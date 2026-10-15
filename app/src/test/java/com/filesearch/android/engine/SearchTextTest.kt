package com.filesearch.android.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchTextTest {
    @Test
    fun `NFKC folds compatibility characters`() {
        assertEquals("ABC", SearchText.normalize("ＡＢＣ")) // fullwidth
        assertEquals("fi", SearchText.normalize("ﬁ")) // ligature
        assertEquals("2", SearchText.normalize("²")) // superscript
    }

    @Test
    fun `tatweel and harakat are stripped`() {
        assertEquals("كتاب", SearchText.normalize("كـتـاب"))
        assertEquals("مرحبا", SearchText.normalize("مَرْحَبًا"))
        assertEquals("سلام", SearchText.normalize("سَلام"))
    }

    @Test
    fun `extended harakat marks strip to bare form`() {
        val bare = "ا" // U+0627, verified 0x627 above
        assertEquals(bare, SearchText.normalize("ا" + 0x0653.toChar())) // maddah above
        assertEquals(bare, SearchText.normalize("ا" + 0x0654.toChar())) // hamza above
        assertEquals(bare, SearchText.normalize("ا" + 0x0655.toChar())) // hamza below
        assertEquals(bare, SearchText.normalize("ا" + 0x0670.toChar())) // superscript alef
    }

    @Test
    fun `alef variants fold to bare alef`() {
        assertEquals("احمد", SearchText.normalize("أحمد")) // U+0623
        assertEquals("امال", SearchText.normalize("آمال")) // U+0622
        assertEquals("الى", SearchText.normalize("إلى")) // U+0625
        assertEquals("ا", SearchText.normalize(0x0671.toChar().toString())) // wasla
    }

    @Test
    fun `zero-width space breaks words, joiners are stripped`() {
        assertEquals("a b", SearchText.normalize("a" + 0x200B.toChar() + "b")) // zero-width space
        assertEquals("ab", SearchText.normalize("a" + 0xFEFF.toChar() + "b")) // BOM
        assertEquals("ab", SearchText.normalize("a" + 0x200C.toChar() + "b")) // ZWNJ joiner
        assertEquals("ab", SearchText.normalize("a" + 0x200D.toChar() + "b")) // ZWJ joiner
    }

    @Test
    fun `hasWords accepts prose and rejects digit-punctuation dumps`() {
        assertTrue(SearchText.hasWords("hello world"))
        assertTrue(SearchText.hasWords("sleep"))
        assertFalse(SearchText.hasWords(""))
        assertFalse(SearchText.hasWords("   "))
        assertFalse(SearchText.hasWords("12345 !!!"))
    }

    @Test
    fun `hasWords threshold is 20 percent letters`() {
        assertTrue(SearchText.hasWords("a....")) // 1/5 = 20%: counts
        assertFalse(SearchText.hasWords("a.....")) // 1/6 < 20%: keyword-only
    }

    @Test
    fun `isLookup catches codes and numbers but not prose`() {
        assertTrue(SearchText.isLookup("K7Q2LM"))
        assertTrue(SearchText.isLookup("404"))
        assertTrue(SearchText.isLookup("3.14"))
        assertTrue(SearchText.isLookup("abc123"))
        assertFalse(SearchText.isLookup("where they talk about sleep"))
        assertFalse(SearchText.isLookup("sleep"))
        assertFalse(SearchText.isLookup("photo 2024"))
        assertFalse(SearchText.isLookup(""))
        assertFalse(SearchText.isLookup("   "))
    }

    @Test
    fun `normalisation is script-agnostic across six-plus scripts`() {
        // CJK/Thai have no spaces: they match as prefixes downstream (FTS),
        // here normalize must simply pass them through unharmed.
        val queries =
            mapOf(
                "latin" to "where they talk about sleep",
                "bengali" to "ঘুম নিয়ে",
                "arabic" to "النوم",
                "devanagari" to "नींद",
                "cjk" to "睡眠",
                "thai" to "การนอนหลับ",
                "cyrillic" to "сон",
            )
        for ((script, q) in queries) {
            val norm = SearchText.normalize(q)
            assertTrue(norm.isNotEmpty(), "$script must survive normalize")
            assertTrue(SearchText.hasWords(norm), "$script must count as words")
            assertFalse(SearchText.isLookup(q), "$script prose is not a lookup")
        }
    }
}
