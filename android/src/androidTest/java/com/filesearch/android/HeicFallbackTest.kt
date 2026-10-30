package com.filesearch.android

import android.graphics.ImageDecoder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F021 — HEIC fallback on device (phone-only, NOT run on JVM).
 *
 * Verifies [HeicSupport.hasHeicDecoder] against decode reality using the staged
 * `f017/sample.heic` fixture (app-private cache, deleted after — no real user
 * files touched): the probe must agree with whether ImageDecoder actually
 * decodes the fixture, so the engine `skipped:no-heic-decoder` path fires only
 * where decode truly fails. Portable — asserts probe/decode consistency, not a
 * fixed present/absent value. Run via `:android:connectedAndroidTest` (make
 * test-device).
 */
@RunWith(AndroidJUnit4::class)
class HeicFallbackTest {
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val staged = mutableListOf<File>()

    @After
    fun cleanUp() {
        staged.forEach { it.delete() }
        staged.clear()
        HeicSupport.resetForTest()
    }

    @Test
    fun probeMatchesDecodeReality() {
        HeicSupport.resetForTest()
        val still = HeicSupport.hasDecoderFor(HeicSupport.HEIC_STILL_MIME)
        val hevc = HeicSupport.hasDecoderFor(HeicSupport.HEVC_VIDEO_MIME)
        val present = HeicSupport.hasHeicDecoder()
        assertEquals("OR-probe mismatch (still=$still hevc=$hevc)", still || hevc, present)
        // Cached probe must be stable across calls.
        assertEquals("probe not stable", present, HeicSupport.hasHeicDecoder())

        val out = File(appContext.cacheDir, "f021-sample.heic")
        InstrumentationRegistry.getInstrumentation().context.assets.open("f017/sample.heic").use { ins ->
            out.outputStream().use { ins.copyTo(it) }
        }
        staged += out
        val bytes = out.readBytes()
        var decoded: String? = null
        try {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
            decoded = "${bmp.width}x${bmp.height}"
        } catch (_: Throwable) {
            // decoded stays null — decode truly fails on this phone.
        }
        Log.i(TAG, "heic-fallback still=$still hevc=$hevc present=$present decoded=$decoded")
        assertEquals("probe=$present but ImageDecoder decoded=$decoded — probe is stale", present, decoded != null)
        if (present) {
            Log.i(TAG, "heic-fallback verdict=PASS decoderPresent=true bitmap=$decoded")
        } else {
            Log.i(TAG, "heic-fallback verdict=RECORDED-skip decoderPresent=false reason=skipped:no-heic-decoder")
        }
    }

    companion object {
        const val TAG = "F021"
    }
}
