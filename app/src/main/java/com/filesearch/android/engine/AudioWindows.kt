package com.filesearch.android.engine

import kotlin.math.log10
import kotlin.math.sqrt

/** Pure audio-window planning. Device streams 16kHz mono s16 PCM via Media3 behind this plan. */
object AudioWindows {
    const val SAMPLE_RATE = 16000
    const val WINDOW_SEC = 30
    const val HOP_SEC = 25
    const val SILENCE_GATE_DB = -50.0
    const val TAIL_MIN_SEC = 2
    private const val S16_FULL_SCALE = 32768.0
    private const val DB_GAIN = 20

    data class AudioWindow(val index: Int, val startSec: Double, val endSec: Double) {
        val loc: Double get() = startSec
    }

    fun planWindows(
        durationSec: Double,
        rmsDbOfWindow: (index: Int) -> Double,
    ): List<AudioWindow> {
        val windows = mutableListOf<AudioWindow>()
        var index = 0
        var start = 0.0
        while (start + TAIL_MIN_SEC <= durationSec) {
            if (rmsDbOfWindow(index) >= SILENCE_GATE_DB) {
                windows += AudioWindow(index, start, minOf(start + WINDOW_SEC, durationSec))
            }
            index++
            start = index * HOP_SEC.toDouble()
        }
        return windows
    }

    /**
     * dBFS loudness of one window-sized streaming chunk.
     *
     * Call per window-sized chunk only — never pass whole-file PCM
     * (architecture §4: never hold full PCM in RAM; stream via Media3).
     */
    fun rmsDb(samples: ShortArray): Double {
        if (samples.isEmpty()) return Double.NEGATIVE_INFINITY
        var sum = 0.0
        for (s in samples) sum += (s / S16_FULL_SCALE) * (s / S16_FULL_SCALE)
        val rms = sqrt(sum / samples.size)
        return if (rms <= 0.0) Double.NEGATIVE_INFINITY else DB_GAIN * log10(rms)
    }
}
