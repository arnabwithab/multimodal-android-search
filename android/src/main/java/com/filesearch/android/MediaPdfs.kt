package com.filesearch.android

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** One PDF: content URI (the stable id), display name, modified time, size. */
data class PdfRow(
    val uri: Uri,
    val name: String,
    val dateModified: Long,
    val size: Long,
)

/**
 * F025 PDF enumeration: MediaStore Files with mime application/pdf (read-only),
 * newest first, unbound. What the grant makes visible is what gets indexed —
 * the worker reports honest counts either way. Page text comes from the axet
 * Pdfium text layer (same calls as the MediaPreview first-page loader); the
 * engine [com.filesearch.android.engine.classifyPage] decides TEXT vs
 * render-only per page.
 */
object MediaPdfs {
    const val PDF_MIME = "application/pdf"

    // Pdfium native init, once per process: cheap after the first call, and
    // idempotent so per-file callers stay safe without the worker's pre-call.
    private val initDone = java.util.concurrent.atomic.AtomicBoolean(false)

    /** One native init per run; later calls are a single atomic check. */
    fun ensureInit() {
        if (initDone.compareAndSet(false, true)) {
            runCatching { com.github.axet.pdfium.Pdfium.FPDF_InitLibrary() }
        }
    }

    fun list(
        resolver: ContentResolver,
        limit: Int = Int.MAX_VALUE,
    ): List<PdfRow> {
        val cols =
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.SIZE,
            )
        resolver.query(
            MediaStore.Files.getContentUri("external"),
            cols,
            "${MediaStore.Files.FileColumns.MIME_TYPE}=?",
            arrayOf(PDF_MIME),
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            return buildList {
                while (c.moveToNext() && size < limit) {
                    val id = c.getLong(idCol)
                    add(
                        PdfRow(
                            uri = Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), id.toString()),
                            name = c.getString(nameCol).orEmpty(),
                            dateModified = c.getLong(dateCol),
                            size = c.getLong(sizeCol),
                        ),
                    )
                }
            }
        }
        return emptyList()
    }

    /**
     * Page texts for the first-pass pages only (two-pass rule: first 30 now,
     * tail later). One entry per page; "" when the page has no text layer
     * (worker classifies those RENDER_ONLY). Never throws — failure yields
     * emptyList and the worker records the file accordingly.
     */
    fun readFirstPassTexts(
        ctx: Context,
        uri: Uri,
    ): List<String> {
        return try {
            ensureInit()
            val pdfium = com.github.axet.pdfium.Pdfium()
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
            pfd.use {
                pdfium.open(it.fileDescriptor)
                try {
                    val count = pdfium.pagesCount.coerceAtLeast(0)
                    if (count == 0) return emptyList()
                    FullCorpus.firstPassPages(count).map { i ->
                        runCatching {
                            val page = pdfium.openPage(i)
                            try {
                                val t = page.open()
                                try {
                                    t.getText(0, t.count)
                                } finally {
                                    t.close()
                                }
                            } finally {
                                page.close()
                            }
                        }.getOrDefault("")
                    }
                } finally {
                    pdfium.close()
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
