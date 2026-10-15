package com.digup.android.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerTest {

    @Test
    fun `all guards true resolves to thermal first`() {
        assertEquals(
            PauseReason.THERMAL_SEVERE,
            shouldPause(
                thermalSevere = true,
                batteryLow = true,
                dozing = true,
                chargerOnly = true,
                onCharger = false,
            ),
        )
    }

    @Test
    fun `battery beats doze and charger-only`() {
        assertEquals(
            PauseReason.BATTERY_LOW,
            shouldPause(
                thermalSevere = false,
                batteryLow = true,
                dozing = true,
                chargerOnly = true,
                onCharger = false,
            ),
        )
    }

    @Test
    fun `only dozing pauses for doze`() {
        assertEquals(
            PauseReason.DOZE,
            shouldPause(
                thermalSevere = false,
                batteryLow = false,
                dozing = true,
                chargerOnly = false,
                onCharger = true,
            ),
        )
    }

    @Test
    fun `charger-only pauses only when off charger`() {
        assertEquals(
            PauseReason.CHARGER_ONLY,
            shouldPause(false, false, false, chargerOnly = true, onCharger = false),
        )
        assertEquals(
            PauseReason.OK,
            shouldPause(false, false, false, chargerOnly = true, onCharger = true),
        )
    }

    @Test
    fun `all clear keeps going`() {
        assertEquals(
            PauseReason.OK,
            shouldPause(false, false, false, chargerOnly = false, onCharger = false),
        )
    }

    @Test
    fun `backoff doubles from 30s and caps at 30min`() {
        assertEquals(30_000L, backoffMs(0))
        assertEquals(60_000L, backoffMs(1))
        assertEquals(120_000L, backoffMs(2))
        assertEquals(960_000L, backoffMs(5))
        assertEquals(1_800_000L, backoffMs(6))
        assertEquals(1_800_000L, backoffMs(7))
        assertEquals(1_800_000L, backoffMs(100))
    }

    @Test
    fun `watchdog trips only past five minutes`() {
        assertFalse(watchdogTripped(lastProgressMs = 0L, nowMs = WATCHDOG_STUCK_MS.toLong()))
        assertTrue(watchdogTripped(lastProgressMs = 0L, nowMs = WATCHDOG_STUCK_MS.toLong() + 1))
        assertFalse(watchdogTripped(lastProgressMs = 0L, nowMs = 60_000L))
    }

    @Test
    fun `estimator sums known kind rates`() {
        val costs = MeasuredCosts(mapOf("image" to 100.0, "chunk" to 200.0))
        assertEquals(700L, costs.estimateRemainingMs(mapOf("image" to 3L, "chunk" to 2L)))
    }

    @Test
    fun `estimator falls back to average for unknown kinds`() {
        val costs = MeasuredCosts(mapOf("image" to 100.0, "chunk" to 200.0))
        // average = 150.0
        assertEquals(300L, costs.estimateRemainingMs(mapOf("audio" to 2L)))
    }

    @Test
    fun `estimator empty means nothing left`() {
        val costs = MeasuredCosts(mapOf("image" to 100.0))
        assertEquals(0L, costs.estimateRemainingMs(emptyMap()))
        assertEquals(0L, MeasuredCosts(emptyMap()).estimateRemainingMs(mapOf("image" to 5L)))
    }

    @Test
    fun `record folds new samples into a running average`() {
        val costs = MeasuredCosts(mapOf("image" to 100.0))
        costs.record("image", 200L)
        assertEquals(150L, costs.estimateRemainingMs(mapOf("image" to 1L)))
        costs.record("audio", 400L)
        assertEquals(400L, costs.estimateRemainingMs(mapOf("audio" to 1L)))
    }

    @Test
    fun `negative attempt clamps to base backoff`() {
        assertEquals(30_000L, backoffMs(-1))
        assertEquals(30_000L, backoffMs(Int.MIN_VALUE))
    }

    @Test
    fun `negative sample is ignored`() {
        val costs = MeasuredCosts(mapOf("image" to 100.0))
        costs.record("image", -100L)
        assertEquals(100L, costs.estimateRemainingMs(mapOf("image" to 1L)))
        assertTrue(costs.estimateRemainingMs(mapOf("image" to 1L)) >= 0)
    }

    @Test
    fun `negative pending counts clamp to zero`() {
        val costs = MeasuredCosts(mapOf("image" to 100.0))
        assertEquals(0L, costs.estimateRemainingMs(mapOf("image" to -5L)))
    }

    @Test
    fun `clock skew never trips watchdog`() {
        assertFalse(watchdogTripped(lastProgressMs = 60_000L, nowMs = 0L))
    }
}
