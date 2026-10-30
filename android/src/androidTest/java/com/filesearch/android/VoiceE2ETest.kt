package com.filesearch.android

import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F019 voice E2E on device (Realme GT 6).
 * Mic-live recognition is NOT asserted (needs a human voice) — see
 * docs/bench/sm8635-voice.md for the manual mic checklist.
 * Privacy: no audio recorded, no keys logged.
 */
@RunWith(AndroidJUnit4::class)
class VoiceE2ETest {
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun toggleDefaultsOff() {
        val toggles = VoiceService.defaultToggles()
        assertFalse("cloud STT must be OFF on install", toggles.cloudStt)
        assertFalse("cloud TTS must be OFF on install", toggles.cloudTts)
    }

    @Test
    fun keyStorageRoundTrip() {
        val voice = VoiceService(appContext)
        voice.clearKeys()
        assertNull(voice.loadSttKey())
        assertNull(voice.loadTtsKey())
        voice.saveSttKey("stt-test-key")
        voice.saveTtsKey("tts-test-key")
        assertEquals("stt-test-key", voice.loadSttKey())
        assertEquals("tts-test-key", voice.loadTtsKey())
        voice.clearKeys()
        assertNull(voice.loadSttKey())
        assertNull(voice.loadTtsKey())
    }

    @Test
    fun cloudWithoutKeyErrorsCleanly() {
        val voice = VoiceService(appContext)
        voice.clearKeys()
        try {
            voice.requireCloudSttKey()
            fail("expected MissingVoiceKeyException for STT")
        } catch (e: MissingVoiceKeyException) {
            assertTrue(
                "error must name the prefs key, got: ${e.message}",
                e.message!!.contains(VoiceService.KEY_STT_API_KEY),
            )
        }
        try {
            voice.requireCloudTtsKey()
            fail("expected MissingVoiceKeyException for TTS")
        } catch (e: MissingVoiceKeyException) {
            assertTrue(
                "error must name the prefs key, got: ${e.message}",
                e.message!!.contains(VoiceService.KEY_TTS_API_KEY),
            )
        }
    }

    @Test
    fun fallbackReturnsTypedQuery() {
        // Airplane-agnostic: typed query always works (arch §7). When this run
        // happens with airplane mode on (adb toggle), the same assertion holds.
        val airplaneOn = try {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (_: Throwable) {
            false
        }
        val typed = "zebra in a video"
        assertEquals(typed, VoiceService(appContext).offlineFallback(typed))
        // Log for record: fallback holds regardless of airplane state.
        Log.i("VoiceE2ETest", "fallback ok, airplaneOn=$airplaneOn")
    }

    @Test
    fun ttsSpeakToDoneCallbackFires() {
        val voice = VoiceService(appContext)
        val done = CountDownLatch(1)
        voice.speak("voice test") { done.countDown() }
        try {
            assertTrue("TTS speak-to-done callback never fired", done.await(20, TimeUnit.SECONDS))
        } finally {
            voice.shutdownTts()
        }
    }
}
