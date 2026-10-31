package com.filesearch.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.wav.WavExtractor
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.axet.pdfium.Pdfium
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.internal.CommonComponentRegistrar
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.common.internal.VisionCommonRegistrar
import com.google.mlkit.vision.text.internal.TextRegistrar
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F017 — extraction parity on device (Realme GT 6, SM8635).
 *
 * Proves the DEVICE side matches the pure-JVM planning logic in
 * engine/{PdfPages,ImagePrepare,AudioWindows,VideoKeyframes}:
 *  (a) Pdfium text layer vs the >=200-char TEXT rule (+1400px render rule),
 *  (b) ImageDecoder HEIC decode smoke,
 *  (c) ML Kit OCR Latin PASS / Bengali gap RECORDED (fallback = image decode),
 *  (d) Media3 decode of a 30s fixture -> PCM RMS vs the -50dB gate math,
 *  (e) MediaMetadataRetriever @1fps frame count (+ MTS 12s seek-back note).
 *
 * Fixtures ship as androidTest assets, staged to app-private cache and deleted
 * after each probe — real user files are never touched. Every probe logs one
 * `F017 <name> {...}` line; transcribe into docs/bench/sm8635-extraction.md.
 */
@RunWith(AndroidJUnit4::class)
class ExtractionParityTest {
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val staged = mutableListOf<File>()

    // Design rules mirrored from engine/ (no engine dep on device; see class doc).
    private val minTextChars = 200 // PdfPages.MIN_TEXT_CHARS
    private val renderLongestPx = 1400 // PdfPages.RENDER_LONGEST_PX
    private val silenceGateDb = -50.0 // AudioWindows.SILENCE_GATE_DB
    private val sampleRate = 16000 // AudioWindows.SAMPLE_RATE

    @After
    fun cleanUp() {
        staged.forEach { it.delete() }
        staged.clear()
    }

    @Before
    fun initMlKit() {
        // App init (FileSearchApplication) covers production runs via manifest
        // discovery. Defensive: if the runner uses a different Application (or
        // discovery hasn't run), register the bundled-Latin components
        // explicitly — without them getClient NPEs. initializeIfNeeded: first
        // test registers, rest reuse.
        MlKitContext.initializeIfNeeded(
            appContext,
            listOf(CommonComponentRegistrar(), VisionCommonRegistrar(), TextRegistrar()),
        )
    }

    private fun stage(asset: String): File {
        val out = File(appContext.cacheDir, "f017-$asset".replace('/', '-'))
        InstrumentationRegistry.getInstrumentation().context.assets.open("f017/$asset").use { ins ->
            out.outputStream().use { ins.copyTo(it) }
        }
        staged += out
        return out
    }

    private fun report(name: String, verdict: String, details: String) {
        Log.i(TAG, "$name verdict=$verdict $details")
    }

    @Test
    fun pdfTextMeets200CharRule_andRenderParity() {
        val pdf = stage("fixture.pdf")
        Pdfium.FPDF_InitLibrary()
        val pdfium = Pdfium()
        var verdict = "FAIL"
        var detail = ""
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            pdfium.open(pfd.fileDescriptor)
            try {
                assertEquals("fixture must be 1 page", 1, pdfium.pagesCount)
                val page = pdfium.openPage(0)
                try {
                    val text = page.open()
                    try {
                        val count = text.count
                        assertTrue("text layer empty", count > 0)
                        val extracted = text.getText(0, count)
                        val len = extracted.trim().length
                        // >=200 chars -> PageClass.TEXT per PdfPages.classifyPage.
                        assertTrue("TEXT rule needs >=$minTextChars chars, got $len", len >= minTextChars)
                        detail = "pages=1 chars=$len"
                        verdict = "PASS"
                    } finally {
                        text.close()
                    }
                    // Render parity: longest side 1400px PNG to cache (design §4).
                    val size = pdfium.getPageSize(0)
                    val scale = renderLongestPx.toFloat() / maxOf(size.width, size.height)
                    val w = maxOf(1, (size.width * scale).toInt())
                    val h = maxOf(1, (size.height * scale).toInt())
                    assertTrue("render longest side >$renderLongestPx", maxOf(w, h) <= renderLongestPx)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    page.render(bmp, 0, 0, w, h)
                    assertTrue("rendered bitmap empty", bmp.width > 0 && bmp.height > 0)
                    detail += " render=${w}x$h"
                } finally {
                    page.close()
                }
            } finally {
                pdfium.close()
            }
        }
        report("pdf", verdict, detail)
    }

    @Test
    fun heicDecodeSmoke() {
        val heic = MediaFormat()
        heic.setString(MediaFormat.KEY_MIME, "image/vnd.android.heic")
        val decoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(heic)
        if (decoder == null) {
            // No HEIC codec on this SoC: skip, don't fail (task spec).
            report("heic", "RECORDED-skip", "reason=no-heic-decoder")
            return
        }
        val bytes = stage("sample.heic").readBytes()
        val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        val bmp = ImageDecoder.decodeBitmap(src)
        assertTrue("HEIC decoded to empty bitmap", bmp.width > 0 && bmp.height > 0)
        report("heic", "PASS", "decoder=$decoder bitmap=${bmp.width}x$bmp.height")
    }

    @Test
    fun ocrLatinPasses_bengaliGapRecorded() {
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val latinBmp = BitmapFactory.decodeFile(stage("latin.png").absolutePath)
            assertNotNull("latin fixture did not decode (fallback path broken)", latinBmp)
            val latin = Tasks.await(client.process(InputImage.fromBitmap(latinBmp, 0)), 60, TimeUnit.SECONDS)
            assertTrue(
                "Latin OCR missed fixture text, got: ${latin.text.take(120)}",
                latin.text.contains("brown fox", ignoreCase = true),
            )
            report("ocr-latin", "PASS", "chars=${latin.text.length}")

            // Bengali: ML Kit v2 ships latin/chinese/devanagari/japanese/korean —
            // no Bengali model, so only the gap is RECORDED here. The asserted
            // property is the design fallback: the image still decodes, so the
            // image-vector path survives regardless of OCR quality.
            val bengaliBmp = BitmapFactory.decodeFile(stage("bengali.png").absolutePath)
            assertNotNull("bengali fixture did not decode (image-vector fallback broken)", bengaliBmp)
            val bengali = Tasks.await(
                client.process(InputImage.fromBitmap(bengaliBmp, 0)),
                60,
                TimeUnit.SECONDS,
            )
            report(
                "ocr-bengali",
                "RECORDED-gap",
                "model=none(latin-engine) outChars=${bengali.text.length} " +
                    "out=${bengali.text.take(80)} fallback=image-decode-ok",
            )
        } finally {
            client.close()
        }
    }

    @Test
    @UnstableApi
    fun media3PcmRmsMatchesGateMath() {
        val wav = stage("tone30s.wav")
        val pcm = decodeWavPcm(wav)
        val samples = pcm.size / 2
        assertEquals("30s @16kHz mono", 30 * sampleRate, samples)
        var sum = 0.0
        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN) // WAV s16 is LE; default order is BE
        repeat(samples) {
            val s = buf.short / 32768.0
            sum += s * s
        }
        val rms = sqrt(sum / samples)
        val db = 20 * log10(rms)
        // Fixture is a 0.25-amplitude 440Hz sine: 20*log10(0.25/sqrt(2)) ≈ -15.05 dBFS.
        assertTrue("RMS $db dBFS far from fixture level -15.05", abs(db - (-15.05)) < 1.0)
        // Same gate as AudioWindows.planWindows: this window would be KEPT, not silenced.
        assertTrue("tone window wrongly gated (rms=$db <= $silenceGateDb)", db >= silenceGateDb)
        report("audio-rms", "PASS", "samples=$samples rmsDb=${"%.2f".format(db)} gate=$silenceGateDb kept=true")

        // Full Media3 decode path: Transformer transcodes the fixture to AAC/MP4.
        val outMp4 = File(appContext.cacheDir, "f017-tone.mp4")
        staged += outMp4
        val done = CountDownLatch(1)
        var error: Exception? = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Transformer is a main-thread API (verifyApplicationThread); the
        // instrumentation thread only blocks on the completion latch.
        val transformerHolder = arrayOfNulls<Transformer>(1)
        instrumentation.runOnMainSync {
            transformerHolder[0] = Transformer.Builder(appContext)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            done.countDown()
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException,
                        ) {
                            error = exception
                            done.countDown()
                        }
                    },
                ).build()
            transformerHolder[0]!!.start(MediaItem.fromUri(Uri.fromFile(wav)), outMp4.absolutePath)
        }
        assertTrue("transformer timed out", done.await(180, TimeUnit.SECONDS))
        assertNull("transformer failed: $error", error)
        report("audio-transformer", "PASS", "wav->mp4 bytes=${outMp4.length()}")
    }

    @Test
    fun mmrFrameCountAt1Fps() {
        val clip = stage("clip30s.mp4")
        val mmr = MediaMetadataRetriever()
        var verdict = "FAIL"
        var detail = ""
        try {
            mmr.setDataSource(clip.absolutePath)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: -1
            var frames = 0
            var t = 0L
            while (t < durMs * 1000L) {
                if (mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST) != null) frames++
                t += 1_000_000L
            }
            assertTrue("expected ~30 frames @1fps over 30s, got $frames", frames in 28..30)
            verdict = "PASS"
            detail = "durationMs=$durMs frames=$frames"
        } finally {
            mmr.release()
        }
        // No MTS fixture exists: the 12s seek-back fallback (VideoKeyframes doc)
        // stays untested on device — recorded, not failed.
        report("video-mmr", verdict, "$detail mts-seekback=RECORDED-untested(no-mts-fixture)")
    }

    @UnstableApi
    private fun decodeWavPcm(file: File): ByteArray {
        val dataSource = DefaultDataSource.Factory(appContext).createDataSource()
        val length = dataSource.open(DataSpec(Uri.fromFile(file)))
        val input = DefaultExtractorInput(dataSource, 0, length)
        val pcm = ByteArrayOutputStream()
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
                override fun format(format: Format) = Unit

                override fun sampleData(input: DataReader, len: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                    val chunk = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val r = input.read(chunk, read, len - read)
                        if (r == C.RESULT_END_OF_INPUT) break
                        read += r
                    }
                    pcm.write(chunk, 0, read)
                    return read
                }

                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = Unit

                override fun sampleMetadata(
                    timeUs: Long,
                    flags: Int,
                    size: Int,
                    offset: Int,
                    cryptoData: TrackOutput.CryptoData?,
                ) = Unit
            }

            override fun endTracks() = Unit

            override fun seekMap(seekMap: SeekMap) = Unit
        }
        val extractor: Extractor = WavExtractor()
        extractor.init(output)
        val position = PositionHolder()
        while (extractor.read(input, position) != Extractor.RESULT_END_OF_INPUT) {
            // Drain all samples; position-holder seeks (non-seekable WAV: none expected).
        }
        dataSource.close()
        return pcm.toByteArray()
    }

    companion object {
        const val TAG = "F017"
    }
}
