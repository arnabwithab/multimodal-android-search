package com.filesearch.android

import android.content.Context
import android.util.Log
import com.filesearch.android.engine.EmbedBackend
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.InputData
import java.io.File

/**
 * F022 on-device embed path (arch §3).
 *
 * Reuses the bench-proven LiteRT wiring ([EmbeddingBench] patterns): one engine
 * at a time, backend order GPU -> XNNPACK CPU. NPU is skipped on purpose — the
 * F016 bench recorded it REJECTED (one engine serves all three encoders, so the
 * audio-executor failure fails init as a unit); GPU accepted text+image+audio.
 * The model file is the production download when present, else the
 * bench-staged copy; missing model throws with an honest message (status shows
 * it, nothing silently embeds zeros).
 */
class LitertEmbedBackend(
    private val app: Context,
    private val modelName: String = MODEL_NAME,
) : EmbedBackend {
    companion object {
        const val TAG = "LitertEmbed"
        const val MODEL_NAME = "embeddinggemma-2-740m.litertlm"

        /** Fingerprint constants for the index-now flow (any change forces full re-embed). */
        const val FP_MODEL = "embeddinggemma-2-740m"
        const val FP_REV = "local"
        const val FP_RUNTIME = "litert-gpu"
        const val FP_DTYPE = "fp16"
        const val FP_DIM = 256
        const val FP_BUDGETS = "pics-v1"
    }

    private val guard = Any()
    private var engine: EmbeddingEngine? = null

    private fun engine(): EmbeddingEngine =
        synchronized(guard) {
            engine ?: open().also { engine = it }
        }

    private fun open(): EmbeddingEngine {
        val model = stageModel()
        var lastError = "no backend tried"
        val backends: List<Pair<String, () -> Backend>> = listOf(
            "GPU" to { Backend.GPU() },
            "CPU" to { Backend.CPU() },
        )
        for ((name, make) in backends) {
            try {
                val e = EmbeddingEngine(
                    EmbeddingEngineConfig(
                        modelPath = model.absolutePath,
                        backend = make(),
                        visionBackend = make(),
                        audioBackend = make(),
                    ),
                )
                e.initialize()
                Log.i(TAG, "engine accepted on $name (${model.length()} bytes)")
                return e
            } catch (t: Throwable) {
                lastError = "$name: ${(t.message ?: t.javaClass.simpleName).take(160)}"
                Log.w(TAG, "backend $name rejected: $lastError")
            }
        }
        throw IllegalStateException("No LiteRT backend accepted $modelName (last: $lastError)")
    }

    /** Production download first, bench-staged copy fallback (copies it in). */
    private fun stageModel(): File {
        val dest = File(app.filesDir, modelName)
        if (dest.exists()) return dest
        val staged = File("/data/local/tmp/$modelName")
        if (staged.exists()) {
            // shortcut: no SHA/size verify on staged copy — production download path verifies, bench-staged copy is trusted.
            staged.copyTo(dest)
            return dest
        }
        throw IllegalStateException(
            "Model missing: ${dest.absolutePath} (download it first, or push via adb to /data/local/tmp/$modelName)",
        )
    }

    override fun embedTexts(texts: List<String>): List<FloatArray> =
        synchronized(guard) {
            texts.map { engine().computeEmbedding(listOf(InputData.Text(it))).embedding }
        }

    override fun embedImages(blobs: List<ByteArray>): List<FloatArray> =
        synchronized(guard) {
            blobs.map { engine().computeEmbedding(listOf(InputData.Image(it))).embedding }
        }

    override fun embedAudio(windows: List<ByteArray>): List<FloatArray> =
        synchronized(guard) {
            windows.map { engine().computeEmbedding(listOf(InputData.Audio(it))).embedding }
        }

    fun close() {
        synchronized(guard) {
            try {
                engine?.close()
            } catch (_: Throwable) {
            }
            engine = null
        }
    }
}
