package com.sessionsense.session_sense

import org.junit.Assert.*
import org.junit.Test

class SessionTransitionTest {
    @Test fun backgroundSessionIsRecordedAcrossPolls() {
        val now = 2_000_000_000L
        val started = SessionTransition.apply(TrackingState(0, 0, 0), 12, now + 3_600_000, now)
        assertTrue(started.started)
        val peak = SessionTransition.apply(started.state, 83, now + 3_600_000, now + 1000)
        val ended = SessionTransition.apply(peak.state, 0, 0, now + 3_600_000)
        assertEquals(83, ended.ended?.pctUsed)
        assertEquals(0, ended.state.activeStartMs)
    }

    @Test fun resetAlertIsRetainedAndOnlyEligibleOnceAcrossZeroPolls() {
        val resetMs = 3_000_000_000L
        var tracking = TrackingState(previousPct = 72, activeStartMs = 1_000_000_000L, peakPct = 88)
        var flags = emptySet<String>()
        var eligible = 0

        repeat(4) { poll ->
            val result = SessionTransition.apply(tracking, 0, resetMs, resetMs + poll * 5_000L)
            tracking = result.state
            if (result.ended != null) {
                val key = resetAlertKey(resetMs)
                if (key !in flags) {
                    eligible++
                    flags = retainAlertFlags(flags + key, resetMs, 0)
                }
            }
        }

        assertEquals(1, eligible)
        assertTrue(resetAlertKey(resetMs) in flags)
    }

    /**
     * Regression: at the limit, Codex's reset time is "now + reset_after_seconds", so it moves by a few ms every poll.
     * Alert keys embed the reset time, so every poll looked like a new window and the same alert fired every 5 s.
     */
    @Test fun jitteringResetTimeAlertsOnlyOnce() {
        val now = 2_000_000_000_000L
        var stored = 0L
        var flags = emptySet<String>()
        var fired = 0
        repeat(120) { poll ->
            val t = now + poll * 5_000L + (poll % 7) * 13L                 // poll time, with scheduling noise
            val reported = t + (8 * 60 - poll * 5) * 1000L + (poll % 3)     // "reset in N s" measured from t
            stored = stableReset(stored, reported)
            val key = "10:$stored"
            if (reported - t in 1..10 * 60_000L && key !in flags) { fired++; flags = flags + key }
            flags = retainAlertFlags(flags, stored, 0)
        }
        assertEquals(1, fired)
    }

    @Test fun stableResetKeepsTheWindowButFollowsARealRollover() {
        val reset = 2_000_000_000_000L
        assertEquals(reset, stableReset(0, reset))                            // first reading
        assertEquals(reset, stableReset(reset, reset + 999))                  // jitter
        assertEquals(reset, stableReset(reset, reset - 90_000))               // jitter the other way
        assertEquals(reset + 5 * 3_600_000L, stableReset(reset, reset + 5 * 3_600_000L)) // next 5-hour window
        assertEquals(0L, stableReset(reset, 0))                               // no window reported
    }
}
