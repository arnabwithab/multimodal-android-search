package com.filesearch.android.engine

import com.filesearch.android.engine.lang.INDIC_VOWEL_SIGN_RANGES

/** Device side (PdfiumAndroid) implements this; all logic below stays pure JVM. */
interface PdfTextSource {
    fun pageText(page: Int): String
}

enum class PageClass { TEXT, RENDER_ONLY, OCR_RETRY, LOCKED }

/** Longest side in px when a page is rendered to PNG in cache (device only). */
const val RENDER_LONGEST_PX = 1400

private const val MIN_TEXT_CHARS = 200
private const val GARBLE_THRESHOLD = 0.05
private const val FIRST_PASS_PAGES = 30
private const val MAX_PAGES = 5000
private const val MIN_ARABIC_SAMPLE = 50
private const val LOST_ARABIC_FRACTION = 0.40
private const val ARABIC_BLOCK_FIRST = 0x0600
private const val ARABIC_BLOCK_LAST = 0x06FF
private const val PRESENTATION_A_FIRST = 0xFB50
private const val PRESENTATION_A_LAST = 0xFDFF
private const val PRESENTATION_B_FIRST = 0xFE70
private const val PRESENTATION_B_LAST = 0xFEFF

/**
 * @param isScanned image-only page (no text layer); set by the Pdfium probe, not inferred here.
 */
fun classifyPage(
    text: String,
    isLocked: Boolean,
    isScanned: Boolean,
): PageClass =
    when {
        isLocked -> PageClass.LOCKED
        isScanned || text.trim().length < MIN_TEXT_CHARS -> PageClass.RENDER_ONLY
        glyphOrderGarbleRate(text) >= GARBLE_THRESHOLD -> PageClass.RENDER_ONLY
        hasLostArabicLetters(text) -> PageClass.OCR_RETRY
        else -> PageClass.TEXT
    }

private fun isIndicVowelSign(c: Char): Boolean = INDIC_VOWEL_SIGN_RANGES.any { c.code in it }

/** Fraction of Indic vowel signs detached from their consonant (space/start before). 0.0 when none. */
fun glyphOrderGarbleRate(text: String): Double {
    var signs = 0
    var detached = 0
    text.forEachIndexed { i, c ->
        if (isIndicVowelSign(c)) {
            signs++
            if (i == 0 || text[i - 1].isWhitespace()) detached++
        }
    }
    if (signs == 0) return 0.0
    return detached.toDouble() / signs
}

private fun isArabic(c: Char): Boolean = c.code in ARABIC_BLOCK_FIRST..ARABIC_BLOCK_LAST

private fun isJoiningForm(c: Char): Boolean =
    c.code in PRESENTATION_A_FIRST..PRESENTATION_A_LAST || c.code in PRESENTATION_B_FIRST..PRESENTATION_B_LAST

/** True when Arabic letters came out isolated with spaces ("ا ل ع ر ب") instead of joined words. */
fun hasLostArabicLetters(text: String): Boolean {
    if (text.length <= MIN_ARABIC_SAMPLE) return false
    return arabicIsolatedRate(text)?.let { it > LOST_ARABIC_FRACTION } == true
}

/** Fraction of Arabic letters standing isolated, or null when the page has no Arabic words to judge. */
private fun arabicIsolatedRate(text: String): Double? {
    val arabic = text.count { isArabic(it) }
    if (arabic == 0 || text.any { isJoiningForm(it) }) return null
    val isolated = text.split(Regex("\\s+|\u200B")).count { it.length == 1 && isArabic(it[0]) }
    return isolated.toDouble() / arabic
}

data class TwoPassPlan(
    val firstPassPages: IntRange,
    val secondPassPages: IntRange?,
    val longFile: Boolean,
)

/** First 30 pages now, the rest after everything else is searchable. */
fun twoPassPlan(pageCount: Int): TwoPassPlan {
    val count = pageCount.coerceAtLeast(0).coerceAtMost(MAX_PAGES) // tailSkipped() handles the rest
    val second = if (count > FIRST_PASS_PAGES) FIRST_PASS_PAGES until count else null
    return TwoPassPlan(0 until minOf(count, FIRST_PASS_PAGES), second, second != null)
}

// shortcut: tail beyond 5000 pages skipped, revisit if users actually search such files.
fun tailSkipped(pageCount: Int): Boolean = pageCount > MAX_PAGES
