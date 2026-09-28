package com.sessionsense.session_sense

import org.junit.Assert.*
import org.junit.Test

class ClaudeUsageParserTest {
    private val now = 1_790_520_000_000L
    private fun parse(extra: String) = UsageParser.parse("""{"five_hour":{"utilization":10,"resets_at":"2026-09-28T13:40:00.126810+00:00"},
        "seven_day":{"utilization":43,"resets_at":"2026-10-02T09:00:00.126832+00:00"}$extra}""", now)

    /** The shape claude.ai returns today for plans without per-model limits (captured 2026-09-28). */
    @Test fun nullModelLimitsAreNotReported() {
        val s = parse(""","seven_day_opus":null,"seven_day_sonnet":null,"seven_day_oauth_apps":null""")
        assertEquals(10, s.sessionPct); assertEquals(43, s.weeklyPct)
        assertFalse(s.opusReported); assertFalse(s.sonnetReported)
        assertEquals(0, s.opusPct); assertEquals(0, s.sonnetPct)
    }

    @Test fun missingModelLimitsAreNotReported() {
        val s = parse("")
        assertFalse(s.opusReported); assertFalse(s.sonnetReported)
    }

    @Test fun nullUtilizationIsNotReported() {
        assertFalse(parse(""","seven_day_sonnet":{"utilization":null,"resets_at":null}""").sonnetReported)
    }

    @Test fun realModelLimitsAreReported() {
        val s = parse(""","seven_day_opus":{"utilization":12},"seven_day_sonnet":{"utilization":"30.4"}""")
        assertTrue(s.opusReported); assertEquals(12, s.opusPct)
        assertTrue(s.sonnetReported); assertEquals(30, s.sonnetPct)
    }

    @Test fun aRealZeroIsStillReported() {
        val s = parse(""","seven_day_sonnet":{"utilization":0}""")
        assertTrue(s.sonnetReported); assertEquals(0, s.sonnetPct)
    }
}
