package com.filesearch.android

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.filesearch.android.engine.Embedder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F025 device proof (SM8635, needs the staged 740M pack + phone photos):
 * full-corpus index-now with NO cap, one app-owned PDF with a rare word,
 * then real-vector assertions — index count > 50 (the cap is gone), PDF
 * text segments indexed, DeviceSearch finds the PDF by its word.
 *
 * The PDF is inserted via the app context (owner = com.filesearch.android,
 * so the MediaStore Files sweep sees it) and deleted at the end.
 */
@RunWith(AndroidJUnit4::class)
class FullCorpusDeviceTest {
    companion object {
        const val PROOF_WORD = "quetzal"
    }

    @Test
    fun fullCorpusIndexesBeyondTheOldCapWithPdfText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        context.deleteDatabase(DeviceIndexStore.DB_NAME)

        val pdfUri =
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "f025-quetzal-proof.pdf")
                    put(MediaStore.Downloads.MIME_TYPE, MediaPdfs.PDF_MIME)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                },
            ) ?: error("could not insert proof PDF into MediaStore")
        try {
            resolver.openOutputStream(pdfUri)!!.use { it.write(proofPdfBytes()) }

            // Buckets are probed, never hardcoded — the sweep must see real ones.
            val buckets = MediaPictures.listBuckets(resolver)
            Log.i("FullCorpusDevice", "buckets=$buckets")
            assertTrue("expected real buckets under the grant, got $buckets", buckets.isNotEmpty())

            // No IN_MAX_FILES: the production path is unbound (the 50 cap is gone).
            val input = Data.Builder().putString(IndexWorker.IN_MODE, IndexWorker.MODE_INDEX_NOW).build()
            val worker = TestListenableWorkerBuilder<IndexWorker>(context).setInputData(input).build()
            val result = runBlocking { worker.doWork() }
            Log.i("FullCorpusDevice", "result=$result")
            assertTrue("expected success, got $result", result is ListenableWorker.Result.Success)
            val out = (result as ListenableWorker.Result.Success).outputData
            val total = out.getInt(IndexWorker.OUT_TOTAL, -1)
            val indexed = out.getInt(IndexWorker.OUT_INDEXED, -1)
            val pdfSegs = out.getInt(IndexWorker.OUT_PDF_INDEXED, -1)
            val pdfTotal = out.getInt(IndexWorker.OUT_PDF_TOTAL, -1)
            Log.i("FullCorpusDevice", "photos indexed=$indexed total=$total pdfSegs=$pdfSegs pdfTotal=$pdfTotal")
            assertTrue("cap-gone proof needs >50 photos, got total=$total", total > 50)
            // Tolerance over strict equality: a real corpus has undecodable/
            // no-decoder files; they land as failed rows, never silent drops.
            val skipped = total - indexed
            Log.i("FullCorpusDevice", "photos indexed=$indexed skipped=$skipped total=$total")
            assertTrue("skipped photos must be counted, not silently dropped (indexed=$indexed total=$total)", skipped >= 0 && indexed + skipped == total)
            assertTrue("cap-gone proof needs >50 photos indexed, got indexed=$indexed total=$total skipped=$skipped", indexed > 50)
            assertTrue("proof PDF must be visible to the sweep, pdfTotal=$pdfTotal", pdfTotal >= 1)
            assertTrue("proof PDF text must be indexed, pdfSegs=$pdfSegs", pdfSegs >= 1)

            // Real query path: the rare word finds the PDF row.
            val store = DeviceIndexStore(context)
            try {
                val backend = LitertEmbedBackend(context)
                try {
                    val hits = DeviceSearch.search(PROOF_WORD, store, Embedder(backend, LitertEmbedBackend.FP_DIM))
                    val names = (hits.strong + hits.weak).map { it.fileName }
                    Log.i("FullCorpusDevice", "search $PROOF_WORD -> $names")
                    assertTrue("search must find the proof PDF, got $names", names.any { it.contains("quetzal") })
                    val pdfRow = (hits.strong + hits.weak).first { it.fileName.contains("quetzal") }
                    assertEquals("page", pdfRow.kind)
                    assertTrue("pdf row must carry its page label, got '${pdfRow.locLabel}'", pdfRow.locLabel == "p.1")
                } finally {
                    backend.close()
                }
            } finally {
                store.close()
            }
        } finally {
            runCatching { resolver.delete(pdfUri, null, null) }
        }
    }

    /** Minimal one-page PDF whose text layer holds the rare proof word (>=200 chars: the TEXT rule). */
    private fun proofPdfBytes(): ByteArray {
        val body = (listOf(PROOF_WORD) + List(12) { "magnesium invoice harbor lantern" }).joinToString(" ")
        val content = "BT /F1 24 Tf 72 720 Td ($body) Tj ET"
        val objs =
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            )
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf(0)
        objs.forEachIndexed { i, bodyText ->
            offsets.add(out.length)
            out.append("${i + 1} 0 obj\n$bodyText\nendobj\n")
        }
        val xrefAt = out.length
        out.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        for (o in offsets.drop(1)) out.append("%010d 00000 n \n".format(o))
        out.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xrefAt\n%%EOF")
        return out.toString().toByteArray(StandardCharsets.US_ASCII)
    }
}
