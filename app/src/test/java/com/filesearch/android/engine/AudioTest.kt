package com.filesearch.android.engine

import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioTest {

    private val loud: (Int) -> Double = { 0.0 }

    @Test
    fun `90s loud file yields 4 windows with extended tail`() {
        val windows = AudioWindows.planWindows(90.0, loud)
        assertEquals(
            listOf(
                AudioWindows.AudioWindow(0, 0.0, 30.0),
                AudioWindows.AudioWindow(1, 25.0, 55.0),
                AudioWindows.AudioWindow(2, 50.0, 80.0),
                AudioWindows.AudioWindow(3, 75.0, 90.0),
            ),
            windows,
        )
    }

    @Test
    fun `31s file yields full window plus short tail`() {
        val windows = AudioWindows.planWindows(31.0, loud)
        assertEquals(
            listOf(
                AudioWindows.AudioWindow(0, 0.0, 30.0),
                AudioWindows.AudioWindow(1, 25.0, 31.0),
            ),
            windows,
        )
    }

    @Test
    fun `exactly 2s yields single window`() {
        assertEquals(
            listOf(AudioWindows.AudioWindow(0, 0.0, 2.0)),
            AudioWindows.planWindows(2.0, loud),
        )
    }

    @Test
    fun `file under 2s yields empty`() {
        assertEquals(emptyList(), AudioWindows.planWindows(1.5, loud))
    }

    @Test
    fun `silent middle window dropped but neighbors kept with original indices`() {
        val windows = AudioWindows.planWindows(90.0) { index -> if (index == 1) -60.0 else 0.0 }
        assertEquals(listOf(0, 2, 3), windows.map { it.index })
        assertEquals(0.0, windows[0].startSec)
        assertEquals(50.0, windows[1].startSec)
        assertEquals(75.0, windows[2].startSec)
    }

    @Test
    fun `all silent yields empty`() {
        assertEquals(emptyList(), AudioWindows.planWindows(90.0) { -60.0 })
    }

    @Test
    fun `rmsDb of full-scale sine is near minus 3dB`() {
        val samples = ShortArray(16000) { i ->
            (sin(2 * PI * 440 * i / 16000) * Short.MAX_VALUE).toInt().toShort()
        }
        assertEquals(-3.01, AudioWindows.rmsDb(samples), 0.05)
    }

    @Test
    fun `rmsDb of silence is negative infinity`() {
        assertEquals(Double.NEGATIVE_INFINITY, AudioWindows.rmsDb(ShortArray(1024)))
        assertEquals(Double.NEGATIVE_INFINITY, AudioWindows.rmsDb(ShortArray(0)))
    }

    @Test
    fun `loc mirrors startSec segment convention`() {
        val window = AudioWindows.AudioWindow(2, 50.0, 80.0)
        assertEquals(window.startSec, window.loc)
        assertTrue(window.endSec > window.startSec)
    }
}
