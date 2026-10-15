package com.digup.android.engine

import java.text.Normalizer

/**
 * Script-agnostic query normalisation (docs/design.md §3, docs/architecture.md
 * module map): NFKC + tatweel/harakat/joiner strip + alef fold. No language
 * allow-lists — unknown scripts pass through to full meaning + generic word
 * search. CJK/Thai (no spaces) match as prefixes at the FTS layer; here they
 * are passed through unharmed.
 */
object SearchText {
    private const val TATWEEL = 'ـ' // U+0640
    private const val HARAKAT_FIRST = 'ً' // U+064B
    // Harakat run U+064B..U+0655 (fatha..hamza-below) + U+0670 superscript alef.
    private const val HARAKAT_LAST_CODE = 0x0655
    private const val SUPERSCRIPT_ALEF_CODE = 0x0670
    private val alefFolds = setOf(0x0622, 0x0623, 0x0625, 0x0671) // + wasla -> U+0627
    private const val ALEF = 'ا' // U+0627

    /** Normalise for keyword matching. Pure string function, no stemmer involved. */
    fun normalize(q: String): String {
        val folded = Normalizer.normalize(q, Normalizer.Form.NFKC)
        val sb = StringBuilder(folded.length)
        for (c in folded) {
            when {
                c == TATWEEL -> Unit // lengthener: drop
                c.code in HARAKAT_FIRST.code..HARAKAT_LAST_CODE ||
                    c.code == SUPERSCRIPT_ALEF_CODE -> Unit // vowel marks: drop
                c.code == 0x200B -> sb.append(' ') // word break per design §3
                c.code == 0xFEFF ||
                    c.code == 0x200C || c.code == 0x200D -> Unit // BOM + joiners: drop
                c.code in alefFolds -> sb.append(ALEF)
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun letterFraction(s: String): Double {
        var letters = 0
        var nonSpace = 0
        for (c in s) {
            if (!c.isWhitespace()) {
                nonSpace++
                if (c.isLetter()) letters++
            }
        }
        return if (nonSpace == 0) 0.0 else letters.toDouble() / nonSpace
    }

    /**
     * True when the text carries real words (>= 20% letters, Char.isLetter so
     * every script counts). Below that the row is keyword-only, no vector.
     */
    fun hasWords(s: String): Boolean =
        s.any { !it.isWhitespace() } && letterFraction(s) >= 0.2

    /**
     * True for codes/numbers only: no letters at all, letter-sparse text, or
     * a single digit-mixed token like K7Q2LM (embeddings cannot hold exact
     * IDs, so the searcher doubles word weight for these).
     */
    fun isLookup(q: String): Boolean {
        var letters = 0
        var digits = 0
        var nonSpace = 0
        var spaced = false
        for (c in q) {
            if (c.isWhitespace()) {
                spaced = true
            } else {
                nonSpace++
                if (c.isLetter()) letters++ else if (c.isDigit()) digits++
            }
        }
        if (nonSpace == 0) return false
        if (letters == 0 || letterFraction(q) < 0.2) return true
        return !spaced && digits > 0 && letters <= 2 * digits
    }
}
