package com.filesearch.android.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrepareTest {
    @Test
    fun `photo plan uses 1024 longest side and 140 tokens`() {
        val plan = plan(4000, 3000, isScreenshot = false, format = "jpg")
        assertEquals(1024, plan.longestSide)
        assertEquals(false, plan.png)
        assertEquals(140, plan.visionTokens)
        assertNull(plan.skipReason)
    }

    @Test
    fun `screenshot plan uses 1536 PNG and 280 tokens`() {
        val plan = plan(1170, 2532, isScreenshot = true, format = "png")
        assertEquals(1536, plan.longestSide)
        assertEquals(true, plan.png)
        assertEquals(280, plan.visionTokens)
        assertNull(plan.skipReason)
    }

    @Test
    fun `icon is small in both dims`() {
        assertEquals("skipped:icon", plan(100, 100, false, "jpg").skipReason)
        assertEquals("skipped:icon", plan(255, 200, false, "jpg").skipReason)
        // Wide banner is real content, not an icon; 300x200 thumbnail keeps its vector.
        assertNull(plan(300, 200, false, "jpg").skipReason)
        assertNull(plan(1920, 100, false, "jpg").skipReason)
    }

    @Test
    fun `invalid dims get their own skip reason`() {
        assertEquals("skipped:invalid-dims", plan(0, 100, false, "jpg").skipReason)
        assertEquals("skipped:invalid-dims", plan(100, -1, false, "jpg").skipReason)
    }

    @Test
    fun `256px min-side is not an icon`() {
        assertNull(plan(256, 256, false, "jpg").skipReason)
    }

    @Test
    fun `RAW formats are skipped in v1`() {
        for (format in listOf("arw", "cr2", "cr3", "nef", "dng", "rw2", "orf", "ARW", ".Cr2", "image/x-adobe-dng")) {
            assertEquals("skipped:raw-v1", plan(4000, 3000, false, format).skipReason, format)
        }
    }

    @Test
    fun `HEIC is not skipped`() {
        assertNull(plan(4000, 3000, false, "heic").skipReason)
    }

    @Test
    fun `HEIC without a decoder is skipped with an explicit reason`() {
        // SM8635 (F017 RECORDED): MediaCodecList has a HEIC encoder only, no decoder.
        for (format in listOf("heic", "heif", "HEIC", ".Heic", "image/heic", "image/heif", "image/vnd.android.heic")) {
            assertEquals("skipped:no-heic-decoder", plan(4000, 3000, false, format, heicDecoderPresent = false).skipReason, format)
        }
        // Screenshots follow the same rule (1536px base kept, skip reason attached).
        assertEquals("skipped:no-heic-decoder", plan(1170, 2532, true, "heic", heicDecoderPresent = false).skipReason)
    }

    @Test
    fun `HEIC with a decoder keeps its vector`() {
        assertNull(plan(4000, 3000, false, "heic", heicDecoderPresent = true).skipReason)
        assertNull(plan(4000, 3000, false, "heif", heicDecoderPresent = true).skipReason)
    }

    @Test
    fun `missing decoder changes nothing for non-HEIC`() {
        assertNull(plan(4000, 3000, false, "jpg", heicDecoderPresent = false).skipReason)
        assertNull(plan(4000, 3000, false, "png", heicDecoderPresent = false).skipReason)
        assertEquals("skipped:raw-v1", plan(4000, 3000, false, "dng", heicDecoderPresent = false).skipReason)
    }

    @Test
    fun `isHeicFormat matches ext and MIME spellings`() {
        for (format in listOf("heic", "heif", "HEIC", ".heic", "IMG.HEIF", "image/heic", "image/heif", "image/vnd.android.heic")) {
            assertTrue(isHeicFormat(format), format)
        }
        for (format in listOf("jpg", "png", "avif", "webp", "dng", "image/jpeg")) {
            assertTrue(!isHeicFormat(format), format)
        }
    }

    @Test
    fun `screenshot detected by filename prefix`() {
        assertTrue(isScreenshot("/sdcard/DCIM/Screenshot_20240101-120000.png", null))
        assertTrue(isScreenshot("/sdcard/DCIM/screenshot_01.jpg", null))
    }

    @Test
    fun `screenshot detected by bucket`() {
        assertTrue(isScreenshot("/sdcard/DCIM/IMG_001.png", "Screenshots"))
        assertTrue(isScreenshot("/sdcard/DCIM/IMG_001.png", "screenshot"))
    }

    @Test
    fun `screenshot detected by path dir even without bucket`() {
        assertTrue(isScreenshot("/sdcard/Pictures/Screenshots/IMG_001.png", null))
    }

    @Test
    fun `normal photo is not a screenshot`() {
        assertFalse(isScreenshot("/sdcard/DCIM/IMG_123.jpg", "Camera"))
    }

    @Test
    fun `downscale preserves aspect ratio`() {
        assertEquals(1024 to 768, downscalePlan(4000, 3000, 1024))
        assertEquals(768 to 1024, downscalePlan(3000, 4000, 1024))
    }

    @Test
    fun `downscale never upscales`() {
        assertEquals(100 to 80, downscalePlan(100, 80, 1024))
    }

    @Test
    fun `OCR segment returns text unchanged, FTS-only`() {
        // FTS-only: modality=none, no vector (see OcrSegment KDoc). No script branching:
        // every language passes through byte-identical.
        for (text in listOf("hello world", "আমার ছবি", "صورة جميلة", "照片里的文字", "🎉 emoji mix 123")) {
            assertEquals(text, ocrSegment(text).text)
        }
    }
}
