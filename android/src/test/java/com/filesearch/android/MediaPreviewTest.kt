package com.filesearch.android

import com.filesearch.android.ui.PdfPreviewData
import com.filesearch.android.ui.pdfShowsText
import com.filesearch.android.ui.progressFraction
import com.filesearch.android.ui.sampleSizeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure preview helpers (MediaPreview.kt). Bitmap/Pdfium/
 * ExoPlayer loading is device-only and phone-gated; the >=200-char TEXT rule
 * is engine PdfPages (already tested) — here we pin the reuse, the sampling
 * math, and the row->URI wiring.
 */
class MediaPreviewTest {
    @Test
    fun `pdfShowsText reuses the 200-char TEXT rule`() {
        assertTrue(pdfShowsText("word ".repeat(60)))
        assertFalse(pdfShowsText("short scan"))
        assertFalse(pdfShowsText(""))
    }

    @Test
    fun `sampleSizeFor is powers of two near target`() {
        assertEquals(1, sampleSizeFor(100, 100, 256))
        assertEquals(4, sampleSizeFor(1024, 768, 256))
        assertEquals(8, sampleSizeFor(2048, 1536, 256))
        assertEquals(1, sampleSizeFor(0, 0, 256))
        assertEquals(1, sampleSizeFor(100, 100, 0))
    }

    @Test
    fun `progressFraction halves clamps and zeroes`() {
        assertEquals(0.5f, progressFraction(5000, 10000), 1e-6f)
        assertEquals(0f, progressFraction(0, 0), 1e-6f)
        assertEquals(1f, progressFraction(99999, 1000), 1e-6f)
        assertEquals(0f, progressFraction(-5, 1000), 1e-6f)
    }

    @Test
    fun `ranked rows carry the file URI for thumbs and previews`() {
        val vec = floatArrayOf(0f, 1f, 0f, 0f)
        val buf = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (x in vec) buf.putShort(com.filesearch.android.engine.Vectors.floatToFp16Bits(x))
        val seg =
            StoredSeg(
                id = 7,
                path = "content://media/external/images/media/7",
                kind = "image",
                modality = "image",
                excerpt = "sunset beach",
                vec = buf.array(),
                ftsName = "sunset-beach.jpg",
            )
        val out =
            DeviceSearch.rankAndMap(
                "sunset",
                floatArrayOf(1f, 0f, 0f, 0f),
                listOf(seg),
                mapOf(7L to 1.0),
                null,
            )
        assertEquals("content://media/external/images/media/7", out.strong.single().fileUri)
    }

    @Test
    fun `pdf preview data defaults to empty placeholder state`() {
        assertEquals(PdfPreviewData(), PdfPreviewData(null, null))
    }
}
