package com.filesearch.android.app

/**
 * Pure-JVM UI state + formatting for F011.
 * The 3 Compose screens (SearchScreen, StatusSheet, Settings) bind these states on device;
 * Paparazzi snapshots + thumbnail test happen on a phone. No Android/Compose imports here.
 */

private val pageKinds = setOf("page", "page_image")
private val timeKinds = setOf("audio", "frame")

/** One search result row: thumbnail lookup key, file/kind badge, marked excerpt, location label. */
data class ResultRow(
    val fileName: String,
    val kind: String,
    val excerpt: String,
    val locLabel: String,
    val strong: Boolean,
    val thumbKey: String,
)

/**
 * Location label per kind: page kinds -> "p.N" (0-based loc to 1-based);
 * audio/frame -> "MM:SS" seconds; chunk (doc text) and anything else -> "".
 */
fun formatLoc(kind: String, loc: Double): String = when (kind) {
    in pageKinds -> "p.${(loc.toInt() + 1).coerceAtLeast(1)}"
    in timeKinds -> {
        val s = loc.toLong().coerceAtLeast(0)
        if (s >= 3600) "%d:%02d:%02d".format(java.util.Locale.US, s / 3600, (s % 3600) / 60, s % 60)
        else "%02d:%02d".format(java.util.Locale.US, s / 60, s % 60)
    }
    else -> ""
}

/**
 * Wraps each match range in «». Out-of-bounds ends clamp, overlaps merge
 * (sort by start; drop fully-covered), script-agnostic char indices.
 */
fun markExcerpt(text: String, ranges: List<IntRange>): String {
    if (text.isEmpty() || ranges.isEmpty()) return text
    val sorted = ranges.mapNotNull { r ->
        val s = r.first.coerceIn(0, text.length)
        val e = r.last.coerceIn(-1, text.length - 1)
        if (s > e) null else s..e
    }.sortedBy { it.first }
    if (sorted.isEmpty()) return text
    val merged = mutableListOf(sorted[0])
    for (r in sorted.drop(1)) {
        val last = merged.last()
        if (r.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, r.last)
        else merged += r
    }
    val out = StringBuilder()
    var i = 0
    for (r in merged) {
        out.append(text, i, r.first).append('«').append(text, r.first, r.last + 1).append('»')
        i = r.last + 1
    }
    return out.append(text, i, text.length).toString()
}

/** Search screen state; weak hits (score < 0.5x top) fold under "weaker matches" until expanded. */
data class SearchUiState(val query: String, val rows: List<ResultRow>, val expanded: Boolean = false) {
    fun visibleRows(): List<ResultRow> = if (expanded) rows else rows.filter { it.strong }
    fun weakCount(): Int = rows.count { !it.strong }
}

/**
 * Honest ETA label; estimate comes from MeasuredCosts (F009).
 * Non-positive -> "finishing…", <60s -> secs, <60min -> mins, else "≈H h M min left".
 */
fun formatRemaining(ms: Long): String = when {
    ms <= 0 -> "finishing…"
    ms < 60_000 -> "≈${ms / 1000} sec left"
    ms < 3_600_000 -> "≈${ms / 60_000} min left"
    else -> "≈${ms / 3_600_000} h ${(ms % 3_600_000) / 60_000} min left"
}

private val allowedExcluded = setOf("image", "pdf", "doc", "audio", "video")
private val validCloudProviders = setOf("google", "whisper", "elevenlabs", "tts-google")

/** Settings screen state; cloud toggles need a named provider or they cannot work. */
data class SettingsState(
    val folders: List<String>,
    val excludedTypes: List<String>,
    val chargerOnly: Boolean,
    val wifiOnlyModels: Boolean,
    val cloudStt: Boolean,
    val cloudTts: Boolean,
) {
    fun validate(sttProvider: String = "none", ttsProvider: String = "none"): List<String> = buildList {
        if (folders.none { it.isNotBlank() }) add("Pick at least one folder to watch.")
        if (cloudStt && sttProvider !in validCloudProviders)
            add("Cloud voice input needs a provider (Google STT or Whisper).")
        if (cloudTts && ttsProvider !in validCloudProviders)
            add("Cloud read-aloud needs a provider (Google TTS or ElevenLabs).")
        (excludedTypes.map { it.trim().lowercase() } - allowedExcluded).forEach { add("Unknown excluded type: $it.") }
    }

    /** What "remove app data" deletes per design §8. */
    fun wipeSummary(): String =
        "Remove app data deletes downloaded models, the search index, logs, cloud transcripts, and stored voice keys."
}
