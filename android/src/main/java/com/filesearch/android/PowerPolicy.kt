package com.filesearch.android

import com.filesearch.android.engine.WATCHDOG_STUCK_MS as EngineWatchdogMs
import com.filesearch.android.engine.backoffMs as engineBackoffMs
import com.filesearch.android.engine.shouldPause as engineShouldPause
import com.filesearch.android.engine.watchdogTripped as engineWatchdogTripped

/**
 * Power-guard policy for background indexing (arch §6).
 *
 * F022: thin forwards to engine/Power.kt (compiled into :android via the
 * srcDir share) — the hand-mirrored logic is gone, one source of truth.
 * Signatures stay exactly as the field test + worker use them.
 */
typealias PauseReason = com.filesearch.android.engine.PauseReason

typealias MeasuredCosts = com.filesearch.android.engine.MeasuredCosts

/** Priority: thermal > battery > doze > charger-only, else [PauseReason.OK]. */
fun shouldPause(
    thermalSevere: Boolean,
    batteryLow: Boolean,
    dozing: Boolean,
    chargerOnly: Boolean,
    onCharger: Boolean,
): PauseReason = engineShouldPause(thermalSevere, batteryLow, dozing, chargerOnly, onCharger)

/** Retry backoff: 30s base, doubling, capped at 30min. [attempt] is 0-indexed. */
fun backoffMs(attempt: Int): Long = engineBackoffMs(attempt)

/** Decoder stuck past this with no progress: kill job, mark stopped, keep partial. */
const val WATCHDOG_STUCK_MS = EngineWatchdogMs

/** True when no progress for longer than [stuckAfterMs] (default [WATCHDOG_STUCK_MS]). */
fun watchdogTripped(
    lastProgressMs: Long,
    nowMs: Long,
    stuckAfterMs: Long = WATCHDOG_STUCK_MS,
): Boolean = engineWatchdogTripped(lastProgressMs, nowMs, stuckAfterMs)
