package com.filesearch.android

import com.filesearch.android.engine.PageClass
import com.filesearch.android.engine.classifyPage
import com.filesearch.android.engine.twoPassPlan
import com.filesearch.android.ui.KindProgress

/** One indexable PDF text page: 0-based page + trimmed body. */
data class PdfSegPlan(val page: Int, val body: String)

/**
 * F025 full-corpus planning: pure, JVM-testable decisions for the
 * all-buckets + PDF-text sweep. Device I/O (MediaStore cursors, Pdfium,
 * LiteRT) lives in MediaPictures/MediaPdfs/IndexWorker; this file only
 * decides batches, buckets, rows, and pages — no framework calls.
 */
object FullCorpus {
    /** Commit batch: progress + watchdog-friendly yield every N items. */
    const val COMMIT_BATCH = 50

    /** Index ranges over [0, total) in commit batches (short tail stays whole). */
    fun batchRanges(
        total: Int,
        batchSize: Int = COMMIT_BATCH,
    ): List<IntRange> {
        if (total <= 0 || batchSize <= 0) return emptyList()
        return (0 until total step batchSize).map { start -> start until minOf(start + batchSize, total) }
    }

    /** Distinct non-blank bucket names, sorted — the "buckets covered" report. */
    fun distinctBuckets(buckets: List<String?>): List<String> =
        buckets.mapNotNull { it?.takeIf { b -> b.isNotBlank() } }.distinct().sorted()

    /** Per-kind status rows (image + pdf), honest counts. */
    fun corpusKindRows(
        imageDone: Long,
        imageTotal: Long,
        pdfDone: Long,
        pdfTotal: Long,
    ): List<KindProgress> =
        listOf(
            KindProgress(kind = "image", done = imageDone, total = imageTotal),
            KindProgress(kind = "pdf", done = pdfDone, total = pdfTotal),
        )

    /**
     * TEXT page -> indexable segment plan (loc = 0-based page); anything else
     * (render-only, locked, scanned, OCR-retry) -> null: the existing preview
     * treatment (MediaPreview inline render), no vector here.
     */
    fun pdfPagePlan(
        page: Int,
        text: String,
        isLocked: Boolean = false,
        isScanned: Boolean = false,
    ): PdfSegPlan? =
        if (classifyPage(text, isLocked, isScanned) == PageClass.TEXT) {
            PdfSegPlan(page = page, body = text.trim())
        } else {
            null
        }

    /** First-pass page range for a [pageCount]-page PDF (two-pass rule: first 30 now, tail later). */
    fun firstPassPages(pageCount: Int): IntRange = twoPassPlan(pageCount).firstPassPages
}
