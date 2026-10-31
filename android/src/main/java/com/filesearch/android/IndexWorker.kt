package com.filesearch.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.filesearch.android.engine.Embedder
import com.filesearch.android.engine.Vectors
import com.filesearch.android.engine.isHeicFormat
import com.filesearch.android.engine.isScreenshot
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

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
        val mode = inputData.getString(IN_MODE) ?: MODE_CRAWL
        if (mode == MODE_INDEX_NOW) return indexNow()
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

    /**
     * F025 index-now: the full corpus — MediaStore Images across ALL buckets
     * (unbound) plus MediaStore PDF text (first 30 pages now, tail later) —
     * through the 740M pack into [DeviceIndexStore]. One image segment per
     * photo (vector = meaning, FTS name/body = filename words); TEXT pdf pages
     * become kind=page segments (loc = 0-based page).
     *
     * Honest costs: segment commits stream as each embed lands; progress
     * reports and watchdog-friendly yields fire per [FullCorpus.COMMIT_BATCH]
     * (50). Every run re-embeds the whole corpus — there is no skip-unchanged
     * resume (that is F023), so each run costs ~1 image infer per photo plus
     * ~1 text infer per TEXT pdf page; on battery, prefer charger-only. OCR
     * body text + SAF trees stay F023.
     */
    private suspend fun indexNow(): Result {
        val maxFiles = inputData.getInt(IN_MAX_FILES, Int.MAX_VALUE).coerceIn(1, Int.MAX_VALUE)
        val chargerOnly = inputData.getBoolean(IN_CHARGER_ONLY, false)
        val pauseCheck = devicePauseCheck(applicationContext, chargerOnly)
        val store = DeviceIndexStore(applicationContext)
        try {
            val backend = LitertEmbedBackend(applicationContext)
            try {
                val embedder = Embedder(backend, LitertEmbedBackend.FP_DIM)
                val fp =
                    embedder.fingerprint(
                        LitertEmbedBackend.FP_MODEL,
                        LitertEmbedBackend.FP_REV,
                        LitertEmbedBackend.FP_RUNTIME,
                        LitertEmbedBackend.FP_DTYPE,
                        LitertEmbedBackend.FP_BUDGETS,
                    )
                if (!store.checkFingerprint(fp)) Log.i(TAG, "fingerprint changed, full re-embed")
                // Fail fast on a missing model (else every photo fails with the same error).
                try {
                    embedder.embedQuery("warmup")
                } catch (t: Throwable) {
                    val msg = (t.message ?: t.javaClass.simpleName).take(200)
                    Log.w(TAG, "embed backend unavailable: $msg")
                    return Result.failure(Data.Builder().putString(OUT_ERROR, msg).build())
                }
                val pics =
                    try {
                        MediaPictures.list(applicationContext.contentResolver, maxFiles)
                    } catch (t: SecurityException) {
                        val msg = "no-media-permission (grant Photos access, then Index now): ${(t.message ?: "").take(120)}"
                        return Result.failure(Data.Builder().putString(OUT_ERROR, msg).build())
                    }
                // PDFs are best-effort under the grant (may be invisible on 33+): honest 0 either way.
                val pdfs =
                    try {
                        MediaPdfs.list(applicationContext.contentResolver, maxFiles)
                    } catch (t: SecurityException) {
                        Log.w(TAG, "pdf sweep skipped: ${t.javaClass.simpleName}")
                        emptyList()
                    }
                val buckets = runCatching { MediaPictures.listBuckets(applicationContext.contentResolver) }.getOrDefault(emptyList())
                // Count only: bucket names are the user's own album names, never logged.
                Log.i(TAG, "full-corpus buckets=${buckets.size} pics=${pics.size} pdfs=${pdfs.size}")
                var imgIndexed = 0
                var imgDone = 0
                var pdfSegs = 0
                var pdfDone = 0
                var pauseReason = PauseReason.OK
                suspend fun progress() {
                    setProgress(
                        Data.Builder()
                            .putInt(PROG_DONE, imgDone + pdfDone)
                            .putInt(PROG_TOTAL, pics.size + pdfs.size)
                            .putInt(PROG_IMAGE_DONE, imgDone)
                            .putInt(PROG_IMAGE_TOTAL, pics.size)
                            .putInt(PROG_PDF_DONE, pdfDone)
                            .putInt(PROG_PDF_TOTAL, pdfs.size)
                            .build(),
                    )
                }
                progress()
                for (range in FullCorpus.batchRanges(pics.size)) {
                    for (i in range) {
                        if (isStopped) break
                        val reason = pauseCheck()
                        if (reason != PauseReason.OK) {
                            pauseReason = reason
                            break
                        }
                        try {
                            if (indexOnePhoto(store, embedder, pics[i])) imgIndexed++
                        } catch (t: Throwable) {
                            val msg = (t.message ?: t.javaClass.simpleName).take(160)
                            Log.w(TAG, "index failed ${pics[i].name}: $msg")
                            store.upsertFile(pics[i].uri.toString(), "image", -1L, pics[i].dateModified, "failed", msg)
                        }
                        imgDone++
                    }
                    progress()
                    currentCoroutineContext().ensureActive()
                    yield()
                    if (pauseReason != PauseReason.OK || isStopped) break
                }
                if (pauseReason == PauseReason.OK && !isStopped) {
                    MediaPdfs.ensureInit() // one native init per run; per-file calls after this are no-ops
                    for (range in FullCorpus.batchRanges(pdfs.size)) {
                        for (i in range) {
                            if (isStopped) break
                            val reason = pauseCheck()
                            if (reason != PauseReason.OK) {
                                pauseReason = reason
                                break
                            }
                            try {
                                pdfSegs += indexOnePdf(store, embedder, pdfs[i])
                            } catch (t: Throwable) {
                                val msg = (t.message ?: t.javaClass.simpleName).take(160)
                                Log.w(TAG, "pdf index failed ${pdfs[i].name}: $msg")
                                store.upsertFile(pdfs[i].uri.toString(), "pdf", -1L, pdfs[i].dateModified, "failed", msg)
                            }
                            pdfDone++
                        }
                        progress()
                        currentCoroutineContext().ensureActive()
                        yield()
                        if (pauseReason != PauseReason.OK || isStopped) break
                    }
                }
                Log.i(TAG, "index-now done photos=$imgIndexed/${pics.size} pdfSegs=$pdfSegs/${pdfs.size} pause=$pauseReason")
                return Result.success(
                    Data.Builder()
                        .putInt(OUT_INDEXED, imgIndexed)
                        .putInt(OUT_TOTAL, pics.size)
                        .putInt(OUT_PDF_INDEXED, pdfSegs)
                        .putInt(OUT_PDF_TOTAL, pdfs.size)
                        .putInt(OUT_PDF_FILES_DONE, pdfDone)
                        .putString(OUT_PAUSE_REASON, pauseReason.name)
                        .build(),
                )
            } finally {
                backend.close()
            }
        } finally {
            store.close()
        }
    }

    /**
     * One photo -> one image segment. Returns false for skipped/failed rows
     * (no vector landed); the row is only written after a successful embed,
     * so a failed run keeps the last good segments instead of a bare row.
     */
    private fun indexOnePhoto(
        store: DeviceIndexStore,
        embedder: Embedder,
        p: PicRow,
    ): Boolean {
        val jpeg = sampledJpeg(p) ?: throw IllegalStateException("undecodable image bytes")
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        applicationContext.contentResolver.openInputStream(p.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val shot = isScreenshot(p.uri.toString(), p.bucket)
        val plan =
            com.filesearch.android.engine.plan(
                opts.outWidth,
                opts.outHeight,
                shot,
                p.name.substringAfterLast('.', ""),
                HeicSupport.hasHeicDecoder(),
            )
        if (plan.skipReason != null) {
            val id = store.upsertFile(p.uri.toString(), if (shot) "screenshot" else "image", jpeg.size.toLong(), p.dateModified, "failed", plan.skipReason)
            store.clearSegments(id)
            return false
        }
        // Embed first: the row is only touched once bytes landed, so a failed
        // embed keeps the last good segments for the retry (caller logs it).
        val vec = embedder.embedImage(jpeg)
        val id = store.upsertFile(p.uri.toString(), if (shot) "screenshot" else "image", jpeg.size.toLong(), p.dateModified, "done")
        store.clearSegments(id) // same row, fresh bytes, never stacked duplicates
        val kind = if (shot) "screenshot" else "image"
        // shortcut: single image segment per photo; filename doc-vector + OCR body text are F023 tuning.
        store.addSegment(id, kind, "image", p.name, fp16Bytes(vec), p.name, p.name, p.name)
        return true
    }

    /**
     * One PDF -> one kind=page segment per TEXT first-pass page (loc =
     * 0-based page, FTS body = page text). Render-only/locked/scanned pages
     * get the existing preview treatment (MediaPreview renders inline) and no
     * vector here. The row is only written after the first embed lands, so a
     * fully-failed run keeps the last good segments. Returns the TEXT segment
     * count.
     */
    private fun indexOnePdf(
        store: DeviceIndexStore,
        embedder: Embedder,
        row: PdfRow,
    ): Int {
        val texts = MediaPdfs.readFirstPassTexts(applicationContext, row.uri)
        if (texts.isEmpty()) {
            store.upsertFile(row.uri.toString(), "pdf", row.size, row.dateModified, "done", "no-text-layer")
            return 0
        }
        var n = 0
        var fileId: Long? = null
        for ((page, text) in texts.withIndex()) {
            val plan = FullCorpus.pdfPagePlan(page, text) ?: continue
            // Embed first, same as photos: the row is only touched once bytes
            // landed, so failed embeds keep the last good segments for retry.
            val vec = runCatching { embedder.embedDocs(listOf(row.name to plan.body)).single() }.getOrNull() ?: continue
            val fid = fileId ?: store.upsertFile(row.uri.toString(), "pdf", row.size, row.dateModified, "done").also { fileId = it; store.clearSegments(it) }
            store.addSegment(fid, "page", "text", plan.body.take(600), fp16Bytes(vec), row.name, plan.body, row.name, loc = page.toDouble())
            n++
        }
        if (n == 0) store.upsertFile(row.uri.toString(), "pdf", row.size, row.dateModified, "done", "render-only ${texts.size} pages")
        return n
    }

    /** Downsamples to a 1024-longest-side JPEG for the vision encoder (never upscales). */
    private fun sampledJpeg(p: PicRow): ByteArray? {
        val resolver = applicationContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(p.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (isHeicFormat(p.name) && !HeicSupport.hasHeicDecoder()) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(p.uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        try {
            val out = ByteArrayOutputStream()
            return if (bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)) out.toByteArray() else null
        } finally {
            bmp.recycle()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {        val nm = ctx.getSystemService(NotificationManager::class.java)
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

        /** "crawl" (F018 fixture walk, default) or "index_now" (F022 MediaStore photos). */
        const val IN_MODE = "mode"
        const val MODE_CRAWL = "crawl"
        const val MODE_INDEX_NOW = "index_now"

        /** Optional per-run bound (tests pass 2); production sends none — the corpus is unbound. */
        const val IN_MAX_FILES = "maxFiles"

        /** Unique work name the status sheet observes. */
        const val UNIQUE_WORK = "index-now"

        /** Live progress keys (setProgress) + terminal output keys. */
        const val PROG_DONE = "done"
        const val PROG_TOTAL = "total"

        /** Per-kind live progress for the image+pdf status rows. */
        const val PROG_IMAGE_DONE = "imageDone"
        const val PROG_IMAGE_TOTAL = "imageTotal"
        const val PROG_PDF_DONE = "pdfDone"
        const val PROG_PDF_TOTAL = "pdfTotal"
        const val OUT_INDEXED = "indexed"
        const val OUT_TOTAL = "total"
        const val OUT_PDF_INDEXED = "pdfIndexed"
        const val OUT_PDF_TOTAL = "pdfTotal"
        /** PDF files walked (units = files). OUT_PDF_INDEXED counts segments — never mix them. */
        const val OUT_PDF_FILES_DONE = "pdfFilesDone"
        const val OUT_ERROR = "error"

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
    }
}

/** fp16 L2-normed vector -> little-endian BLOB (engine Vectors fp16 helpers, storage only). */
private fun fp16Bytes(v: FloatArray): ByteArray {
    val buf = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (x in v) buf.putShort(Vectors.floatToFp16Bits(x))
    return buf.array()
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
