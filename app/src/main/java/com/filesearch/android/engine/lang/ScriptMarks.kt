package com.filesearch.android.engine.lang

private const val DEVANAGARI_SIGNS_FIRST = 0x093E
private const val DEVANAGARI_SIGNS_LAST = 0x094C
private const val BENGALI_SIGNS_FIRST = 0x09BE
private const val BENGALI_SIGNS_LAST = 0x09CC
private const val GURMUKHI_SIGNS_FIRST = 0x0A3E
private const val GURMUKHI_SIGNS_LAST = 0x0A4C
private const val GUJARATI_SIGNS_FIRST = 0x0ABE
private const val GUJARATI_SIGNS_LAST = 0x0ACC
private const val ORIYA_SIGNS_FIRST = 0x0B3E
private const val ORIYA_SIGNS_LAST = 0x0B4C
private const val TAMIL_SIGNS_FIRST = 0x0BBE
private const val TAMIL_SIGNS_LAST = 0x0BCC
private const val TELUGU_SIGNS_FIRST = 0x0C3E
private const val TELUGU_SIGNS_LAST = 0x0C4C
private const val KANNADA_SIGNS_FIRST = 0x0CBE
private const val KANNADA_SIGNS_LAST = 0x0CCC
private const val MALAYALAM_SIGNS_FIRST = 0x0D3E
private const val MALAYALAM_SIGNS_LAST = 0x0D4C

/**
 * Indic vowel-sign (matra) ranges, one per script.
 * Data table per AGENTS.md: new-script support extends this list, never code.
 */
val INDIC_VOWEL_SIGN_RANGES: List<IntRange> =
    listOf(
        // Devanagari
        DEVANAGARI_SIGNS_FIRST..DEVANAGARI_SIGNS_LAST,
        // Bengali
        BENGALI_SIGNS_FIRST..BENGALI_SIGNS_LAST,
        // Gurmukhi
        GURMUKHI_SIGNS_FIRST..GURMUKHI_SIGNS_LAST,
        // Gujarati
        GUJARATI_SIGNS_FIRST..GUJARATI_SIGNS_LAST,
        // Oriya
        ORIYA_SIGNS_FIRST..ORIYA_SIGNS_LAST,
        // Tamil
        TAMIL_SIGNS_FIRST..TAMIL_SIGNS_LAST,
        // Telugu
        TELUGU_SIGNS_FIRST..TELUGU_SIGNS_LAST,
        // Kannada
        KANNADA_SIGNS_FIRST..KANNADA_SIGNS_LAST,
        // Malayalam
        MALAYALAM_SIGNS_FIRST..MALAYALAM_SIGNS_LAST,
    )
