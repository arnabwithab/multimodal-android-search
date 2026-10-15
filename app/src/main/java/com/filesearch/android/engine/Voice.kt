package com.filesearch.android.engine

/**
 * Voice toggles (design §7): two independent cloud switches, both OFF on install.
 * Mic/audio/recognizer stay device-side; keys live in EncryptedSharedPreferences (arch §7).
 * Provider reads "none" while its toggle is off.
 */
data class VoiceConfig(
    val cloudStt: Boolean = false,
    val cloudTts: Boolean = false,
    val sttProvider: String = "none",
    val ttsProvider: String = "none",
) {
    val effectiveSttProvider: String get() = if (cloudStt) sttProvider else "none"
    val effectiveTtsProvider: String get() = if (cloudTts) ttsProvider else "none"
}

/** "heard: …" confirmation plus language chip for the search bar (arch §7 input). */
fun heardLabel(
    transcript: String,
    langTag: String,
): String = "heard: $transcript [$langTag]"

/**
 * Cloud transcript row (arch §7 boost): IndexStore writes these as extra kind=chunk
 * FTS rows flagged transcript=api; vectors unchanged.
 */
data class TranscriptRow(
    val fileId: Long,
    val text: String,
    val origin: String = "transcript=api",
)

/** Build one transcript boost row for [fileId] (arch §7: extra chunk FTS row). */
fun transcriptRow(
    text: String,
    fileId: Long,
): TranscriptRow = TranscriptRow(fileId = fileId, text = text)

/**
 * FileIds whose transcript rows the device must delete when the cloud toggle flips
 * off (design §7 zero residue: only transcript=api rows qualify).
 */
fun purgeOnToggleOff(storedRows: List<TranscriptRow>): List<Long> =
    storedRows.filter { it.origin == "transcript=api" }.map { it.fileId }.distinct()

/**
 * Per-minute cloud-usage tally the metering UI reads (arch §7); device adds
 * measured audio millis, never audio content.
 */
class CostMeter(var millisUsed: Long = 0) {
    fun add(millis: Long) {
        require(millis >= 0)
        millisUsed += millis
    }

    fun minuteBucket(): Long = millisUsed / MS_PER_MINUTE
}

private const val MS_PER_MINUTE = 60_000L

/**
 * Offline fallback (design §7 / arch §7): the typed query always works.
 * On device, on-device STT is tried first; cloud failure never blocks search.
 */
@Suppress("UNUSED_PARAMETER")
fun queryFallback(
    cloudFailed: Boolean,
    typed: String,
): String = typed
