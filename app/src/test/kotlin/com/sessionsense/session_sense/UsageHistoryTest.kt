package com.sessionsense.session_sense

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class UsageHistoryTest {
    private val hour = 3_600_000L

    @Test fun rolloverWithoutZeroReadingClosesOldWindowAndOpensNewOne() {
        val t0 = 2_000_000_000_000L
        val first = SessionTransition.apply(TrackingState(0, 0, 0), 40, t0 + 4 * hour, t0)
        val peak = SessionTransition.apply(first.state, 70, t0 + 4 * hour, t0 + hour)
        // App was dead across the reset; next poll already sees a fresh window at 5%.
        val next = SessionTransition.apply(peak.state, 5, t0 + 10 * hour, t0 + 6 * hour)
        assertTrue(next.started)
        assertEquals(70, next.ended?.pctUsed)
        assertEquals(t0 + 4 * hour, next.ended?.endMs) // closed at its reset time, not at the late poll
        assertEquals(t0 + 5 * hour, next.state.activeStartMs)
        assertEquals(5, next.state.peakPct)
    }

    @Test fun sameWindowResetJitterIsNotARollover() {
        val t0 = 2_000_000_000_000L
        val first = SessionTransition.apply(TrackingState(0, 0, 0), 10, t0 + 4 * hour, t0)
        val next = SessionTransition.apply(first.state, 12, t0 + 4 * hour + 900, t0 + 5_000)
        assertFalse(next.started); assertNull(next.ended)
        assertEquals(12, next.state.peakPct)
    }

    @Test fun zeroReadingAfterResetEndsAtResetTime() {
        val t0 = 2_000_000_000_000L
        val active = SessionTransition.apply(TrackingState(0, 0, 0), 30, t0 + 2 * hour, t0)
        val ended = SessionTransition.apply(active.state, 0, 0, t0 + 9 * hour)
        assertEquals(t0 + 2 * hour, ended.ended?.endMs)
    }

    @Test fun samplesRecordOnChangeOrHeartbeatOnly() {
        val a = UsageSample(ts = 0, sessionPct = 10, weeklyPct = 20, opusPct = 0, sonnetPct = 0)
        assertTrue(SamplePolicy.shouldRecord(null, a))
        assertFalse(SamplePolicy.shouldRecord(a, a.copy(ts = 5_000)))
        assertTrue(SamplePolicy.shouldRecord(a, a.copy(ts = 5_000, sessionPct = 11)))
        assertTrue(SamplePolicy.shouldRecord(a, a.copy(ts = SamplePolicy.HEARTBEAT_MS)))
    }

    @Test fun dailyPeaksIncludeLiveSamplesAndOlderSessions() {
        val zone = ZoneOffset.UTC; val today = LocalDate.of(2026, 9, 27)
        val todayMs = today.atStartOfDay(zone).toInstant().toEpochMilli(); val day = 86_400_000L
        val samples = listOf(
            UsageSample(ts = todayMs + hour, sessionPct = 15, weeklyPct = 1, opusPct = 0, sonnetPct = 0),
            UsageSample(ts = todayMs + 2 * hour, sessionPct = 42, weeklyPct = 2, opusPct = 0, sonnetPct = 0),
        )
        val sessions = listOf(SessionRecord(startMs = todayMs - 3 * day, endMs = todayMs - 3 * day + 5 * hour, pctUsed = 88))
        val h = UsageHistory.build(samples, sessions, today, zone)
        assertEquals(listOf(0, 0, 0, 88, 0, 0, 42), h.dailyPeaks)
        assertEquals(2, h.activeDays)
        assertEquals(1, h.between(todayMs + 90 * 60_000L, todayMs + 3 * hour).size)
    }
}

class AccountKeysTest {
    @Test fun defaultAccountKeepsLegacyKeyNamesSoExistingDataCarriesOver() {
        assertEquals("session_pct", AccountKeys(DEFAULT_ACCOUNT).SESSION.name)
        assertEquals("alert_flags", AccountKeys(DEFAULT_ACCOUNT).ALERT_FLAGS.name)
    }

    @Test fun otherAccountsAreIsolated() {
        val a = AccountKeys("a1b2c3d4"); val b = AccountKeys("e5f6a7b8")
        assertEquals("acct.a1b2c3d4.session_pct", a.SESSION.name)
        assertNotEquals(a.ALERT_FLAGS.name, b.ALERT_FLAGS.name)
        assertNotEquals(a.PLAN.name, AccountKeys(DEFAULT_ACCOUNT).PLAN.name)
    }
}
