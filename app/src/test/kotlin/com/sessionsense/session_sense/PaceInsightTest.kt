package com.sessionsense.session_sense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class PaceInsightTest {
    private val zone = ZoneOffset.UTC
    private val now = 1_790_000_000_000L // a fixed instant; times below are relative to it
    private val h = 60 * 60 * 1000L
    private val day = 24 * h

    private fun insight(u: UsageSnapshot) = paceInsight(u.copy(connection = if (u.connection == "idle") "connected" else u.connection), now, zone).text

    @Test fun sessionRunsOutBeforeReset() {
        // 60% used in the first 2h of the window, 3h left: full in ~1h20m.
        val text = insight(UsageSnapshot(sessionPct = 60, sessionResetMs = now + 3 * h))
        assertTrue(text, text.startsWith("At this pace you'll hit the 5-hour limit in about 1h 20m"))
    }

    @Test fun sessionOnPaceFallsThroughToWeekly() {
        // 20% after 2h won't fill the session; weekly 30% after 3.5 days projects to 60%.
        val text = insight(UsageSnapshot(sessionPct = 20, sessionResetMs = now + 3 * h, weeklyPct = 30, weeklyResetMs = now + 84 * h))
        assertEquals("On track: about 60% of your weekly limit used by the ${java.time.Instant.ofEpochMilli(now + 84 * h).atZone(zone).dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::uppercaseChar)} reset.", text)
    }

    @Test fun weeklyRunsOutBeforeReset() {
        // 70% after 3.5 days: 100% about 1.5 days later, 2 days before the reset.
        val text = insight(UsageSnapshot(weeklyPct = 70, weeklyResetMs = now + 84 * h))
        assertTrue(text, text.startsWith("At this pace you'll reach your weekly limit around"))
        assertTrue(text, text.endsWith("2 days before it resets."))
    }

    @Test fun sessionPeaksNoLongerDriveTheWeeklyWarning() {
        // The old insight averaged session peaks: a 90% session reads as "weekly runs out in 1 day". Weekly is barely used.
        val text = insight(UsageSnapshot(sessionPct = 5, sessionResetMs = now + 4 * h, weeklyPct = 10, weeklyResetMs = now + 3 * day))
        assertTrue(text, text.startsWith("On track"))
    }

    @Test fun tooEarlyToExtrapolate() {
        // 30% five minutes into a session is a burst, not a rate; one hour into the week is too early for a weekly projection.
        val text = insight(UsageSnapshot(sessionPct = 30, sessionResetMs = now + 5 * h - 5 * 60_000, weeklyPct = 5, weeklyResetMs = now + 7 * day - h))
        assertEquals("30% of this 5-hour session used, 4h 55m left.", text)
    }

    @Test fun limitsReachedAndIdle() {
        assertTrue(insight(UsageSnapshot(sessionPct = 100, sessionResetMs = now + h)).startsWith("5-hour limit reached"))
        assertTrue(insight(UsageSnapshot(weeklyPct = 100, weeklyResetMs = now + day)).startsWith("Weekly limit reached"))
        assertEquals("Full 5-hour session available.", insight(UsageSnapshot()))
        assertEquals("Reconnect your account to see your pace.", insight(UsageSnapshot(connection = "expired")))
    }
}
