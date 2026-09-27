package com.sessionsense.session_sense

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/**
 * Regression: the live notification was re-posted on every 5 s poll. Android kept it silent, but the Nothing launcher
 * (Glyph lights) treats every post as new, so it flashed continuously. Its content must only change when what it shows does.
 */
class OngoingContentTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = 1_790_520_000_000L
    private val snapshot = UsageSnapshot(sessionPct = 42, weeklyPct = 30, sessionResetMs = now + 3 * 3_600_000L, connection = "connected")

    @Test fun unchangedUsageGivesIdenticalContentAcrossPolls() {
        val first = ongoingContent(snapshot, "Claude", now, zone)
        (1..720).forEach { poll -> assertEquals(first, ongoingContent(snapshot.copy(lastUpdatedMs = now + poll * 5_000L), "Claude", now + poll * 5_000L, zone)) }
    }

    @Test fun limitReachedStaysStable() {
        val full = snapshot.copy(sessionPct = 100)
        assertEquals(ongoingContent(full, null, now, zone), ongoingContent(full, null, now + 60 * 60_000L, zone))
    }

    @Test fun changesWhenWhatItShowsChanges() {
        val base = ongoingContent(snapshot, null, now, zone)
        assertNotEquals(base, ongoingContent(snapshot.copy(sessionPct = 43), null, now, zone))
        assertNotEquals(base, ongoingContent(snapshot.copy(connection = "expired"), null, now, zone))
        assertNotEquals(base, ongoingContent(snapshot, "Codex", now, zone))
        assertEquals(snapshot.sessionResetMs, base.countdownToMs)
        assertTrue(base.text, base.text.startsWith("42% used · resets "))
    }

    @Test fun noCountdownWithoutAWindow() {
        assertEquals(0L, ongoingContent(UsageSnapshot(), null, now, zone).countdownToMs)
        assertEquals(0L, ongoingContent(snapshot.copy(sessionResetMs = now - 1), null, now, zone).countdownToMs)
    }
}
