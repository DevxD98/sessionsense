package com.sessionsense.session_sense

import org.junit.Assert.*
import org.junit.Test

class ResetPlannerTest {
    private val h = 3_600_000L
    private val session = 1_790_000_000_000L // the 5-hour reset the user asked about
    private val week = session + 50 * h

    @Test fun firesWhenTheRequestedWindowEnds() {
        assertEquals(ResetRequest.NONE, resetNotifyDue(session, session, session, ended = false)) // still waiting
        assertEquals(ResetRequest.FIRE, resetNotifyDue(session, session, 0, ended = true))
        // A new window started straight after: still the window the user asked about.
        assertEquals(ResetRequest.FIRE, resetNotifyDue(session, session, session + 5 * h, ended = true))
    }

    @Test fun staleRequestsNeverFire() {
        // Set for a window that's already gone (e.g. the app was off when it reset): cleared, not announced.
        assertEquals(ResetRequest.STALE, resetNotifyDue(session, session + 5 * h, 0, ended = true))
        assertEquals(ResetRequest.STALE, resetNotifyDue(session, session + 5 * h, session + 5 * h, ended = false))
    }

    @Test fun firesOnce() {
        // The caller clears the request after it fires, so the next poll has nothing to do.
        var flag: Long? = session
        val polls = listOf(Triple(session, 0L, true), Triple(0L, 0L, false), Triple(0L, session + 6 * h, false))
        val fired = polls.count { (old, new, ended) ->
            val r = resetNotifyDue(flag, old, new, ended)
            if (r != ResetRequest.NONE) flag = null
            r == ResetRequest.FIRE
        }
        assertEquals(1, fired)
        assertEquals(ResetRequest.NONE, resetNotifyDue(null, session, 0, ended = true))
    }

    @Test fun sessionAndWeeklyAreSeparate() {
        // The session ends; a weekly request doesn't fire with it, and a session request ignores the weekly window.
        val weeklyDone = weeklyEnded(week, week, 40, 40)
        assertFalse(weeklyDone)
        assertEquals(ResetRequest.NONE, resetNotifyDue(week, week, week, weeklyDone))
        assertEquals(ResetRequest.FIRE, resetNotifyDue(session, session, 0, ended = true))
        // The week resets mid-session: the weekly request fires, the session one keeps waiting for its own window.
        assertTrue(weeklyEnded(week, week + 7 * 24 * h, 90, 0))
        assertEquals(ResetRequest.NONE, resetNotifyDue(session, session, session, ended = false))
    }

    @Test fun weeklyWindowEnds() {
        assertTrue(weeklyEnded(week, week + 7 * 24 * h, 90, 2)) // moved on to next week
        assertTrue(weeklyEnded(week, 0, 15, 0)) // no window reported after the reset
        assertTrue(weeklyEnded(week, week, 85, 3)) // usage dropped before the reset time caught up
        assertFalse(weeklyEnded(week, week, 40, 41))
        assertFalse(weeklyEnded(0, week, 0, 5)) // the first reading of a window
        assertEquals(ResetRequest.FIRE, resetNotifyDue(week, week, week + 7 * 24 * h, weeklyEnded(week, week + 7 * 24 * h, 90, 2)))
    }
}
