package com.sessionsense.session_sense

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class RunwayTest {
    private val zone = ZoneOffset.UTC
    private val now = 1_790_000_100_000L // on a 5-minute boundary
    private val m = 60_000L
    private val h = 60 * m

    private fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a"))
    private fun usage(pct: Int, resetIn: Long = 3 * h) = UsageSnapshot(sessionPct = pct, sessionResetMs = now + resetIn, connection = "connected")
    private fun sample(ago: Long, pct: Int) = UsageSample(ts = now - ago, sessionPct = pct, weeklyPct = 0, opusPct = 0, sonnetPct = 0)
    private fun run(u: UsageSnapshot, vararg samples: UsageSample) = runway(u, samples.toList(), now, zone)

    @Test fun prefersTheLastThirtyMinutes() {
        // 2h in at 40%. The window average (20%/h) would just reach 100% at the reset; the last 30 min rose 20 points.
        val r = run(usage(40), sample(2 * h, 2), sample(40 * m, 20), sample(10 * m, 30))!!
        assertEquals("From the last 30 min", r.basis)
        assertEquals(90 * m, r.useLeftMs)
        assertEquals("At your current pace you'll hit the limit around ${clock(now + 90 * m)}", r.text)
        assertEquals("~1h 30m of use left at this rate", r.detail)
    }

    @Test fun fallsBackToTheWindowAverage() {
        // No samples: 60% in 2h is 30%/h, so the last 40% takes 80 minutes.
        val r = run(usage(60))!!
        assertEquals("From your average this session", r.basis)
        assertEquals(80 * m, r.useLeftMs)
        // Under 10 minutes of recent samples isn't a rate yet: 20 min in, one sample 5 min ago.
        assertEquals("From your average this session", run(usage(12, resetIn = 5 * h - 20 * m), sample(5 * m, 10))!!.basis)
    }

    @Test fun tooEarlyToProject() = assertNull(run(usage(30, resetIn = 5 * h - 10 * m)))

    @Test fun finishesBelowTheLimit() {
        // 20% in 2h with 3h left: about 50% at the reset.
        val r = run(usage(20))!!
        assertEquals("At this pace you'll be at about 50% when it resets at ${clock(now + 3 * h)}", r.text)
        assertNull(r.useLeftMs); assertNull(r.detail)
    }

    @Test fun idleWhenNothingMovedInThirtyMinutes() {
        val r = run(usage(40), sample(2 * h, 30), sample(50 * m, 40))!!
        assertEquals("Idle for now · 60% left until ${clock(now + 3 * h)}", r.text)
        assertNull(r.useLeftMs)
    }

    @Test fun samplesFromThePreviousWindowDontCount() {
        // An 80% reading from the last window, 40 minutes ago, would make this one look idle (or falling).
        val r = run(usage(30, resetIn = 5 * h - 35 * m), sample(40 * m, 80), sample(20 * m, 10))!!
        assertNotEquals("No usage in the last 30 min", r.basis)
    }

    @Test fun roundsToFiveMinutes() {
        assertEquals(now, roundTo5(now + 2 * m))
        assertEquals(now + 5 * m, roundTo5(now + 3 * m))
        // 87% in 2h with 3h left: 13% at 43.5%/h is about 18 minutes, shown as 20, and the card is in its red zone.
        val r = run(usage(87))!!
        assertEquals(0L, r.hitAtMs % (5 * m))
        assertEquals(20 * m, r.useLeftMs)
        assertEquals("~20m of use left at this rate", r.detail)
        // The second-by-second ticker doesn't change what the card says.
        assertEquals(r.text, runway(usage(87), emptyList(), now + 20_000, zone)!!.text)
    }

    @Test fun nothingToProject() {
        assertNull(run(usage(100)))
        assertNull(run(usage(40).copy(connection = "expired")))
        assertNull(run(usage(40).copy(sessionWindow = false)))
        assertNull(run(usage(0)))
        assertNull(run(usage(40, resetIn = -m)))
    }
}
