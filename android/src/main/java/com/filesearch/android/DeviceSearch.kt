package com.filesearch.android

import com.filesearch.android.app.formatLoc
import com.filesearch.android.app.markExcerpt
import com.filesearch.android.engine.Embedder
import com.filesearch.android.engine.SearchText
import com.filesearch.android.engine.Searcher
import com.filesearch.android.engine.Vectors
import com.filesearch.android.ui.SearchRowUi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One stored segment plus the file/FTS text needed to render a row. */
data class StoredSeg(
    val id: Long,
    val path: String,
    val kind: String,
    val modality: String,
    val excerpt: String?,
    val vec: ByteArray?,
    val ftsName: String,
    /** Page index (pdf) or seconds (audio/frame); 0.0 when meaningless. */
    val loc: Double = 0.0,
)

/** Ranked rows: strong stay visible, weak fold under "weaker matches". */
data class DeviceHits(val strong: List<SearchRowUi>, val weak: List<SearchRowUi>) {
    val weakCount: Int get() = weak.size
}

/**
 * F024 on-device query path: embedQuery -> cosine via [Searcher.fuse] over the
 * stored vectors + [DeviceIndexStore.matchTerms] word coverage -> rows.
 *
 * Zero new ranking logic: [Searcher.fuse] already exists and is tested
 * (engine SearcherTest); this only reads the store, decodes fp16, and maps
 * hits to [SearchRowUi] with the engine [formatLoc]/[markExcerpt] helpers.
 * Honest empty: a blank query, an empty index, or no overlap all yield no
 * rows (the UI already shows "nothing stands out"); zero-score rows are
 * dropped, never parked under the weak fold.
 */
object DeviceSearch {
    /**
     * Query -> FTS-safe terms: engine normalisation, then drop every char
     * that is FTS5 syntax (callers pass plain tokens; raw '-'/'"'/'*' throw).
     * Unicode letters survive, so every script still matches.
     */
    fun sanitizeTerms(query: String): List<String> =
        SearchText.normalize(query).split("\\s+".toRegex())
            .map { tok -> tok.filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }

    /** Little-endian fp16 BLOB -> float32; null on dim mix (never mix dims). */
    fun decodeVec(
        blob: ByteArray,
        dim: Int,
    ): FloatArray? {
        if (blob.size != dim * 2) return null
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dim) { Vectors.fp16BitsToFloat(buf.short) }
    }

    /** Case-insensitive occurrences of each term (for «marking» excerpts). */
    fun findRanges(
        text: String,
        terms: List<String>,
    ): List<IntRange> {
        if (text.isEmpty() || terms.isEmpty()) return emptyList()
        val lower = text.lowercase()
        return terms.flatMap { term ->
            val needle = term.lowercase()
            if (needle.isEmpty()) return@flatMap emptyList()
            buildList {
                var from = 0
                while (true) {
                    val i = lower.indexOf(needle, from)
                    if (i < 0) break
                    add(i..(i + needle.length - 1))
                    from = i + maxOf(1, needle.length)
                }
            }
        }
    }

    /**
     * Pure rank + map (JVM-testable): [matched] is segId -> wordCoverage
     * (fraction of query terms with an FTS hit on that row).
     */
    fun rankAndMap(
        query: String,
        queryVec: FloatArray,
        segs: List<StoredSeg>,
        matched: Map<Long, Double>,
        kindHint: String?,
    ): DeviceHits {
        if (segs.isEmpty()) return DeviceHits(emptyList(), emptyList())
        val terms = sanitizeTerms(query)
        val isLookup = SearchText.isLookup(query)
        val decoded =
            segs.mapNotNull { seg ->
                val vec = seg.vec?.let { decodeVec(it, queryVec.size) } ?: return@mapNotNull null
                seg to Searcher.Cand(seg.id, seg.modality, seg.kind, vec, 0.0, matched[seg.id] ?: 0.0)
            }
        if (decoded.isEmpty()) return DeviceHits(emptyList(), emptyList())
        val bySeg = decoded.associate { it.first.id to it.first }
        val strong = mutableListOf<SearchRowUi>()
        val weak = mutableListOf<SearchRowUi>()
        for (hit in Searcher.fuse(decoded.map { it.second }, queryVec, kindHint, isLookup)) {
            if (hit.score <= 0.0) continue // honest empty: no standout, no fold filler
            val seg = bySeg[hit.id] ?: continue
            val row = seg.toRow(terms)
            if (hit.strong) strong.add(row) else weak.add(row)
        }
        return DeviceHits(strong, weak)
    }

    private fun StoredSeg.toRow(terms: List<String>): SearchRowUi {
        val text = excerpt?.ifEmpty { ftsName } ?: ftsName
        return SearchRowUi(
            fileName = ftsName.ifEmpty { path.substringAfterLast('/') },
            kind = kind,
            excerpt = markExcerpt(text, findRanges(text, terms)),
            locLabel = formatLoc(kind, loc),
            thumbKey = "$kind:$id",
            fileUri = path,
        )
    }

    /**
     * Full query: blank/terms/empty-index checks first (skip inference on an
     * empty index) -> embed (throws when the model is missing — the host
     * catches and keeps old rows) -> per-term FTS coverage -> fuse.
     * Runs off Main: LiteRT is single-flight behind its own mutex, DB behind
     * the store lock. Reads the whole corpus (brute force, like the engine).
     */
    fun search(
        query: String,
        store: DeviceIndexStore,
        embedder: Embedder,
        kindHint: String? = null,
    ): DeviceHits {
        if (query.isBlank()) return DeviceHits(emptyList(), emptyList())
        val terms = sanitizeTerms(query)
        if (terms.isEmpty()) return DeviceHits(emptyList(), emptyList())
        val segs = store.readSegments()
        if (segs.isEmpty()) return DeviceHits(emptyList(), emptyList())
        val queryVec = embedder.embedQuery(query)
        val perTerm = terms.map { t -> runCatching { store.matchTerms("\"$t\"") }.getOrDefault(emptyList()).toSet() }
        val total = terms.size.toDouble()
        val matched = buildMap<Long, Double> {
            for (ids in perTerm) for (id in ids) put(id, (get(id) ?: 0.0) + 1.0 / total)
        }
        return rankAndMap(query, queryVec, segs, matched, kindHint)
    }
}
