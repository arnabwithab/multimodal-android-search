package com.filesearch.android.engine

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

/**
 * @param isScanned image-only page (no text layer); set by the Pdfium probe, not inferred here.
 */
fun classifyPage(text: String, isLocked: Boolean, isScanned: Boolean): PageClass {
    if (isLocked) return PageClass.LOCKED
    if (isScanned || text.trim().length < MIN_TEXT_CHARS) return PageClass.RENDER_ONLY
    if (glyphOrderGarbleRate(text) >= GARBLE_THRESHOLD) return PageClass.RENDER_ONLY
    if (hasLostArabicLetters(text)) return PageClass.OCR_RETRY
    return PageClass.TEXT
}

// shortcut: inline ranges; move to an engine/lang/ table (per AGENTS.md) when a new script needs support.
private fun isIndicVowelSign(c: Char): Boolean = c.code in 0x093E..0x094C || // Devanagari
    c.code in 0x09BE..0x09CC || // Bengali
    c.code in 0x0A3E..0x0A4C || // Gurmukhi
    c.code in 0x0ABE..0x0ACC || // Gujarati
    c.code in 0x0B3E..0x0B4C || // Oriya
    c.code in 0x0BBE..0x0BCC || // Tamil
    c.code in 0x0C3E..0x0C4C || // Telugu
    c.code in 0x0CBE..0x0CCC || // Kannada
    c.code in 0x0D3E..0x0D4C // Malayalam

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

private fun isArabic(c: Char): Boolean = c.code in 0x0600..0x06FF
private fun isJoiningForm(c: Char): Boolean = c.code in 0xFB50..0xFDFF || c.code in 0xFE70..0xFEFF

/** True when Arabic letters came out isolated with spaces ("ا ل ع ر ب") instead of joined words. */
fun hasLostArabicLetters(text: String): Boolean {
    if (text.length <= 50) return false
    var arabic = 0
    for (c in text) {
        if (isJoiningForm(c)) return false
        if (isArabic(c)) arabic++
    }
    if (arabic == 0) return false
    val isolated = text.split(Regex("\\s+|\u200B")).count { it.length == 1 && isArabic(it[0]) }
    return isolated.toDouble() / arabic > 0.40
}

data class TwoPassPlan(
    val firstPassPages: IntRange,
    val secondPassPages: IntRange?,
    val longFile: Boolean
)

/** First 30 pages now, the rest after everything else is searchable. */
fun twoPassPlan(pageCount: Int): TwoPassPlan {
    val count = pageCount.coerceAtLeast(0).coerceAtMost(MAX_PAGES) // tailSkipped() handles the rest
    val second = if (count > FIRST_PASS_PAGES) FIRST_PASS_PAGES until count else null
    return TwoPassPlan(0 until minOf(count, FIRST_PASS_PAGES), second, second != null)
}

// shortcut: tail beyond 5000 pages skipped, revisit if users actually search such files.
fun tailSkipped(pageCount: Int): Boolean = pageCount > MAX_PAGES
