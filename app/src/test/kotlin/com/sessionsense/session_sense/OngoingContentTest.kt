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

    @Test fun goingOfflineIsNotAChange() {
        assertEquals(ongoingContent(snapshot, null, now, zone), ongoingContent(snapshot.copy(connection = "offline"), null, now, zone))
    }

    // ─── OngoingPolicy: when a changed notification is actually re-posted ───────────────────────────────────

    private val shown = ongoingContent(snapshot, null, now, zone)
    private fun repost(next: UsageSnapshot, after: Long, quiet: Boolean = false, title: String? = null) =
        OngoingPolicy.shouldRepost(shown, now, ongoingContent(next, title, now + after, zone), now + after, quiet)

    @Test fun firstPostAlwaysGoesOut() = assertTrue(OngoingPolicy.shouldRepost(null, 0, shown, now, quiet = true))

    @Test fun percentTicksWaitForTheMinimumGap() {
        assertFalse(repost(snapshot.copy(sessionPct = 43), 5_000))
        assertFalse(repost(snapshot.copy(sessionPct = 55), OngoingPolicy.MIN_REPOST_MS - 1))
        assertTrue(repost(snapshot.copy(sessionPct = 55), OngoingPolicy.MIN_REPOST_MS))
    }

    @Test fun importantChangesGoOutAtOnce() {
        assertTrue(repost(snapshot.copy(sessionPct = 60), 5_000)) // into amber
        assertTrue(repost(snapshot.copy(sessionPct = 0), 5_000)) // window ended
        assertTrue(repost(snapshot.copy(connection = "expired"), 5_000))
        assertTrue(repost(snapshot, 5_000, title = "Codex"))
    }

    @Test fun quietHoursHoldBackEverythingButAccountAndLoginChanges() {
        assertFalse(repost(snapshot.copy(sessionPct = 0), 5_000, quiet = true))
        assertFalse(repost(snapshot.copy(sessionPct = 90), OngoingPolicy.MIN_REPOST_MS * 3, quiet = true))
        assertTrue(repost(snapshot.copy(connection = "expired"), 5_000, quiet = true))
        assertTrue(repost(snapshot, 5_000, quiet = true, title = "Codex"))
    }

    @Test fun noCountdownWithoutAWindow() {
        assertEquals(0L, ongoingContent(UsageSnapshot(), null, now, zone).countdownToMs)
        assertEquals(0L, ongoingContent(snapshot.copy(sessionResetMs = now - 1), null, now, zone).countdownToMs)
    }
}
