package com.sessionsense.session_sense

data class TrackingState(val previousPct: Int, val activeStartMs: Long, val peakPct: Int, val resetMs: Long = 0)
data class TrackingResult(val state: TrackingState, val started: Boolean = false, val ended: SessionRecord? = null)

object SessionTransition {
    private const val WINDOW_MS = 5 * 60 * 60 * 1000L
    // A new window can only start after the old one resets, so its resets_at lands ≥ 5h later; 1h absorbs API jitter.
    private const val ROLLOVER_MS = 60 * 60 * 1000L

    fun apply(old: TrackingState, newPct: Int, resetMs: Long, nowMs: Long): TrackingResult {
        val pct = newPct.coerceIn(0, 100)
        // resets_at jumped forward without a 0% reading in between (app killed, phone off, or a new window started
        // right after the reset): close the old window and open the new one in the same poll.
        if (old.previousPct > 0 && pct > 0 && old.activeStartMs > 0 && old.resetMs > 0 && resetMs - old.resetMs >= ROLLOVER_MS) {
            val record = SessionRecord(startMs = old.activeStartMs, endMs = windowEnd(old, nowMs), pctUsed = old.peakPct)
            return TrackingResult(TrackingState(pct, windowStart(resetMs, nowMs), pct, resetMs), started = true, ended = record)
        }
        if (old.previousPct == 0 && pct > 0) {
            return TrackingResult(TrackingState(pct, windowStart(resetMs, nowMs), pct, resetMs), started = true)
        }
        if (old.previousPct > 0 && pct == 0 && old.activeStartMs > 0) {
            val record = SessionRecord(startMs = old.activeStartMs, endMs = windowEnd(old, nowMs), pctUsed = old.peakPct)
            return TrackingResult(TrackingState(0, 0, 0), ended = record)
        }
        return TrackingResult(old.copy(previousPct = pct, peakPct = maxOf(old.peakPct, pct), resetMs = if (resetMs > 0) resetMs else old.resetMs))
    }

    private fun windowStart(resetMs: Long, nowMs: Long) = (if (resetMs > nowMs) resetMs - WINDOW_MS else nowMs).coerceAtMost(nowMs)

    // The window closed at its reset time, not whenever we next happened to poll.
    private fun windowEnd(old: TrackingState, nowMs: Long) = if (old.resetMs in (old.activeStartMs + 1)..nowMs) old.resetMs else nowMs
}

internal fun resetAlertKey(sessionResetMs: Long) = "reset:$sessionResetMs"

internal fun retainAlertFlags(flags: Set<String>, sessionResetMs: Long, weeklyResetMs: Long) =
    flags.filterTo(mutableSetOf()) { key ->
        key.startsWith("digest:") || key.endsWith(":$sessionResetMs") || key.endsWith(":$weeklyResetMs")
    }
