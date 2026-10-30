package com.filesearch.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filesearch.android.engine.EmbedBackend
import com.filesearch.android.engine.Embedder
import com.filesearch.android.engine.Vectors
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F024 device proof: REAL [DeviceIndexStore] rows (bundled-SQLite FTS5) +
 * fake-but-shaped vectors through [DeviceSearch.search]. Embedding quality is
 * the F016 bench's job; this proves store -> decode -> fuse -> rows on device.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSearchDeviceTest {
    private lateinit var store: DeviceIndexStore

    /** Sunset/beach-ish texts -> e1, everything else -> e2 (300d; Embedder truncates). */
    private class FakeBackend : EmbedBackend {
        private fun vec(first: Boolean) = FloatArray(300) { i -> if ((i == 0) == first) 1f else 0f }

        override fun embedTexts(texts: List<String>): List<FloatArray> =
            texts.map { vec(it.contains("sunset", ignoreCase = true) || it.contains("beach", ignoreCase = true)) }

        override fun embedImages(blobs: List<ByteArray>): List<FloatArray> = blobs.map { vec(true) }

        override fun embedAudio(windows: List<ByteArray>): List<FloatArray> = windows.map { vec(false) }
    }

    private fun fp16(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buf.putShort(Vectors.floatToFp16Bits(x))
        return buf.array()
    }

    private fun seed() {
        val beach = store.upsertFile("content://media/external/images/media/1", "image", 10L, 1L, "done")
        store.addSegment(beach, "image", "image", "sunset beach", fp16(floatArrayOf(1f, 0f)), "sunset-beach.jpg", "sunset beach", "sunset-beach.jpg")
        val city = store.upsertFile("content://media/external/images/media/2", "image", 11L, 2L, "done")
        store.addSegment(city, "image", "image", "city night", fp16(floatArrayOf(0f, 1f)), "city-night.jpg", "city night", "city-night.jpg")
    }

    @Before
    fun create() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.deleteDatabase(DeviceIndexStore.DB_NAME)
        store = DeviceIndexStore(ctx)
    }

    @After
    fun close() {
        store.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(DeviceIndexStore.DB_NAME)
    }

    @Test
    fun readSegmentsRoundTrip() {
        seed()
        val segs = store.readSegments()
        assertEquals(2, segs.size)
        val beach = segs.single { it.ftsName == "sunset-beach.jpg" }
        assertEquals("content://media/external/images/media/1", beach.path)
        assertEquals("image", beach.kind)
        assertEquals("image", beach.modality)
        assertEquals("sunset beach", beach.excerpt)
        assertEquals(4, beach.vec!!.size)
    }

    @Test
    fun searchFindsFtsRowWithMarkedExcerpt() {
        seed()
        val res = DeviceSearch.search("sunset", store, Embedder(FakeBackend(), 2))
        assertEquals(1, res.strong.size)
        assertEquals(0, res.weakCount)
        val row = res.strong.single()
        assertEquals("sunset-beach.jpg", row.fileName)
        assertEquals("«sunset» beach", row.excerpt)
        val beachId = store.readSegments().single { it.ftsName == "sunset-beach.jpg" }.id
        assertEquals("image:$beachId", row.thumbKey)
    }

    @Test
    fun blankQueryIsEmpty() {
        seed()
        val res = DeviceSearch.search("   ", store, Embedder(FakeBackend(), 2))
        assertTrue(res.strong.isEmpty())
        assertTrue(res.weak.isEmpty())
    }

    @Test
    fun unknownTermIsHonestEmpty() {
        seed()
        val res = DeviceSearch.search("zzzqw", store, Embedder(FakeBackend(), 2))
        assertTrue(res.strong.isEmpty())
        assertTrue(res.weak.isEmpty())
    }
}
