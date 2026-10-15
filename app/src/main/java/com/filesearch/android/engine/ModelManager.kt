package com.filesearch.android.engine

import java.io.InputStream
import java.security.MessageDigest

data class ModelPack(val id: String, val bytes: Long)

object ModelPacks {
    private const val MB = 1024L * 1024L
    private const val TEXT_PACK_MB = 165L
    private const val VISION_DELTA_MB = 223L
    private const val AUDIO_DELTA_MB = 97L

    // Sizes are incremental deltas: 165MB base, +223MB vision, +97MB audio (totals 388/485).
    val TEXT_270M = ModelPack("text-270m", TEXT_PACK_MB * MB)
    val TEXT_VISION_440M = ModelPack("text-vision-440m", VISION_DELTA_MB * MB)
    val FULL_740M = ModelPack("full-740m", AUDIO_DELTA_MB * MB)
}

/**
 * Device-side model decisions (downloads stay in Play Asset Delivery packs).
 *
 * Reference-vector gate (cos >= 0.999 vs HF sentence-transformers output) runs on
 * device/Linux-Eval when the .litertlm backend lands (F013). Wi-Fi-only download and
 * user-deletable policy live in Settings (F011).
 */
object ModelManager {
    private const val IO_BUFFER_SIZE = 8192
    private const val HEX_RADIX = 16

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
        val socHit = soc.takeIf { it.isNotBlank() }?.let { s -> artifacts.firstOrNull { it.contains(s, ignoreCase = true) } }
        val npuHit = artifacts.firstOrNull { it.contains("npu", ignoreCase = true) || it.contains("tpu", ignoreCase = true) }
        val gpuHit = artifacts.firstOrNull { it.contains("gpu", ignoreCase = true) }
        val cpuHit = artifacts.firstOrNull { it.contains("cpu", ignoreCase = true) || it.contains("xnnpack", ignoreCase = true) }
        return socHit ?: npuHit ?: gpuHit ?: cpuHit ?: artifacts.first()
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
            } catch (_: IllegalArgumentException) {
                // Malformed expected hex (odd length, empty, or non-hex digits) can never match: fail closed.
                // NumberFormatException needs no separate catch: it already extends IllegalArgumentException.
                return false
            }
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(IO_BUFFER_SIZE)
        var n: Int
        while (stream.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        return MessageDigest.isEqual(md.digest(), expected)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0 && hex.isNotEmpty())
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(HEX_RADIX).toByte() }
    }

    /** IndexStore fingerprint `model@rev runtime dtype dim budgets`; null stored -> false. */
    fun fingerprintOk(
        stored: String?,
        current: String,
    ): Boolean = stored == current
}
