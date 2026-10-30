package com.filesearch.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Field-test background crawl (F018, arch §6).
 *
 * Expedited [CoroutineWorker] that walks an app-private fixture directory and
 * per file consults the [shouldPause] guards (thermal via PowerManager, battery
 * via BatteryManager, Doze, charger-only) and the [watchdogTripped] stuck-job
 * rule. Pause stops the run with a reason (partial progress kept); a stuck file
 * is marked stopped and the crawl continues. Per-kind timings fold into
 * [MeasuredCosts] for the honest "≈N left" estimate.
 *
 * Each file is fully read (real I/O) as a stand-in for decode: full media
 * decode (BitmapFactory/Pdfium/Media3) is later device work; this wires the
 * guards, the worker, and the measurement harness.
 */
class IndexWorker(
    private val ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // Expedited work needs a foreground notification on API 31+. Best-effort:
        // under the instrumented test harness there is no SystemForegroundService.
        try {
            setForeground(getForegroundInfo())
        } catch (t: Throwable) {
            Log.w(TAG, "setForeground skipped: ${t.javaClass.simpleName}")
        }
        val dirPath = inputData.getString(IN_DIR) ?: return Result.failure()
        val chargerOnly = inputData.getBoolean(IN_CHARGER_ONLY, false)
        val forced = inputData.getString(IN_FORCE_PAUSE)?.let { runCatching { PauseReason.valueOf(it) }.getOrNull() }
        val pauseCheck = forced?.let { reason -> { reason } } ?: devicePauseCheck(ctx, chargerOnly)
        val stuckTimeoutMs = inputData.getLong(IN_STUCK_TIMEOUT_MS, WATCHDOG_STUCK_MS)
        val stuckName = inputData.getString(IN_STUCK_NAME)
        val result = crawlDirectory(
            dir = File(dirPath),
            costs = MeasuredCosts(emptyMap()),
            pauseCheck = pauseCheck,
            stuckTimeoutMs = stuckTimeoutMs,
            isStuck = { f -> stuckName != null && f.name.contains(stuckName) },
            isStopped = { isStopped },
        )
        Log.i(TAG, "crawl done=$result")
        return Result.success(
            Data.Builder()
                .putInt(OUT_FILES_DONE, result.filesDone)
                .putInt(OUT_FILES_STOPPED, result.filesStopped)
                .putLong(OUT_WALL_MS, result.wallMs)
                .putString(OUT_PAUSE_REASON, result.pauseReason.name)
                .putBoolean(OUT_WATCHDOG, result.watchdogTripped)
                .putLong(OUT_ESTIMATE_MS, result.estimateMs)
                .putLong(OUT_FILES_PER_SEC, result.filesPerSec)
                .putLong(OUT_NEXT_BACKOFF_MS, result.nextBackoffMs)
                .build(),
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Indexing", NotificationManager.IMPORTANCE_LOW))
        val note = Notification.Builder(ctx, CHANNEL)
            .setContentTitle("Indexing files")
            .setContentText("Background field crawl")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
        return ForegroundInfo(NOTIF_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val TAG = "IndexWorker"
        const val IN_DIR = "dirPath"
        const val IN_CHARGER_ONLY = "chargerOnly"

        /**
         * Test seam: PauseReason name that short-circuits the device guards so a
         * test can exercise the pause path without heating the phone.
         */
        const val IN_FORCE_PAUSE = "forcePause"

        /** Test seam: override [WATCHDOG_STUCK_MS] so the stuck path runs in ms. */
        const val IN_STUCK_TIMEOUT_MS = "stuckTimeoutMs"

        /** Test seam: filename substring treated as a stuck decoder. */
        const val IN_STUCK_NAME = "stuckName"

        const val OUT_FILES_DONE = "filesDone"
        const val OUT_FILES_STOPPED = "filesStopped"
        const val OUT_WALL_MS = "wallMs"
        const val OUT_PAUSE_REASON = "pauseReason"
        const val OUT_WATCHDOG = "watchdogTripped"
        const val OUT_ESTIMATE_MS = "estimateMs"
        const val OUT_FILES_PER_SEC = "filesPerSec"
        const val OUT_NEXT_BACKOFF_MS = "nextBackoffMs"

        private const val CHANNEL = "index"
        private const val NOTIF_ID = 1

        /** Real device signals -> [shouldPause]; charger-only respected via BatteryManager. */
        fun devicePauseCheck(context: Context, chargerOnly: Boolean): () -> PauseReason = {
            val pm = context.getSystemService(PowerManager::class.java)
            val bm = context.getSystemService(BatteryManager::class.java)
            val thermalSevere = (pm?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE
            val batteryLow = (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100) <= 15
            val dozing = pm?.isDeviceIdleMode == true
            val onCharger = bm?.isCharging == true
            shouldPause(thermalSevere, batteryLow, dozing, chargerOnly, onCharger)
        }

        /** Expedited first-pass request over [dir] (arch §6). */
        fun buildCrawlRequest(dir: File, chargerOnly: Boolean): androidx.work.OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<IndexWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(
                    Data.Builder()
                        .putString(IN_DIR, dir.absolutePath)
                        .putBoolean(IN_CHARGER_ONLY, chargerOnly)
                        .build(),
                )
                .addTag("field-crawl")
                .build()

        fun enqueueCrawl(context: Context, dir: File, chargerOnly: Boolean): UUID =
            buildCrawlRequest(dir, chargerOnly).also { WorkManager.getInstance(context).enqueue(it) }.id
    }
}

/** Outcome of one [crawlDirectory] run. Partial progress is always kept. */
data class CrawlResult(
    val filesDone: Int,
    val filesStopped: Int,
    val wallMs: Long,
    val pauseReason: PauseReason,
    val watchdogTripped: Boolean,
    val estimateMs: Long,
    val filesPerSec: Long,
    val nextBackoffMs: Long,
)

/**
 * Walk [dir] (sorted by name), reading each file fully, consulting [pauseCheck]
 * before every file and [watchdogTripped] while a stuck file makes no progress.
 *
 * WorkManager cancellation ([isStopped], e.g. the §4 pause button) breaks the
 * loop with [PauseReason.OK] — partial progress is kept, nothing rescheduled.
 *
 * @param stuckTimeoutMs override for [WATCHDOG_STUCK_MS] (tests use ~300ms).
 * @param isStuck marks a file as a stuck decoder (no progress until the watchdog fires).
 * @param isStopped WorkManager cancel signal, checked per file.
 */
suspend fun crawlDirectory(
    dir: File,
    costs: MeasuredCosts,
    pauseCheck: () -> PauseReason,
    stuckTimeoutMs: Long = WATCHDOG_STUCK_MS,
    isStuck: (File) -> Boolean = { false },
    isStopped: () -> Boolean = { false },
): CrawlResult {
    val t0 = SystemClock.elapsedRealtime()
    val files = dir.listFiles()?.sortedBy { it.name }?.filter { it.isFile } ?: emptyList()
    val kinds = files.groupingBy { it.extension.ifEmpty { "bin" } }.eachCount()
    val estimateMs = costs.estimateRemainingMs(kinds.mapValues { it.value.toLong() })
    var done = 0
    var stopped = 0
    var watchdog = false
    var pauseReason = PauseReason.OK
    for (f in files) {
        // Cancel (pause button) keeps partial progress: stop quietly, stay paused.
        if (isStopped()) break
        val reason = pauseCheck()
        if (reason != PauseReason.OK) {
            pauseReason = reason
            break
        }
        val start = SystemClock.elapsedRealtime()
        if (isStuck(f)) {
            // Simulated runaway decoder: no progress until the watchdog fires,
            // then mark stopped and continue (partial results kept).
            // shortcut: wall-clock only — real decode must feed per-chunk progress, upgrade when BitmapFactory/Pdfium/Media3 decode lands.
            while (!watchdogTripped(start, SystemClock.elapsedRealtime(), stuckTimeoutMs)) {
                currentCoroutineContext().ensureActive()
                delay(25)
            }
            stopped++
            watchdog = true
            Log.w(IndexWorker.TAG, "watchdog: stuck file marked stopped: ${f.name}")
            continue
        }
        f.inputStream().use { it.readBytes() }
        costs.record(f.extension.ifEmpty { "bin" }, SystemClock.elapsedRealtime() - start)
        done++
    }
    val wallMs = SystemClock.elapsedRealtime() - t0
    return CrawlResult(
        filesDone = done,
        filesStopped = stopped,
        wallMs = wallMs,
        pauseReason = pauseReason,
        watchdogTripped = watchdog,
        estimateMs = estimateMs,
        filesPerSec = done * 1000L / wallMs.coerceAtLeast(1L),
        nextBackoffMs = if (pauseReason == PauseReason.OK) 0L else backoffMs(0),
    )
}
