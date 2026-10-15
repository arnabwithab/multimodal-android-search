package com.filesearch.android.engine

/**
 * JVM eval runner: meaning-only R@1 over [EvalDoc]s for [EvalCase] queries.
 *
 * Honest scope: this measures the MEANING layer only — every candidate is
 * built with wordCoverage 0.0 and fused with isLookup=false, so the word
 * layer (FTS wiring) and kind hints contribute nothing. A high score here
 * says the vectors separate the corpus, not that full hybrid search works.
 * Real signal requires real LiteRT-LM weights (F013 gate); the one-hot
 * fake backend in EvalRunnerTest proves harness math only.
 *
 * Empty [cases] yields total 0, hits 0, rAt1 0.0 (no queries, no recall —
 * 0.0, not NaN, so gates can compare numerically).
 */
object EvalRunner {
    data class EvalDoc(val id: Long, val title: String, val text: String)

    data class EvalCase(val query: String, val expectDocId: Long)

    data class EvalReport(val rAt1: Double, val hits: Int, val total: Int, val meaningOnly: Boolean = true)

    fun runEval(
        embedder: Embedder,
        docs: List<EvalDoc>,
        cases: List<EvalCase>,
    ): EvalReport {
        if (cases.isEmpty()) return EvalReport(rAt1 = 0.0, hits = 0, total = 0)
        val docVecs = embedder.embedDocs(docs.map { it.title to it.text })
        val cands =
            docs.mapIndexed { i, d ->
                Searcher.Cand(
                    id = d.id,
                    modality = "text",
                    kind = "chunk",
                    vec = docVecs[i],
                    ftsRank = 0.0,
                    wordCoverage = 0.0,
                )
            }
        var hits = 0
        for (c in cases) {
            val qv = embedder.embedQuery(c.query)
            val top = Searcher.fuse(cands, qv, isLookup = false).firstOrNull()
            // Tie-luck guard: all-zero similarities leave every candidate at
            // score 0.0, and the stable-sort first row must NOT count as a hit.
            if (top != null && top.score > 0.0 && top.id == c.expectDocId) hits++
        }
        return EvalReport(rAt1 = hits.toDouble() / cases.size, hits = hits, total = cases.size)
    }
}
