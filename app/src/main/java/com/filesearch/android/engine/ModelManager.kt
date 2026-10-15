package com.filesearch.android.engine

import java.io.InputStream
import java.security.MessageDigest

data class ModelPack(val id: String, val bytes: Long)

object ModelPacks {
    private const val MB = 1024L * 1024L

    // Sizes are incremental deltas: 165MB base, +223MB vision, +97MB audio (totals 388/485).
    val TEXT_270M = ModelPack("text-270m", 165L * MB)
    val TEXT_VISION_440M = ModelPack("text-vision-440m", 223L * MB)
    val FULL_740M = ModelPack("full-740m", 97L * MB)
}

/**
 * Device-side model decisions (downloads stay in Play Asset Delivery packs).
 *
 * Reference-vector gate (cos >= 0.999 vs HF sentence-transformers output) runs on
 * device/Linux-Eval when the .litertlm backend lands (F013). Wi-Fi-only download and
 * user-deletable policy live in Settings (F011).
 */
object ModelManager {
    private val staged = listOf(ModelPacks.TEXT_270M, ModelPacks.TEXT_VISION_440M, ModelPacks.FULL_740M)

    /** Next staged pack to fetch, or null when satisfied (740M only when [wantAudio]). */
    fun nextNeeded(
        have: Set<String>,
        wantAudio: Boolean,
    ): ModelPack? {
        val want = if (wantAudio) staged else staged.take(2)
        return want.firstOrNull { it.id !in have }
    }

    /** Exact SoC -> generic NPU/TPU (Pixel Tensor) -> GPU -> CPU/XNNPACK; never throws. */
    fun pickArtifact(
        soc: String,
        artifacts: List<String>,
    ): String {
        if (artifacts.isEmpty()) return "cpu"
        if (soc.isNotBlank()) artifacts.firstOrNull { it.contains(soc, ignoreCase = true) }?.let { return it }
        artifacts.firstOrNull {
            it.contains("npu", ignoreCase = true) || it.contains("tpu", ignoreCase = true)
        }?.let { return it }
        artifacts.firstOrNull {
            it.contains("gpu", ignoreCase = true)
        }?.let { return it }
        artifacts.firstOrNull {
            it.contains("cpu", ignoreCase = true) || it.contains("xnnpack", ignoreCase = true)
        }?.let { return it }
        return artifacts.first()
    }

    /** SHA-256 of [bytes] equals [expectedHex] (constant-time compare). */
    fun shaOk(
        bytes: ByteArray,
        expectedHex: String,
    ): Boolean = shaOk(bytes.inputStream(), expectedHex)

    /** Streaming SHA-256 check — never holds the whole pack in RAM (constant-time compare). */
    fun shaOk(
        stream: InputStream,
        expectedHex: String,
    ): Boolean {
        val expected =
            try {
                hexToBytes(expectedHex.trim().lowercase())
            } catch (_: Exception) {
                return false
            }
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        var n: Int
        while (stream.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        return MessageDigest.isEqual(md.digest(), expected)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0 && hex.isNotEmpty())
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** IndexStore fingerprint `model@rev runtime dtype dim budgets`; null stored -> false. */
    fun fingerprintOk(
        stored: String?,
        current: String,
    ): Boolean = stored == current
}
