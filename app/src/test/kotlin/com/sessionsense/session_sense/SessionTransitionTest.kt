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
}
