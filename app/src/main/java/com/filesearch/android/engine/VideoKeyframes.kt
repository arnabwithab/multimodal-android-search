package com.filesearch.android.engine

/**
 * Pure video-keyframe planning. Frame extraction stays device-side
 * (MediaMetadataRetriever @1fps); this object only hashes and selects.
 *
 * Soundtrack goes through [AudioWindows.planWindows] (F005).
 * MTS without index needs a 12s seek-back fallback on device.
 */
object VideoKeyframes {
    const val SAME_SHOT_BITS = 4
    const val MAX_FRAMES = 1200
    private const val AHASH_BYTES = 64
    private const val BYTE_MASK = 0xFF
    private const val THINNING_SECONDS = 3

    data class VideoFrame(val index: Int, val startSec: Double, val endSec: Double)

    /** 8x8 mean-threshold hash over 64 gray bytes (unsigned). */
    fun ahash(gray64: ByteArray): Long {
        require(gray64.size == AHASH_BYTES) { "ahash needs exactly $AHASH_BYTES gray bytes, got ${gray64.size}" }
        var sum = 0L
        for (b in gray64) sum += (b.toInt() and BYTE_MASK)
        val mean = sum.toDouble() / AHASH_BYTES
        var hash = 0L
        for (i in gray64.indices) {
            if ((gray64[i].toInt() and BYTE_MASK) > mean) hash = hash or (1L shl i)
        }
        return hash
    }

    fun hamming(
        a: Long,
        b: Long,
    ): Int = (a xor b).countOneBits()

    /**
     * 1fps input hashes: keep first frame, then any frame with
     * hamming distance > [SAME_SHOT_BITS] vs last kept.
     * Post-dedup thinning keeps ~1 frame per 3s so fast-cut video
     * cannot fill the [MAX_FRAMES] cap early and drop the file tail.
     */
    fun selectFrames(
        hashes: List<Long>,
        durationSec: Double = hashes.size.toDouble(),
    ): List<Int> {
        if (hashes.isEmpty()) return emptyList()
        val kept = mutableListOf(0)
        var last = hashes[0]
        for (i in 1 until hashes.size) {
            if (kept.size >= MAX_FRAMES) break
            if (hamming(last, hashes[i]) > SAME_SHOT_BITS) {
                kept += i
                last = hashes[i]
            }
        }
        val budget = minOf(MAX_FRAMES, maxOf(1, (durationSec / THINNING_SECONDS).toInt()))
        return if (kept.size <= budget) kept else List(budget) { kept[(it * kept.size.toDouble() / budget).toInt()] }
    }

    /**
     * Maps kept 1fps indices to spans: each frame runs until the next kept frame.
     * [keptIndices] must be sorted unique (as [selectFrames] returns);
     * the last span is clamped so endSec never precedes startSec.
     */
    fun planSpans(
        keptIndices: List<Int>,
        durationSec: Double,
        fps: Int = 1,
    ): List<VideoFrame> {
        require(fps > 0) { "fps must be positive, got $fps" }
        if (keptIndices.isEmpty()) return emptyList()
        for (i in 1 until keptIndices.size) {
            require(keptIndices[i] > keptIndices[i - 1]) { "keptIndices must be sorted unique" }
        }
        return keptIndices.mapIndexed { k, frameIndex ->
            val start = frameIndex.toDouble() / fps
            val rawEnd = if (k + 1 < keptIndices.size) keptIndices[k + 1].toDouble() / fps else durationSec
            VideoFrame(frameIndex, start, maxOf(rawEnd, start))
        }
    }
}
