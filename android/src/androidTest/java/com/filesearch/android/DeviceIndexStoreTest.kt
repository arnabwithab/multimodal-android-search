package com.filesearch.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F022 device round-trip: the Android backend ([DeviceIndexStore]) must hold
 * the SAME schema as engine IndexStore ([com.filesearch.android.engine.IndexSchema])
 * and return an inserted row through FTS5 search.
 *
 * Vectors here are FAKE (deterministic bytes, not embeddings) — this test pins
 * storage + FTS parity only. Real-vector embedding is proven by the F016 bench
 * (GPU: text 58ms / image 932ms / audio 3525ms) and the manual index-now run.
 */
@RunWith(AndroidJUnit4::class)
class DeviceIndexStoreTest {
    private lateinit var store: DeviceIndexStore

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
    fun insertAndFtsRoundTrip() {
        val id = store.upsertFile("/pics/sunset-beach.jpg", "image", 1_024L, 1_700_000_000L)
        // FAKE vectors: schema/FTS parity only, never scored.
        val fakeVec = ByteArray(512) { (it % 251).toByte() }
        val segId = store.addSegment(id, "image", "image", "sunset beach", fakeVec, "sunset-beach.jpg", "sunset beach", "sunset-beach.jpg")
        assertTrue(segId > 0)

        val hits = store.matchTerms("sunset")
        assertEquals(listOf(segId), hits)
        assertEquals(1, store.countFiles())
        assertEquals(1, store.countSegments(id))
    }

    @Test
    fun fingerprintFirstStoreKeepsThenMismatchWipes() {
        val fp = "embeddinggemma-2-740m@local litert-gpu fp16 256 pics-v1"
        // Fresh index: stores without wiping, reports false (same as engine IndexStore).
        assertFalse(store.checkFingerprint(fp))
        store.upsertFile("/pics/a.jpg", "image", 10L, 1L)
        assertEquals(1, store.countFiles())
        // Same fingerprint: rows kept.
        assertTrue(store.checkFingerprint(fp))
        assertEquals(1, store.countFiles())
        // Changed fingerprint: full re-embed, rows wiped.
        assertFalse(store.checkFingerprint("other-model@rev litert-gpu fp16 256 pics-v1"))
        assertEquals(0, store.countFiles())
    }

    @Test
    fun missingTermMatchesNothing() {
        val id = store.upsertFile("/pics/a.jpg", "image", 10L, 1L)
        store.addSegment(id, "image", "image", "a photo", ByteArray(4), "a.jpg", "a photo", "a.jpg")
        // Plain token (callers pass sanitized FTS terms, as engine SearchText does — raw '-' is FTS5 syntax).
        assertTrue(store.matchTerms("zzzqw").isEmpty())
    }
}
