package com.filesearch.android

/**
 * Power-guard policy for background indexing (arch §6).
 *
 * Exact-logic mirror of engine `Power.kt` (`shouldPause`, `backoffMs`,
 * `WATCHDOG_STUCK_MS`, `watchdogTripped`, `MeasuredCosts`). It is duplicated —
 * not shared — because `:android` (an Android app module) cannot depend on
 * `:app` (a `kotlin("jvm")`-only module); extracting a shared module is later
 * work. Keep the two in sync; the field test pins the behaviour.
 *
 * // shortcut: hand-mirrored from engine/Power.kt — any logic change must be ported to both, unify into a shared module when the build allows it.
 */
enum class PauseReason {
    OK,
    THERMAL_SEVERE,
    BATTERY_LOW,
    DOZE,
    CHARGER_ONLY,
}

/** Priority: thermal > battery > doze > charger-only, else [PauseReason.OK]. */
fun shouldPause(
    thermalSevere: Boolean,
    batteryLow: Boolean,
    dozing: Boolean,
    chargerOnly: Boolean,
    onCharger: Boolean,
): PauseReason =
    when {
        thermalSevere -> PauseReason.THERMAL_SEVERE
        batteryLow -> PauseReason.BATTERY_LOW
        dozing -> PauseReason.DOZE
        chargerOnly && !onCharger -> PauseReason.CHARGER_ONLY
        else -> PauseReason.OK
    }

private const val BACKOFF_BASE_MS = 30_000L
private const val BACKOFF_MAX_SHIFT = 6
private const val BACKOFF_CAP_MS = 1_800_000L

/** Retry backoff: 30s base, doubling, capped at 30min. [attempt] is 0-indexed. */
fun backoffMs(attempt: Int): Long = minOf(BACKOFF_BASE_MS shl attempt.coerceIn(0, BACKOFF_MAX_SHIFT), BACKOFF_CAP_MS)

/** Decoder stuck past this with no progress: kill job, mark stopped, keep partial. */
const val WATCHDOG_STUCK_MS = 5 * 60 * 1000L

/** True when no progress for longer than [stuckAfterMs] (default [WATCHDOG_STUCK_MS]). */
fun watchdogTripped(
    lastProgressMs: Long,
    nowMs: Long,
    stuckAfterMs: Long = WATCHDOG_STUCK_MS,
): Boolean = nowMs - lastProgressMs > stuckAfterMs

/**
 * Honest "≈N min left" estimator (arch §6): per-kind ms/file running averages.
 * Unknown kinds use the average of known rates; no known rates -> 0.
 */
class MeasuredCosts(perKindMsPerFile: Map<String, Double>) {
    private val sums = perKindMsPerFile.toMutableMap()
    private val counts = perKindMsPerFile.mapValues { 1L }.toMutableMap()

    @Synchronized
    fun estimateRemainingMs(pendingCounts: Map<String, Long>): Long {
        if (pendingCounts.isEmpty() || sums.isEmpty()) return 0L
        val fallback = sums.map { (k, s) -> s / counts.getValue(k) }.average()
        var sum = 0.0
        for ((kind, count) in pendingCounts) {
            val rate = if (sums.containsKey(kind)) sums.getValue(kind) / counts.getValue(kind) else fallback
            sum += rate * count.coerceAtLeast(0L)
        }
        return sum.toLong()
    }

    /** Fold one finished file into the running average for [kind]. */
    @Synchronized
    fun record(
        kind: String,
        elapsedMs: Long,
    ) {
        if (elapsedMs < 0) return // clock-skew poison
        sums[kind] = (sums[kind] ?: 0.0) + elapsedMs
        counts[kind] = (counts[kind] ?: 0L) + 1L
    }
}
