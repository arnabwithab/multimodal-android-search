package com.filesearch.android.engine

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Hybrid rank fusion (docs/design.md §6, docs/architecture.md §5 Searcher):
 *
 * score = meaning + WORD_WEIGHT x wordCoverage^WORD_EXPONENT [+ KIND_BOOST]
 *
 * - Meaning: cosine vs every candidate vector, centred per modality (text
 *   scores run hotter than image/audio), z-scored globally; Z_GATE = "stands
 *   out or it doesn't count". Below the gate the meaning score is 0.
 * - Words: FTS5 OR-query with prefix-last-while-typing upstream; this object
 *   takes the resulting per-row wordCoverage in [0,1]. Lookup queries
 *   (codes/numbers, see SearchText.isLookup) double the word weight because
 *   embeddings cannot hold exact IDs like K7Q2LM.
 * - Kind hint: "... in a video" boosts the matching kind by KIND_BOOST.
 * - strongCount: rows at/above half the top score stay visible; the rest fold
 *   under "weaker matches" (design §4). Empty in -> empty out: the empty
 *   state says "nothing stands out", never a random file.
 *
 * Pure function of the rows it is given — no DB dependency, so this runs on
 * Linux JVM tests. NOTE: the make-eval R@1 gate needs real query vectors and
 * therefore runs when F013 (Embedder) lands; until then fusion is verified
 * against hand-computed weights in SearcherTest.
 */
object Searcher {
    /** Meaning z-score gate (design §6 "Meaning"). */
    const val Z_GATE = 2.0

    /** Word-coverage weight (design §6 "Fusion"). */
    const val WORD_WEIGHT = 2.0

    /** Word-coverage exponent: full coverage pays, partial matches fade. */
    const val WORD_EXPONENT = 1.5

    /** Lookup-query multiplier on WORD_WEIGHT (design §6 "Fusion"). */
    const val LOOKUP_WORD_MULTIPLIER = 2.0

    /** Exact kind-hint bonus (design §6 "Fusion"). */
    const val KIND_BOOST = 1.0

    /** Strong rows score >= this fraction of the top (design §4 rows). */
    const val STRONG_FRACTION = 0.5

    /**
     * One retrievable row. Plain class (not data): equals/hashCode ignore [vec]
     * so Set/distinct group by identity + scores, never by FloatArray reference.
     *
     * @param ftsRank raw FTS5 rank carried for the caller; fusion uses
     *   [wordCoverage], the 0..1 normalisation of the word match.
     */
    class Cand(
        val id: Long,
        val modality: String,
        val kind: String,
        val vec: FloatArray,
        val ftsRank: Double,
        val wordCoverage: Double,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Cand &&
                id == other.id &&
                modality == other.modality &&
                kind == other.kind &&
                ftsRank == other.ftsRank &&
                wordCoverage == other.wordCoverage

        override fun hashCode(): Int {
            var h = id.hashCode()
            h = 31 * h + modality.hashCode()
            h = 31 * h + kind.hashCode()
            h = 31 * h + ftsRank.hashCode()
            h = 31 * h + wordCoverage.hashCode()
            return h
        }

        override fun toString(): String = "Cand(id=$id, modality=$modality, kind=$kind, ftsRank=$ftsRank, wordCoverage=$wordCoverage)"
    }

    data class Hit(val id: Long, val score: Double, val strong: Boolean)

    /**
     * Fuse meaning + words + kind hint. Returns hits sorted by score,
     * strongest first. [isLookup] comes from SearchText.isLookup on the raw
     * query; kept as a flag (default false) so this stays a pure function.
     */
    fun fuse(
        cands: List<Cand>,
        queryVec: FloatArray,
        kindHint: String? = null,
        isLookup: Boolean = false,
    ): List<Hit> {
        if (cands.isEmpty()) return emptyList()
        val raw = DoubleArray(cands.size) { Vectors.cosine(queryVec, cands[it].vec) }
        // Per-modality mean centre: each modality's scores stand on their own scale.
        val groups = cands.indices.groupBy { cands[it].modality }
        val meanOf = groups.mapValues { (_, idxs) -> idxs.sumOf { raw[it] } / idxs.size }
        val centred = DoubleArray(cands.size) { raw[it] - (meanOf[cands[it].modality] ?: 0.0) }
        val globalMean = centred.average()
        val std = sqrt(centred.sumOf { (it - globalMean) * (it - globalMean) } / centred.size)
        val wordWeight = WORD_WEIGHT * if (isLookup) LOOKUP_WORD_MULTIPLIER else 1.0
        val scored =
            cands.indices.map { i ->
                val z = if (std > 0.0) centred[i] / std else Double.NEGATIVE_INFINITY
                val meaning = if (z >= Z_GATE) z else 0.0
                val coverage = cands[i].wordCoverage.coerceIn(0.0, 1.0)
                var s = meaning + wordWeight * coverage.pow(WORD_EXPONENT)
                if (kindHint != null && cands[i].kind.equals(kindHint, ignoreCase = true)) s += KIND_BOOST
                Hit(cands[i].id, s, strong = false)
            }.sortedByDescending { it.score }
        // Honest empty (design §6): nothing stands out -> nothing marked strong.
        // A non-positive top yields an infinite cutoff, so every row lands weak.
        val cutoff = if (scored.first().score <= 0.0) Double.POSITIVE_INFINITY else STRONG_FRACTION * scored.first().score
        return scored.map { it.copy(strong = it.score >= cutoff) }
    }

    /**
     * Visible-row count: hits at >= half the top score. Order-independent
     * (uses the max); 0 for empty or all-zero scores — honest empty state.
     */
    fun strongCount(sorted: List<Hit>): Int {
        if (sorted.isEmpty()) return 0
        val top = sorted.maxOf { it.score }
        // honest empty: no standout, no strong rows
        return if (top <= 0.0) 0 else sorted.count { it.score >= STRONG_FRACTION * top }
    }
}
