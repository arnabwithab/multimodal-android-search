package com.filesearch.android.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Harness check with a one-hot fake backend (NOT a retrieval claim): doc i
 * gets query i's vector, so a correct harness must score R@1 == 1.0, and a
 * deliberately wrong expectation must score 0.0. Real R@1 needs real weights.
 */
class EvalRunnerTest {
    /** One-hot vectors: known query/doc strings map to basis vectors, unknown to zero. */
    private class OneHotBackend(
        private val queries: List<String>,
        private val docKeys: List<String>,
        private val vecDim: Int = 32,
    ) : EmbedBackend {
        private fun oneHot(idx: Int): FloatArray = FloatArray(vecDim) { if (it == idx) 1f else 0f }

        override fun embedTexts(texts: List<String>): List<FloatArray> =
            texts.map { s ->
                if (s.startsWith(QUERY_PREFIX)) {
                    val i = queries.indexOf(s.removePrefix(QUERY_PREFIX))
                    if (i >= 0) oneHot(i) else FloatArray(vecDim)
                } else {
                    val i = docKeys.indexOf(s)
                    if (i >= 0) oneHot(i) else FloatArray(vecDim)
                }
            }

        override fun embedImages(blobs: List<ByteArray>): List<FloatArray> = blobs.map { FloatArray(vecDim) }

        override fun embedAudio(windows: List<ByteArray>): List<FloatArray> = windows.map { FloatArray(vecDim) }
    }

    private fun twelveDocs(): List<EvalRunner.EvalDoc> = List(12) { i -> EvalRunner.EvalDoc(i.toLong(), "Title $i", "body text $i") }

    private fun harness(docs: List<EvalRunner.EvalDoc>): Pair<Embedder, EmbedBackend> {
        val queries = docs.indices.map { "query $it" }
        val backend = OneHotBackend(queries, docs.map { docPrefix(it.title, it.text) })
        return Embedder(backend, dim = 16) to backend
    }

    @Test
    fun `harness math perfect score when each query matches its doc`() {
        val docs = twelveDocs()
        val (embedder, _) = harness(docs)
        val cases = docs.indices.map { EvalRunner.EvalCase("query $it", it.toLong()) }
        val report = EvalRunner.runEval(embedder, docs, cases)
        assertEquals(12, report.total)
        assertEquals(12, report.hits)
        assertEquals(1.0, report.rAt1, 1e-9)
        assertTrue(report.meaningOnly)
    }

    @Test
    fun `harness math zero when expectations are wrong`() {
        val docs = twelveDocs()
        val (embedder, _) = harness(docs)
        // Every case expects the next doc: all miss.
        val cases = docs.indices.map { EvalRunner.EvalCase("query $it", ((it + 1) % docs.size).toLong()) }
        val report = EvalRunner.runEval(embedder, docs, cases)
        assertEquals(12, report.total)
        assertEquals(0, report.hits)
        assertEquals(0.0, report.rAt1, 1e-9)
    }

    @Test
    fun `empty cases yield total 0 and rAt1 0`() {
        val docs = twelveDocs()
        val (embedder, _) = harness(docs)
        val report = EvalRunner.runEval(embedder, docs, emptyList())
        assertEquals(0, report.total)
        assertEquals(0, report.hits)
        assertEquals(0.0, report.rAt1, 1e-9)
    }

    @Test
    fun `all-zero similarities score zero hits never tie-luck`() {
        // Empty vocab maps every string to the zero vector, so all candidates
        // tie at score 0.0; the stable-sort first row must NOT count as a hit
        // (case 0 expects doc 0, which would tie-luck without the guard).
        val docs = twelveDocs()
        val embedder = Embedder(OneHotBackend(emptyList(), emptyList()), dim = 16)
        val cases = docs.indices.map { EvalRunner.EvalCase("query $it", it.toLong()) }
        val report = EvalRunner.runEval(embedder, docs, cases)
        assertEquals(12, report.total)
        assertEquals(0, report.hits)
        assertEquals(0.0, report.rAt1, 1e-9)
    }
}
