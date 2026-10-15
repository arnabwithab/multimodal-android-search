package com.filesearch.android.engine

import java.util.concurrent.locks.ReentrantLock

/** Task prefix for search queries. Applies to TEXT ONLY — media inputs take no prefix. */
const val QUERY_PREFIX = "task: search result | query: "

// Code retrieval prefix lands with code-retrieval wiring: "task: code retrieval | query: ".

/** Doc prefix `title: {t} | text: {c}`. Applies to TEXT ONLY — media inputs take no prefix. */
fun docPrefix(
    title: String,
    text: String,
): String = "title: $title | text: $text"

/**
 * Device-side embedding backend (LiteRT-LM `.litertlm`; tests use a fake).
 * One lock in [Embedder] serialises all calls — the LiteRT session is
 * single-threaded (docs/architecture.md §3).
 */
interface EmbedBackend {
    fun embedTexts(texts: List<String>): List<FloatArray>

    fun embedImages(blobs: List<ByteArray>): List<FloatArray>

    fun embedAudio(windows: List<ByteArray>): List<FloatArray>
}

/**
 * Prefixes text, fans out to [backend] under one lock, MRL-truncates each
 * vector to [dim] via [Vectors.truncateRenorm] (re-normalised; query and docs
 * share 256d v1). Media takes no prefix.
 */
class Embedder(private val backend: EmbedBackend, val dim: Int = 256) {
    private val lock = ReentrantLock()

    private inline fun <T> guarded(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    fun embedQuery(q: String): FloatArray =
        guarded {
            Vectors.truncateRenorm(backend.embedTexts(listOf(QUERY_PREFIX + q)).single(), dim)
        }

    fun embedDocs(docs: List<Pair<String, String>>): List<FloatArray> =
        guarded {
            val out = backend.embedTexts(docs.map { (t, c) -> docPrefix(t, c) })
            require(out.size == docs.size) { "embedDocs: backend returned ${out.size} vectors for ${docs.size} docs" }
            out.map { Vectors.truncateRenorm(it, dim) }
        }

    fun embedImage(blob: ByteArray): FloatArray =
        guarded {
            Vectors.truncateRenorm(backend.embedImages(listOf(blob)).single(), dim)
        }

    fun embedAudio(window: ByteArray): FloatArray =
        guarded {
            Vectors.truncateRenorm(backend.embedAudio(listOf(window)).single(), dim)
        }

    /** `model@rev runtime dtype dim budgets`; any change forces full re-embed. */
    fun fingerprint(
        model: String,
        rev: String,
        runtime: String,
        dtype: String,
        budgets: String,
    ): String = "$model@$rev $runtime $dtype $dim $budgets"
}
