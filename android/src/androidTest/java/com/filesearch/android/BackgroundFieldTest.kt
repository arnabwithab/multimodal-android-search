package com.filesearch.android

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F018 background field test (arch §6, design §4/§11). Runs on the real phone
 * against app-private fixtures only — never user folders; fixtures are deleted
 * after each test. Numbers land in logcat (tag BackgroundField) and in
 * filesDir/field-result.json for docs/bench/sm8635-field.md.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundFieldTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val exts = arrayOf("txt", "md", "jpg", "png", "pdf", "mp3", "mp4", "wav")

    /** Deterministic ~1KB fixtures, mixed extensions, sorted names. */
    private fun makeFixtures(dir: File, n: Int, stuckAt: Int = -1): List<File> {
        dir.mkdirs()
        return (0 until n).map { i ->
            val name = if (i == stuckAt) "field-%04d-stuck.mp4".format(i) else "field-%04d.%s".format(i, exts[i % exts.size])
            File(dir, name).apply {
                writeBytes(ByteArray(1024) { ((i * 31 + it) % 256).toByte() })
            }
        }
    }

    private fun runWorker(input: Data): Pair<ListenableWorker.Result, Data> {
        val worker = TestListenableWorkerBuilder<IndexWorker>(context).setInputData(input).build()
        val result = runBlocking { worker.doWork() }
        assertTrue("expected success, got $result", result is ListenableWorker.Result.Success)
        return result to (result as ListenableWorker.Result.Success).outputData
    }

    private fun input(
        dir: File,
        chargerOnly: Boolean = false,
        forcePause: String? = null,
        stuckTimeoutMs: Long = WATCHDOG_STUCK_MS,
        stuckName: String? = null,
    ): Data {
        val b = Data.Builder()
            .putString(IndexWorker.IN_DIR, dir.absolutePath)
            .putBoolean(IndexWorker.IN_CHARGER_ONLY, chargerOnly)
            .putLong(IndexWorker.IN_STUCK_TIMEOUT_MS, stuckTimeoutMs)
        if (forcePause != null) b.putString(IndexWorker.IN_FORCE_PAUSE, forcePause)
        if (stuckName != null) b.putString(IndexWorker.IN_STUCK_NAME, stuckName)
        return b.build()
    }

    @Test
    fun workerCompletes500FileCrawl() {
        val dir = File(context.filesDir, "field-fixtures")
        try {
            makeFixtures(dir, 500)
            val (_, out) = runWorker(input(dir))
            val done = out.getInt(IndexWorker.OUT_FILES_DONE, -1)
            val stopped = out.getInt(IndexWorker.OUT_FILES_STOPPED, -1)
            val wallMs = out.getLong(IndexWorker.OUT_WALL_MS, -1)
            val pause = out.getString(IndexWorker.OUT_PAUSE_REASON)
            val watchdog = out.getBoolean(IndexWorker.OUT_WATCHDOG, true)
            val perSec = out.getLong(IndexWorker.OUT_FILES_PER_SEC, -1)
            assertEquals(500, done)
            assertEquals(0, stopped)
            assertEquals(PauseReason.OK.name, pause)
            assertFalse(watchdog)
            assertTrue("files/sec must be measured, got $perSec", perSec > 0)
            assertEquals(0L, out.getLong(IndexWorker.OUT_NEXT_BACKOFF_MS, -1))

            val summary = JSONObject()
                .put("files", done)
                .put("wallMs", wallMs)
                .put("filesPerSec", perSec)
                .put("estimateMsColdStart", out.getLong(IndexWorker.OUT_ESTIMATE_MS, -1))
                .put("pauseReason", pause)
            File(context.filesDir, "field-result.json").writeText(summary.toString(2))
            Log.i("BackgroundField", "500-file crawl: $summary")
        } finally {
            dir.deleteRecursively()
        }
        assertFalse(dir.exists())
    }

    @Test
    fun pauseReasonPlumbing() {
        // Pure policy: priority thermal > battery > doze > charger-only.
        assertEquals(PauseReason.THERMAL_SEVERE, shouldPause(true, true, true, true, false))
        assertEquals(PauseReason.BATTERY_LOW, shouldPause(false, true, true, true, false))
        assertEquals(PauseReason.DOZE, shouldPause(false, false, true, true, false))
        assertEquals(PauseReason.CHARGER_ONLY, shouldPause(false, false, false, true, false))
        assertEquals(PauseReason.OK, shouldPause(false, false, false, true, true))
        assertEquals(PauseReason.OK, shouldPause(false, false, false, false, false))

        // Worker path without heating the phone: forced severe stops before file 1.
        val dir = File(context.filesDir, "field-fixtures-pause")
        try {
            makeFixtures(dir, 10)
            val (_, out) = runWorker(input(dir, forcePause = PauseReason.THERMAL_SEVERE.name))
            assertEquals(0, out.getInt(IndexWorker.OUT_FILES_DONE, -1))
            assertEquals(PauseReason.THERMAL_SEVERE.name, out.getString(IndexWorker.OUT_PAUSE_REASON))
            assertEquals(backoffMs(0), out.getLong(IndexWorker.OUT_NEXT_BACKOFF_MS, -1))
            Log.i("BackgroundField", "pause path ok: reason=${out.getString(IndexWorker.OUT_PAUSE_REASON)}")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun watchdogTripsOnStuckDecoder() {
        val dir = File(context.filesDir, "field-fixtures-stuck")
        try {
            makeFixtures(dir, 5, stuckAt = 2)
            val (_, out) = runWorker(input(dir, stuckTimeoutMs = 300L, stuckName = "stuck"))
            assertTrue(out.getBoolean(IndexWorker.OUT_WATCHDOG, false))
            assertEquals(1, out.getInt(IndexWorker.OUT_FILES_STOPPED, -1))
            // Partial progress kept: every non-stuck file still crawled.
            assertEquals(4, out.getInt(IndexWorker.OUT_FILES_DONE, -1))
            assertTrue(out.getLong(IndexWorker.OUT_WALL_MS, -1) >= 300L)
            Log.i("BackgroundField", "watchdog ok: stopped=1 done=4 wallMs=${out.getLong(IndexWorker.OUT_WALL_MS, -1)}")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun estimatesAndBackoffRecorded() {
        val costs = MeasuredCosts(mapOf("txt" to 2.0))
        assertEquals(200L, costs.estimateRemainingMs(mapOf("txt" to 100L)))
        costs.record("txt", 4L) // avg now 3.0
        assertEquals(300L, costs.estimateRemainingMs(mapOf("txt" to 100L)))
        assertEquals(300L, costs.estimateRemainingMs(mapOf("unknown-kind" to 100L))) // fallback avg
        assertEquals(0L, MeasuredCosts(emptyMap()).estimateRemainingMs(mapOf("txt" to 1L)))
        assertEquals(0L, costs.estimateRemainingMs(emptyMap()))

        assertEquals(30_000L, backoffMs(0))
        assertEquals(60_000L, backoffMs(1))
        assertEquals(120_000L, backoffMs(2))
        assertEquals(240_000L, backoffMs(3))
        assertEquals(1_800_000L, backoffMs(100)) // capped at 30min
    }

    @Test
    fun mediaStoreObserverDeliversEvents() {
        val seen = mutableListOf<Pair<Boolean, Uri?>>()
        val observer = MediaStoreObserver(null) { self, uri -> seen.add(self to uri) }
        observer.register(context.contentResolver)
        try {
            val uri = Uri.parse("content://media/external/file/1")
            observer.onChange(false, uri)
        } finally {
            observer.unregister(context.contentResolver)
        }
        assertEquals(1, seen.size)
        assertEquals(false, seen[0].first)
        assertEquals("content://media/external/file/1", seen[0].second.toString())
    }
}
