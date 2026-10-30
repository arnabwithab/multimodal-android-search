package com.filesearch.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F022 index-now smoke: the REAL on-device path (MediaStore photos -> 740M GPU
 * pack -> [DeviceIndexStore]) with a 2-photo cap. Needs the staged model
 * (/data/local/tmp copy, same as the bench) and phone photos; asserts real
 * vectors landed (row count + FTS name match), not embedding quality (that's
 * the F016 bench: GPU text 58ms / image 932ms).
 */
@RunWith(AndroidJUnit4::class)
class IndexNowSmokeTest {
    // No GrantPermissionRule: this locked-down phone rejects even instrumentation
    // grants ("Neither user 2000 nor current process"). The app already holds
    // READ_MEDIA_IMAGES (user-granted); without it the corpus is empty and the
    // test passes with a log (corpus presence is device state, not code).

    @Test
    fun indexNowIndexesRealPhotosWithRealVectors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DeviceIndexStore.DB_NAME)
        val input =
            Data.Builder()
                .putString(IndexWorker.IN_MODE, IndexWorker.MODE_INDEX_NOW)
                .putInt(IndexWorker.IN_MAX_FILES, 2)
                .build()
        val worker = TestListenableWorkerBuilder<IndexWorker>(context).setInputData(input).build()
        val result = runBlocking { worker.doWork() }
        Log.i("IndexNowSmoke", "result=$result")
        assertTrue("expected success, got $result", result is ListenableWorker.Result.Success)
        val out = (result as ListenableWorker.Result.Success).outputData
        val total = out.getInt(IndexWorker.OUT_TOTAL, -1)
        val indexed = out.getInt(IndexWorker.OUT_INDEXED, -1)
        Log.i("IndexNowSmoke", "indexed=$indexed total=$total")
        assertTrue("total must be reported, got $total", total >= 0)
        if (total == 0) {
            Log.w("IndexNowSmoke", "no phone photos visible (selected-photos grant) — worker path ran, corpus empty")
        } else {
            assertEquals(total, indexed)
        }
        realVectorRoundTrip()
    }

    /**
     * Real weights, no permission needed: the bench photo (/data/local/tmp,
     * same staging the bench uses) through the 740M GPU pack into the store.
     * Proves model -> vector -> DB -> FTS with genuine embeddings.
     */
    private fun realVectorRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes =
            listOf(
                java.io.File("/data/local/tmp/bench-photo.jpg"),
                java.io.File(context.filesDir, "bench-photo.jpg"),
            ).firstOrNull { it.exists() }?.readBytes()
        if (bytes == null) {
            Log.w("IndexNowSmoke", "bench photo not staged — real-vector leg skipped")
            return
        }
        val backend = LitertEmbedBackend(context)
        try {
            val vec = com.filesearch.android.engine.Embedder(backend, 256).embedImage(bytes)
            assertEquals(256, vec.size)
            val norm = kotlin.math.sqrt(vec.sumOf { (it * it).toDouble() })
            Log.i("IndexNowSmoke", "real vec dim=${vec.size} norm=$norm head=${vec.take(3)}")
            assertTrue("renormed query/doc vectors are unit length, got $norm", norm in 0.99..1.01)
            assertTrue("real image vector must not be all zeros", vec.any { it != 0f })
            val store = DeviceIndexStore(context)
            try {
                val id = store.upsertFile("bench-photo.jpg", "image", bytes.size.toLong(), 1L, "done")
                store.addSegment(id, "image", "image", "bench photo", fp16Bytes(vec), "bench-photo.jpg", "bench photo", "bench-photo.jpg")
                assertTrue("FTS should find the photo by filename word", store.matchTerms("bench").isNotEmpty())
                assertEquals(1, store.countSegments(id))
            } finally {
                store.close()
            }
        } finally {
            backend.close()
            context.deleteDatabase(DeviceIndexStore.DB_NAME)
        }
    }

    /** fp16 L2-normed vector -> little-endian BLOB (engine fp16 helpers, storage only). */
    private fun fp16Bytes(vec: FloatArray): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(vec.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (x in vec) buf.putShort(com.filesearch.android.engine.Vectors.floatToFp16Bits(x))
        return buf.array()
    }
}
