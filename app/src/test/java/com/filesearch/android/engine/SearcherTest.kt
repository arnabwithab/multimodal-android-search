package com.filesearch.android.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SearcherTest {
    private val query = floatArrayOf(1f, 0f, 0f, 0f)

    private fun cand(
        id: Long,
        modality: String = "text",
        kind: String = "page",
        vec: FloatArray = floatArrayOf(0f, 1f, 0f, 0f),
        coverage: Double = 0.0,
    ) = Searcher.Cand(id, modality, kind, vec, ftsRank = 0.0, wordCoverage = coverage)

    @Test
    fun `fusion weights are hand-computable when meaning is gated out`() {
        // 3 singleton modalities -> centred cosine is 0 everywhere -> meaning 0.
        val cands =
            listOf(
                cand(1, modality = "text", kind = "page", vec = floatArrayOf(1f, 0f, 0f, 0f), coverage = 1.0),
                cand(2, modality = "image", kind = "image", vec = floatArrayOf(0f, 1f, 0f, 0f), coverage = 0.25),
                cand(3, modality = "audio", kind = "audio", vec = floatArrayOf(0f, 0f, 1f, 0f), coverage = 0.0),
            )
        val hits = Searcher.fuse(cands, query, kindHint = "page").associateBy { it.id }
        // A: 0 + 2.0*1.0^1.5 + 1.0 = 3.0 ; B: 2.0*0.25^1.5 = 0.25 ; C: 0.0
        assertEquals(3.0, hits.getValue(1).score, 1e-9)
        assertEquals(0.25, hits.getValue(2).score, 1e-9)
        assertEquals(0.0, hits.getValue(3).score, 1e-9)
        assertTrue(hits.getValue(1).strong)
        assertTrue(!hits.getValue(2).strong && !hits.getValue(3).strong)
    }

    @Test
    fun `z-gate kills weak meaning but keeps a standout`() {
        // 9 orthogonal distractors + 1 exact match, one modality:
        // centred = -0.1 x9 / +0.9, std = 0.3, standout z = 3.0 >= 2.0.
        val cands =
            List(9) { cand(it.toLong(), coverage = 0.0) } +
                cand(9, vec = floatArrayOf(1f, 0f, 0f, 0f), coverage = 0.0)
        val hits = Searcher.fuse(cands, query).associateBy { it.id }
        assertEquals(3.0, hits.getValue(9).score, 1e-9)
        for (i in 0 until 9) assertEquals(0.0, hits.getValue(i.toLong()).score, 1e-9)
    }

    @Test
    fun `near-tie similarities contribute no meaning`() {
        // n = 4 caps any z-score at 1.5 < 2.0, so even the exact match scores words only.
        val cands =
            listOf(
                cand(1, vec = floatArrayOf(1f, 0f, 0f, 0f), coverage = 1.0),
                cand(2, vec = floatArrayOf(0.9f, 0.4359f, 0f, 0f), coverage = 0.5),
                cand(3, vec = floatArrayOf(0.8f, 0.6f, 0f, 0f), coverage = 0.0),
                cand(4, vec = floatArrayOf(0.7f, 0.7141f, 0f, 0f), coverage = 0.0),
            )
        val hits = Searcher.fuse(cands, query).associateBy { it.id }
        assertEquals(2.0, hits.getValue(1).score, 1e-9)
        assertEquals(2.0 * 0.5 * Math.sqrt(0.5), hits.getValue(2).score, 1e-9)
    }

    @Test
    fun `lookup query doubles the word weight`() {
        val cands =
            listOf(
                cand(1, modality = "text", kind = "chunk", coverage = 1.0),
                cand(2, modality = "image", kind = "image", coverage = 0.25),
            )
        val prose = Searcher.fuse(cands, query, isLookup = false).associateBy { it.id }
        val lookup = Searcher.fuse(cands, query, isLookup = true).associateBy { it.id }
        assertEquals(2.0, prose.getValue(1).score, 1e-9)
        assertEquals(4.0, lookup.getValue(1).score, 1e-9)
        assertEquals(0.25, prose.getValue(2).score, 1e-9)
        assertEquals(0.5, lookup.getValue(2).score, 1e-9)
    }

    @Test
    fun `kind hint adds exactly 1 to the matching kind only`() {
        val cands =
            listOf(
                cand(1, modality = "text", kind = "chunk", coverage = 0.0),
                cand(2, modality = "image", kind = "frame", coverage = 0.0),
            )
        val plain = Searcher.fuse(cands, query).associateBy { it.id }
        val hinted = Searcher.fuse(cands, query, kindHint = "frame").associateBy { it.id }
        assertEquals(plain.getValue(1).score, hinted.getValue(1).score, 1e-12)
        assertEquals(plain.getValue(2).score + 1.0, hinted.getValue(2).score, 1e-12)
    }

    @Test
    fun `strongCount folds weak matches below half the top`() {
        val hits =
            listOf(
                Searcher.Hit(1, 4.0, true),
                Searcher.Hit(2, 3.0, true),
                Searcher.Hit(3, 1.0, false),
                Searcher.Hit(4, 0.5, false),
            )
        assertEquals(2, Searcher.strongCount(hits))
        assertEquals(0, Searcher.strongCount(listOf(Searcher.Hit(1, 0.0, false))))
        assertEquals(0, Searcher.strongCount(emptyList()))
    }

    @Test
    fun `all-zero scores mark nothing strong`() {
        // Honest empty (design §6): gated-out meaning + zero word overlap.
        val cands =
            listOf(
                cand(1, modality = "text", coverage = 0.0),
                cand(2, modality = "image", coverage = 0.0),
            )
        val hits = Searcher.fuse(cands, query)
        assertTrue(hits.all { it.score == 0.0 })
        assertTrue(hits.none { it.strong })
        assertEquals(0, Searcher.strongCount(hits))
    }

    @Test
    fun `empty in means empty out`() {
        assertEquals(emptyList(), Searcher.fuse(emptyList(), query))
        assertEquals(0, Searcher.strongCount(emptyList()))
    }

    @Test
    fun `cross-lingual retrieval works on pure vectors with zero word overlap`() {
        // No shared words (coverage 0 everywhere): a same-direction vector must
        // still surface on meaning alone — no language gate before embedding.
        val q = floatArrayOf(3f, 4f, 0f, 0f)
        val cands =
            List(9) { cand(it.toLong(), modality = "text", vec = floatArrayOf(-4f, 3f, 0f, 0f)) } +
                cand(9, modality = "text", vec = floatArrayOf(6f, 8f, 0f, 0f))
        val hits = Searcher.fuse(cands, q)
        assertEquals(9L, hits.first().id)
        assertTrue(hits.first().score > 2.0)
        assertEquals(1, Searcher.strongCount(hits))
    }

    @Test
    fun `query-doc dim mismatch throws instead of mixing dims`() {
        assertFailsWith<IllegalArgumentException> {
            Searcher.fuse(listOf(cand(1, vec = floatArrayOf(1f, 0f))), floatArrayOf(1f, 0f, 0f, 0f))
        }
    }
}
