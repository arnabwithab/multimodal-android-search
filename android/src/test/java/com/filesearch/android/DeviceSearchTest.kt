package com.filesearch.android

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F024 JVM tests for the pure half of [DeviceSearch] (sanitize, fp16 decode,
 * rank-and-map over [StoredSeg] fakes). Store I/O + LiteRT embedding need the
 * framework SQLite / model, so they are proven by DeviceSearchDeviceTest on
 * the phone; fusion itself is already covered by engine SearcherTest.
 */
class DeviceSearchTest {
    private fun fp16(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buf.putShort(com.filesearch.android.engine.Vectors.floatToFp16Bits(x))
        return buf.array()
    }

    private fun seg(
        id: Long,
        excerpt: String,
        name: String,
        vec: FloatArray,
        kind: String = "image",
        modality: String = "image",
    ) = StoredSeg(
        id = id,
        path = "content://media/external/images/media/$id",
        kind = kind,
        modality = modality,
        excerpt = excerpt,
        vec = fp16(vec),
        ftsName = name,
    )

    // Two singleton modalities -> centred cosine is 0 -> meaning gated out,
    // so FTS coverage alone decides (mirrors SearcherTest's hand-computed case).
    private val aQueryVec = floatArrayOf(1f, 0f, 0f, 0f)

    @Test
    fun `sanitize drops FTS syntax but keeps every script`() {
        assertEquals(listOf("Sunset", "BEACH"), DeviceSearch.sanitizeTerms("Sunset BEACH!"))
        assertEquals(listOf("ab", "c", "d"), DeviceSearch.sanitizeTerms("a-b \"c\"* (d):^"))
        assertEquals(listOf("北京"), DeviceSearch.sanitizeTerms("北京"))
        assertTrue(DeviceSearch.sanitizeTerms("   ").isEmpty())
        assertTrue(DeviceSearch.sanitizeTerms("--- \"\"").isEmpty())
    }

    @Test
    fun `decodeVec round-trips fp16 and rejects dim mix`() {
        val v = floatArrayOf(1f, 0f, 0f, 0f)
        val back = DeviceSearch.decodeVec(fp16(v), 4)!!
        assertEquals(1f, back[0], 1e-3f)
        assertNull(DeviceSearch.decodeVec(ByteArray(6), 4))
        assertNull(DeviceSearch.decodeVec(ByteArray(0), 4))
    }

    @Test
    fun `empty in means empty out`() {
        val out = DeviceSearch.rankAndMap("sunset", aQueryVec, emptyList(), emptyMap(), null)
        assertTrue(out.strong.isEmpty())
        assertTrue(out.weak.isEmpty())
        assertEquals(0, out.weakCount)
    }

    @Test
    fun `FTS hit becomes a strong row with marked excerpt`() {
        val segs = listOf(
            seg(1, "sunset beach", "sunset-beach.jpg", floatArrayOf(0f, 1f, 0f, 0f)),
            seg(2, "city night", "city-night.jpg", floatArrayOf(0f, 0f, 1f, 0f), modality = "text"),
        )
        val out = DeviceSearch.rankAndMap("sunset", aQueryVec, segs, mapOf(1L to 1.0), null)
        assertEquals(1, out.strong.size)
        assertEquals(0, out.weakCount)
        val row = out.strong.single()
        assertEquals("sunset-beach.jpg", row.fileName)
        assertEquals("image", row.kind)
        assertEquals("«sunset» beach", row.excerpt)
        assertEquals("", row.locLabel)
        assertEquals("image:1", row.thumbKey)
    }

    @Test
    fun `no overlap anywhere is honest empty, not a weak fold`() {
        val segs = listOf(
            seg(1, "sunset beach", "sunset-beach.jpg", floatArrayOf(0f, 1f, 0f, 0f)),
            seg(2, "city night", "city-night.jpg", floatArrayOf(0f, 0f, 1f, 0f), modality = "text"),
        )
        val out = DeviceSearch.rankAndMap("zzzqw", aQueryVec, segs, emptyMap(), null)
        assertTrue(out.strong.isEmpty())
        assertTrue(out.weak.isEmpty())
    }

    @Test
    fun `zero-score rows never reach the weak fold`() {
        // One FTS hit + one total miss: the miss scores 0 and must be dropped,
        // not parked under "weaker matches".
        val segs = listOf(
            seg(1, "sunset beach", "sunset-beach.jpg", floatArrayOf(0f, 1f, 0f, 0f)),
            seg(2, "city night", "city-night.jpg", floatArrayOf(0f, 0f, 1f, 0f), modality = "text"),
        )
        val out = DeviceSearch.rankAndMap("sunset", aQueryVec, segs, mapOf(1L to 1.0), null)
        assertEquals(listOf(1L to "sunset-beach.jpg"), out.strong.map { 1L to it.fileName })
        assertTrue(out.weak.isEmpty())
    }

    @Test
    fun `kindHint plumbing boosts the matching kind`() {
        val segs = listOf(
            seg(1, "morning run", "run.jpg", floatArrayOf(0f, 1f, 0f, 0f), kind = "image"),
            seg(2, "morning run", "run.mp4", floatArrayOf(0f, 0f, 1f, 0f), kind = "video", modality = "video"),
        )
        val out = DeviceSearch.rankAndMap("morning run", aQueryVec, segs, emptyMap(), "video")
        assertEquals(listOf("run.mp4"), out.strong.map { it.fileName })
    }

    @Test
    fun `excerpt falls back to filename when empty`() {
        val segs = listOf(
            seg(1, "", "sunset-beach.jpg", floatArrayOf(1f, 0f, 0f, 0f), modality = "text"),
            seg(2, "other", "other.jpg", floatArrayOf(0f, 1f, 0f, 0f), modality = "image"),
        )
        val out = DeviceSearch.rankAndMap("sunset", aQueryVec, segs, mapOf(1L to 1.0, 2L to 0.0), null)
        assertEquals("«sunset»-beach.jpg", out.strong.single().excerpt)
    }

    @Test
    fun `dim-mismatched blob is skipped never crashing`() {
        val good = seg(1, "sunset beach", "sunset-beach.jpg", floatArrayOf(0f, 1f, 0f, 0f))
        val bad = StoredSeg(2, "content://media/2", "image", "image", "sunset pier", ByteArray(6), "pier.jpg")
        val out = DeviceSearch.rankAndMap("sunset", aQueryVec, listOf(good, bad), mapOf(1L to 1.0, 2L to 1.0), null)
        assertEquals(listOf("sunset-beach.jpg"), (out.strong + out.weak).map { it.fileName })
    }
}
