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
 * Smoke benchmark for the generic EmbeddingGemma 2 text pack.
 *
 * Tries backends in shipped-picker order (generic NPU -> GPU -> XNNPACK CPU),
 * records accept/reject per backend, then times ONE query embed on the winner.
 * Runs off the main thread; called from MainActivity only with `--ez bench true`.
 */
object EmbeddingBench {
    const val TAG = "EmbeddingBench"

    /** Single smoke query (same task prefix as engine Embedder.QUERY_PREFIX). */
    const val QUERY = "task: search result | query: sunset photo at the beach"

    const val MODEL_NAME = "embeddinggemma-2-text-270m.litertlm"
    const val TMP_PATH = "/data/local/tmp/$MODEL_NAME"
    const val RESULT_NAME = "bench-sm8635.json"

    fun run(context: Context): JSONObject {
        val modelFile = stageModel(context)
        val out = JSONObject()
        out.put("model", MODEL_NAME)
        out.put("modelPath", modelFile.absolutePath)
        out.put("modelBytes", modelFile.length())
        out.put("query", QUERY)

        val attempts = JSONArray()
        var engine: EmbeddingEngine? = null
        var usedBackend = "NONE"
        var initMs = -1L
        val backends: List<Pair<String, () -> Backend>> = listOf(
            "NPU" to { Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir) },
            "GPU" to { Backend.GPU() },
            "CPU" to { Backend.CPU() },
        )
        for ((name, make) in backends) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val e = EmbeddingEngine(EmbeddingEngineConfig(modelPath = modelFile.absolutePath, backend = make()))
                e.initialize()
                initMs = SystemClock.elapsedRealtime() - t0
                engine = e
                usedBackend = name
                attempts.put(attempt(name, true, initMs, "init ok"))
                Log.i(TAG, "backend $name ACCEPTED init=${initMs}ms")
                break
            } catch (t: Throwable) {
                val dt = SystemClock.elapsedRealtime() - t0
                val detail = (t.message ?: t.javaClass.simpleName).take(200)
                attempts.put(attempt(name, false, dt, detail))
                Log.w(TAG, "backend $name REJECTED in ${dt}ms: $detail")
            }
        }
        out.put("attempts", attempts)
        out.put("backend", usedBackend)
        out.put("initMs", initMs)

        val e = engine
        if (e != null) {
            try {
                val t0 = SystemClock.elapsedRealtime()
                val resp = e.computeEmbedding(listOf(InputData.Text(QUERY)))
                val inferMs = SystemClock.elapsedRealtime() - t0
                val vec = resp.embedding
                out.put("inferMs", inferMs)
                out.put("dim", vec.size)
                out.put(
                    "head5",
                    JSONArray((0 until min(5, vec.size)).map { vec[it].toDouble() }),
                )
                out.put("embedOk", true)
                Log.i(TAG, "embed ok dim=${vec.size} infer=${inferMs}ms")
            } catch (t: Throwable) {
                out.put("embedOk", false)
                out.put("embedError", (t.message ?: t.javaClass.simpleName).take(200))
                Log.e(TAG, "embed failed", t)
            } finally {
                try {
                    e.close()
                } catch (_: Throwable) {
                }
            }
        } else {
            out.put("embedOk", false)
            out.put("embedError", "no backend accepted the model")
        }

        out.put("rssKb", rssKb())
        out.put("nativeHeapKb", Debug.getNativeHeapAllocatedSize() / 1024)
        out.put("javaHeapKb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024)
        return out
    }

    private fun attempt(name: String, ok: Boolean, ms: Long, detail: String): JSONObject =
        JSONObject().put("backend", name).put("ok", ok).put("initMs", ms).put("detail", detail)

    /** Prefer app-private copy; fall back to the adb-pushed /data/local/tmp file (copies it in). */
    private fun stageModel(context: Context): File {
        val dest = File(context.filesDir, MODEL_NAME)
        if (!dest.exists()) {
            val tmp = File(TMP_PATH)
            if (tmp.exists()) tmp.copyTo(dest)
        }
        check(dest.exists()) { "model missing: ${dest.absolutePath} (push via adb first)" }
        return dest
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
