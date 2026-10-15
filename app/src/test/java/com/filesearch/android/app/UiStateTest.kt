package com.filesearch.android.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiStateTest {
    // formatLoc

    @Test
    fun `page kind formats 0-based loc as p-dot-1-based`() {
        assertEquals("p.5", formatLoc("page", 4.0))
        assertEquals("p.1", formatLoc("page_image", 0.0))
    }

    @Test
    fun `audio loc formats as MM-SS`() {
        assertEquals("02:31", formatLoc("audio", 151.0))
        assertEquals("01:05", formatLoc("frame", 65.0))
    }

    @Test
    fun `audio loc pins ASCII digits under Arabic locale`() {
        val prev = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar"))
            assertEquals("02:31", formatLoc("audio", 151.0))
        } finally {
            java.util.Locale.setDefault(prev)
        }
    }

    @Test
    fun `negative page loc clamps to p-1`() {
        assertEquals("p.1", formatLoc("page", -5.0))
    }

    @Test
    fun `audio over an hour formats H-MM-SS`() {
        assertEquals("1:02:03", formatLoc("audio", 3723.0))
    }

    @Test
    fun `zero audio loc is 00-00`() {
        assertEquals("00:00", formatLoc("audio", 0.0))
    }

    @Test
    fun `chunk and unknown kinds have no loc label`() {
        assertEquals("", formatLoc("chunk", 3.0))
        assertEquals("", formatLoc("bogus", 3.0))
        assertEquals("", formatLoc("name", 1.0))
    }

    // markExcerpt

    @Test
    fun `single match gets guillemets`() {
        assertEquals("the «cat» sat", markExcerpt("the cat sat", listOf(4..6)))
    }

    @Test
    fun `multiple matches each marked`() {
        assertEquals("«a» b «c»", markExcerpt("a b c", listOf(0..0, 4..4)))
    }

    @Test
    fun `overlapping ranges merge into one mark`() {
        assertEquals("«abcd»", markExcerpt("abcd", listOf(0..2, 1..3)))
    }

    @Test
    fun `fully covered range is dropped`() {
        assertEquals("«abcd»", markExcerpt("abcd", listOf(0..3, 1..2)))
    }

    @Test
    fun `adjacent ranges render as one mark`() {
        assertEquals("«abc»", markExcerpt("abc", listOf(0..1, 2..2)))
    }

    @Test
    fun `out-of-bounds ranges clamp`() {
        assertEquals("«ab»", markExcerpt("ab", listOf(-5..10)))
    }

    @Test
    fun `empty ranges return text unchanged`() {
        assertEquals("plain", markExcerpt("plain", emptyList()))
    }

    @Test
    fun `multilingual text marks by char index`() {
        assertEquals("«বিড়াল» বসে", markExcerpt("বিড়াল বসে", listOf(0..5)))
    }

    // SearchUiState

    private fun row(
        name: String,
        strong: Boolean,
    ) = ResultRow(name, "page", "ex", "p.1", strong, name)

    @Test
    fun `collapsed hides weak rows`() {
        val s = SearchUiState("q", listOf(row("a", true), row("b", false)))
        assertEquals(listOf("a"), s.visibleRows().map { it.fileName })
    }

    @Test
    fun `expanded shows weak rows`() {
        val s = SearchUiState("q", listOf(row("a", true), row("b", false)), expanded = true)
        assertEquals(2, s.visibleRows().size)
    }

    @Test
    fun `weakCount counts weak rows`() {
        val s = SearchUiState("q", listOf(row("a", true), row("b", false), row("c", false)))
        assertEquals(2, s.weakCount())
    }

    @Test
    fun `all-weak collapsed shows nothing but counts weak`() {
        val s = SearchUiState("q", listOf(row("a", false), row("b", false)))
        assertEquals(emptyList(), s.visibleRows())
        assertEquals(2, s.weakCount())
    }

    // formatRemaining

    @Test
    fun `zero means finishing`() {
        assertEquals("finishing…", formatRemaining(0L))
    }

    @Test
    fun `negative means finishing`() {
        assertEquals("finishing…", formatRemaining(-500L))
    }

    @Test
    fun `under a minute shows seconds`() {
        assertEquals("≈59 sec left", formatRemaining(59_000L))
    }

    @Test
    fun `under an hour shows minutes`() {
        assertEquals("≈12 min left", formatRemaining(12 * 60_000L))
    }

    @Test
    fun `hours show h and min`() {
        assertEquals("≈2 h 5 min left", formatRemaining((2 * 60 + 5) * 60_000L))
    }

    @Test
    fun `exactly a minute shows one min`() {
        assertEquals("≈1 min left", formatRemaining(60_000L))
    }

    @Test
    fun `exactly an hour shows one h zero min`() {
        assertEquals("≈1 h 0 min left", formatRemaining(3_600_000L))
    }

    // SettingsState

    @Test
    fun `empty folders is an error`() {
        val s = SettingsState(emptyList(), emptyList(), false, true, false, false)
        assertTrue(s.validate().any { "folder" in it.lowercase() })
    }

    @Test
    fun `blank-only folders is an error`() {
        val s = SettingsState(listOf(""), emptyList(), false, true, false, false)
        assertTrue(s.validate().any { "folder" in it.lowercase() })
    }

    @Test
    fun `bogus cloud provider is an error when cloud is on`() {
        val stt = SettingsState(listOf("Docs"), emptyList(), false, true, true, false)
        assertTrue(stt.validate(sttProvider = "bogus").isNotEmpty())
        val tts = SettingsState(listOf("Docs"), emptyList(), false, true, false, true)
        assertTrue(tts.validate(ttsProvider = "bogus").isNotEmpty())
    }

    @Test
    fun `cloud stt without provider is an error`() {
        val s = SettingsState(listOf("Docs"), emptyList(), false, true, true, false)
        assertTrue(s.validate(sttProvider = "none").isNotEmpty())
        assertTrue(s.validate(sttProvider = "google").isEmpty())
    }

    @Test
    fun `cloud tts without provider is an error`() {
        val s = SettingsState(listOf("Docs"), emptyList(), false, true, false, true)
        assertTrue(s.validate(ttsProvider = "none").isNotEmpty())
        assertTrue(s.validate(ttsProvider = "elevenlabs").isEmpty())
    }

    @Test
    fun `unknown excluded type is an error`() {
        val s = SettingsState(listOf("Docs"), listOf("apk"), false, true, false, false)
        assertTrue(s.validate().isNotEmpty())
    }

    @Test
    fun `excluded types normalise case and whitespace`() {
        val s = SettingsState(listOf("Docs"), listOf("PDF", " video "), false, true, false, false)
        assertEquals(emptyList(), s.validate())
    }

    @Test
    fun `all-good settings validate clean`() {
        val s = SettingsState(listOf("Docs"), listOf("video"), false, true, false, false)
        assertEquals(emptyList(), s.validate())
    }

    @Test
    fun `wipe summary names models index and transcripts`() {
        val s = SettingsState(listOf("Docs"), emptyList(), false, true, false, false)
        val w = s.wipeSummary.lowercase()
        assertTrue("model" in w)
        assertTrue("index" in w)
        assertTrue("transcript" in w)
        assertTrue("log" in w)
        assertTrue("key" in w)
    }
}
