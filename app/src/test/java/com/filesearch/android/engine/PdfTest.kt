package com.filesearch.android.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfTest {
    private fun longLatin(): String = "The quick brown fox jumps over the lazy riverbank. ".repeat(5)

    @Test
    fun `normal long page is TEXT`() {
        assertEquals(PageClass.TEXT, classifyPage(longLatin(), isLocked = false, isScanned = false))
    }

    @Test
    fun `short page is RENDER_ONLY`() {
        assertEquals(PageClass.RENDER_ONLY, classifyPage("Hello world", isLocked = false, isScanned = false))
    }

    @Test
    fun `scanned long page is RENDER_ONLY`() {
        assertEquals(PageClass.RENDER_ONLY, classifyPage(longLatin(), isLocked = false, isScanned = true))
    }

    @Test
    fun `locked page is LOCKED even with long text`() {
        assertEquals(PageClass.LOCKED, classifyPage(longLatin(), isLocked = true, isScanned = false))
    }

    @Test
    fun `garble rate is zero without vowel signs`() {
        assertEquals(0.0, glyphOrderGarbleRate("plain latin text"))
        assertEquals(0.0, glyphOrderGarbleRate(""))
    }

    @Test
    fun `garble rate hits threshold at exactly 5 percent`() {
        // 20 matras, 1 detached by space -> 0.05 -> garble per architecture >=5%.
        val text = "x ".repeat(120) + "का".repeat(19) + " ा"
        assertEquals(0.05, glyphOrderGarbleRate(text), 1e-9)
        assertEquals(PageClass.RENDER_ONLY, classifyPage(text, isLocked = false, isScanned = false))
    }

    @Test
    fun `garble rate below threshold stays TEXT`() {
        // 40 matras, 1 detached -> 0.025 -> not garble.
        val text = "x ".repeat(120) + "का".repeat(39) + " ा"
        assertEquals(0.025, glyphOrderGarbleRate(text), 1e-9)
        assertEquals(PageClass.TEXT, classifyPage(text, isLocked = false, isScanned = false))
    }

    @Test
    fun `bengali vowel sign counts as indic sign`() {
        val text = "x ".repeat(120) + "কা".repeat(19) + " া"
        assertTrue(glyphOrderGarbleRate(text) >= 0.05)
    }

    @Test
    fun `tamil vowel sign counts as indic sign`() {
        val text = "x ".repeat(120) + "கா".repeat(19) + " ா"
        assertTrue(glyphOrderGarbleRate(text) >= 0.05)
    }

    @Test
    fun `zwsp counts as word break for isolated arabic`() {
        val zwsp = 0x200B.toChar().toString() // zero-width space, built not typed
        val text = "ا$zwsp ل$zwsp ع$zwsp ر$zwsp ب$zwsp ".repeat(20) + "x ".repeat(60)
        assertTrue(hasLostArabicLetters(text))
    }

    @Test
    fun `spaced arabic letters trigger OCR retry`() {
        val text = "ا ل ع ر ب ي ة ".repeat(20)
        assertTrue(text.length > 200)
        assertTrue(hasLostArabicLetters(text))
        assertEquals(PageClass.OCR_RETRY, classifyPage(text, isLocked = false, isScanned = false))
    }

    @Test
    fun `normal joined arabic passes as TEXT`() {
        val text = "العربية لغة جميلة يتحدث بها الملايين حول العالم ".repeat(5)
        assertTrue(text.trim().length >= 200)
        assertFalse(hasLostArabicLetters(text))
        assertEquals(PageClass.TEXT, classifyPage(text, isLocked = false, isScanned = false))
    }

    @Test
    fun `lost arabic needs length and joining forms`() {
        assertFalse(hasLostArabicLetters("ا ل ع ر ب"))
        assertFalse(hasLostArabicLetters(longLatin()))
        // Shaped presentation forms present -> not lost, even with spaced letters.
        assertFalse(hasLostArabicLetters("ا ل ع ر ب ي ة ".repeat(20) + "ﹴ"))
    }

    @Test
    fun `twenty pages is a single pass`() {
        val plan = twoPassPlan(20)
        assertEquals(0..19, plan.firstPassPages)
        assertNull(plan.secondPassPages)
        assertFalse(plan.longFile)
    }

    @Test
    fun `thirty pages is still a single pass`() {
        val plan = twoPassPlan(30)
        assertEquals(0..29, plan.firstPassPages)
        assertNull(plan.secondPassPages)
        assertFalse(plan.longFile)
    }

    @Test
    fun `hundred pages splits thirty then rest`() {
        val plan = twoPassPlan(100)
        assertEquals(0..29, plan.firstPassPages)
        assertEquals(30..99, plan.secondPassPages)
        assertTrue(plan.longFile)
    }

    @Test
    fun `tail skipped only above 5000`() {
        assertFalse(tailSkipped(5000))
        assertTrue(tailSkipped(5001))
    }

    @Test
    fun `huge file plans only up to the tail cap`() {
        val plan = twoPassPlan(6000)
        assertTrue(tailSkipped(6000))
        assertEquals(0..29, plan.firstPassPages)
        assertEquals(30..4999, plan.secondPassPages)
    }
}
