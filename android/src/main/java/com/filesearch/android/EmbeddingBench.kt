package com.filesearch.android

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.InputData
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/**
 * Full-modality benchmark for the generic EmbeddingGemma 2 740M pack.
 *
 * Tries backends in shipped-picker order (generic NPU -> GPU -> XNNPACK CPU)
 * *per modality*: one engine per backend, still-pending modalities (text, image,
 * audio) attempted on each engine in turn; engines are closed before moving to
 * the next backend so at most one 740M engine is resident at a time.
 * Photo = real DCIM pull staged via /data/local/tmp; audio = 30s 16kHz mono
 * WAV fixture (scripts/make-audio-fixture.py) standing in for one AudioWindows
 * window — SDK takes `.wav` file bytes for InputData.Audio.
 * Runs off the main thread; called from MainActivity only with `--ez bench true`.
 */
object EmbeddingBench {
    const val TAG = "EmbeddingBench"

    /** Same smoke query as the 270M run (task prefix kept for comparability). */
    const val QUERY = "task: search result | query: sunset photo at the beach"

    const val MODEL_NAME = "embeddinggemma-2-740m.litertlm"
    const val PHOTO_NAME = "bench-photo.jpg"
    const val AUDIO_NAME = "bench-audio.wav"
    const val RESULT_NAME = "bench-sm8635-full.json"

    private fun tmp(name: String) = "/data/local/tmp/$name"

    fun run(context: Context): JSONObject {
        val modelFile = stage(context, MODEL_NAME)
        val photoBytes = stageBytes(context, PHOTO_NAME)
        val audioBytes = stageBytes(context, AUDIO_NAME)
        val out = JSONObject()
        out.put("model", MODEL_NAME)
        out.put("modelPath", modelFile.absolutePath)
        out.put("modelBytes", modelFile.length())
        out.put("query", QUERY)
        out.put("photoBytes", photoBytes?.size ?: -1)
        out.put("audioBytes", audioBytes?.size ?: -1)

        val attempts = JSONArray()
        val results = JSONObject()
        val pending = mutableMapOf<String, InputData>(
            "text" to InputData.Text(QUERY),
        )
        if (photoBytes != null) pending["image"] = InputData.Image(photoBytes)
        if (audioBytes != null) pending["audio"] = InputData.Audio(audioBytes)
        if (photoBytes == null) results.put("image", skipped("no photo staged"))
        if (audioBytes == null) results.put("audio", skipped("no audio staged"))

        val backends: List<Pair<String, (Context) -> Backend>> = listOf(
            "NPU" to { ctx -> Backend.NPU(nativeLibraryDir = ctx.applicationInfo.nativeLibraryDir) },
            "GPU" to { Backend.GPU() },
            "CPU" to { Backend.CPU() },
        )
        for ((name, make) in backends) {
            if (pending.isEmpty()) break
            var engine: EmbeddingEngine? = null
            val t0 = SystemClock.elapsedRealtime()
            try {
                engine = EmbeddingEngine(
                    EmbeddingEngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = make(context),
                        visionBackend = make(context),
                        audioBackend = make(context),
                    ),
                )
                engine.initialize()
                val initMs = SystemClock.elapsedRealtime() - t0
                attempts.put(attempt(name, true, initMs, "init ok"))
                Log.i(TAG, "backend $name ACCEPTED init=${initMs}ms")
                val done = mutableListOf<String>()
                for ((modality, input) in pending) {
                    try {
                        val t1 = SystemClock.elapsedRealtime()
                        val vec = engine.computeEmbedding(listOf(input)).embedding
                        val inferMs = SystemClock.elapsedRealtime() - t1
                        results.put(modality, okResult(name, initMs, inferMs, vec.size, vec))
                        Log.i(TAG, "$modality on $name ok dim=${vec.size} infer=${inferMs}ms")
                        done.add(modality)
                    } catch (t: Throwable) {
                        val detail = (t.message ?: t.javaClass.simpleName).take(200)
                        Log.w(TAG, "$modality on $name FAILED: $detail")
                        results.put(
                            "$modality-$name-error",
                            JSONObject().put("backend", name).put("error", detail),
                        )
                    }
                }
                done.forEach { pending.remove(it) }
            } catch (t: Throwable) {
                val dt = SystemClock.elapsedRealtime() - t0
                val detail = (t.message ?: t.javaClass.simpleName).take(200)
                attempts.put(attempt(name, false, dt, detail))
                Log.w(TAG, "backend $name REJECTED in ${dt}ms: $detail")
            } finally {
                try {
                    engine?.close()
                } catch (_: Throwable) {
                }
            }
        }
        for (modality in pending.keys) {
            results.put(modality, JSONObject().put("ok", false).put("error", "no backend accepted it"))
        }
        out.put("attempts", attempts)
        out.put("modalities", results)

        out.put("rssKb", rssKb())
        out.put("nativeHeapKb", Debug.getNativeHeapAllocatedSize() / 1024)
        out.put("javaHeapKb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024)
        return out
    }

    private fun attempt(name: String, ok: Boolean, ms: Long, detail: String): JSONObject =
        JSONObject().put("backend", name).put("ok", ok).put("initMs", ms).put("detail", detail)

    private fun skipped(reason: String): JSONObject =
        JSONObject().put("ok", false).put("error", reason)

    private fun okResult(backend: String, initMs: Long, inferMs: Long, dim: Int, vec: FloatArray): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("backend", backend)
            .put("initMs", initMs)
            .put("inferMs", inferMs)
            .put("dim", dim)
            .put("head5", JSONArray((0 until min(5, vec.size)).map { vec[it].toDouble() }))

    /** Prefer app-private copy; fall back to the adb-pushed /data/local/tmp file (copies it in). */
    private fun stage(context: Context, name: String): File {
        val dest = File(context.filesDir, name)
        if (!dest.exists()) {
            val tmp = File(tmp(name))
            if (tmp.exists()) tmp.copyTo(dest)
        }
        check(dest.exists()) { "model missing: ${dest.absolutePath} (push via adb first)" }
        return dest
    }

    private fun stageBytes(context: Context, name: String): ByteArray? {
        return try {
            stage(context, name).readBytes()
        } catch (t: Throwable) {
            Log.w(TAG, "fixture $name missing: ${t.message}")
            null
        }
    }

    private fun rssKb(): Long {
        return try {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }
                    ?.split(Regex("\\s+"))?.getOrNull(1)?.toLong() ?: -1L
            }
        } catch (_: Throwable) {
            -1L
        }
    }
}
