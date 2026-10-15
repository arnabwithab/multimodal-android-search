package com.filesearch.android.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class VoiceTest {

    @Test
    fun `voice toggles are off on install`() {
        val config = VoiceConfig()
        assertFalse(config.cloudStt)
        assertFalse(config.cloudTts)
        assertEquals("none", config.sttProvider)
        assertEquals("none", config.ttsProvider)
    }

    @Test
    fun `heard label shows transcript plus language chip`() {
        assertEquals("heard: hello [en]", heardLabel("hello", "en"))
    }

    @Test
    fun `transcript row is flagged as api origin`() {
        val row = transcriptRow("hello world", 7L)
        assertEquals(7L, row.fileId)
        assertEquals("hello world", row.text)
        assertEquals("transcript=api", row.origin)
    }

    @Test
    fun `purge keeps only api rows and dedupes ids`() {
        val rows = listOf(
            TranscriptRow(fileId = 1L, text = "a"),
            TranscriptRow(fileId = 1L, text = "a dup"),
            TranscriptRow(fileId = 2L, text = "b", origin = "transcript=local"),
            TranscriptRow(fileId = 3L, text = "c"),
        )
        assertEquals(listOf(1L, 3L), purgeOnToggleOff(rows))
    }

    @Test
    fun `effective providers are none while toggles are off`() {
        val config = VoiceConfig(cloudStt = false, cloudTts = false, sttProvider = "google", ttsProvider = "elevenlabs")
        assertEquals("none", config.effectiveSttProvider)
        assertEquals("none", config.effectiveTtsProvider)
    }

    @Test
    fun `effective providers pass through while toggles are on`() {
        val config = VoiceConfig(cloudStt = true, cloudTts = true, sttProvider = "google", ttsProvider = "elevenlabs")
        assertEquals("google", config.effectiveSttProvider)
        assertEquals("elevenlabs", config.effectiveTtsProvider)
    }

    @Test
    fun `cost meter rejects negative millis`() {
        assertFailsWith<IllegalArgumentException> { CostMeter().add(-1L) }
    }

    @Test
    fun `purge of empty store is empty`() {
        assertEquals(emptyList(), purgeOnToggleOff(emptyList()))
    }

    @Test
    fun `cost meter buckets per minute`() {
        assertEquals(0L, CostMeter(millisUsed = 0L).minuteBucket())
        assertEquals(1L, CostMeter(millisUsed = 61_000L).minuteBucket())
    }

    @Test
    fun `cost meter add accumulates`() {
        val meter = CostMeter()
        meter.add(30_000L)
        meter.add(31_000L)
        assertEquals(61_000L, meter.millisUsed)
        assertEquals(1L, meter.minuteBucket())
    }

    @Test
    fun `offline fallback keeps the typed query`() {
        assertEquals("zebra in video", queryFallback(cloudFailed = true, typed = "zebra in video"))
        assertEquals("zebra in video", queryFallback(cloudFailed = false, typed = "zebra in video"))
    }
}
