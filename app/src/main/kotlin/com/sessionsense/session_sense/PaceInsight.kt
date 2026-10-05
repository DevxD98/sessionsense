package com.sessionsense.session_sense

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** The Home "Pace insight" card: [text] is the insight, [basis] says what it was worked out from. */
data class PaceInsight(val text: String, val basis: String)

internal const val SESSION_MS = 5 * 60 * 60 * 1000L
private const val WEEK_MS = 7 * 86_400_000L
// Too little of a window has passed to extrapolate before this; a burst in the first minutes would read as a crisis.
internal const val MIN_SESSION_ELAPSED_MS = 15 * 60_000L
private const val MIN_WEEK_ELAPSED_MS = 12 * 60 * 60 * 1000L

/**
 * Projects each window at its own average rate since it started (usage % / time elapsed in that window), so the
 * session and weekly limits are never mixed up. A window only "runs out" if it would hit 100% before it resets.
 * Most urgent first: session limit, weekly limit, then the weekly outlook, then the session.
 * [skipSessionLimit] drops the "you'll hit the 5-hour limit" line when the Runway card already says it.
 */
fun paceInsight(u: UsageSnapshot, now: Long, zone: ZoneId = ZoneId.systemDefault(), skipSessionLimit: Boolean = false): PaceInsight {
    fun clock(ms: Long, pattern: String) = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern(pattern))
    if (u.connection == "expired") return PaceInsight("Reconnect your account to see your pace.", "Sign-in expired")

    val sessionLive = u.sessionWindow && u.sessionPct > 0 && u.sessionResetMs > now
    val sessionLeft = u.sessionResetMs - now
    val sessionElapsed = SESSION_MS - sessionLeft
    if (sessionLive && u.sessionPct >= 100) return PaceInsight("5-hour limit reached. It resets at ${clock(u.sessionResetMs, "h:mm a")}.", "From your current session")
    if (sessionLive && sessionElapsed >= MIN_SESSION_ELAPSED_MS && !skipSessionLimit) {
        val toFull = ((100 - u.sessionPct) * sessionElapsed.toDouble() / u.sessionPct).toLong()
        if (toFull < sessionLeft) return PaceInsight("At this pace you'll hit the 5-hour limit in about ${paceSpan(toFull)}, before it resets at ${clock(u.sessionResetMs, "h:mm a")}.",
            "From your rate so far this session")
    }

    val weekLive = u.weeklyPct > 0 && u.weeklyResetMs > now
    val weekLeft = (u.weeklyResetMs - now).coerceAtMost(WEEK_MS)
    val weekElapsed = WEEK_MS - weekLeft
    if (weekLive && u.weeklyPct >= 100) return PaceInsight("Weekly limit reached. It resets ${clock(u.weeklyResetMs, "EEE h:mm a")}.", "From your weekly usage")
    if (weekLive && weekElapsed >= MIN_WEEK_ELAPSED_MS) {
        val basis = "From your average rate since the weekly reset"
        val toFull = ((100 - u.weeklyPct) * weekElapsed.toDouble() / u.weeklyPct).toLong()
        if (toFull < weekLeft) return PaceInsight("At this pace you'll reach your weekly limit around ${clock(now + toFull, "EEE h a")}, ${paceSpan(weekLeft - toFull)} before it resets.", basis)
        val projected = (u.weeklyPct * WEEK_MS.toDouble() / weekElapsed).roundToInt().coerceIn(u.weeklyPct, 99)
        return PaceInsight("On track: about $projected% of your weekly limit used by the ${clock(u.weeklyResetMs, "EEE")} reset.", basis)
    }

    return when {
        sessionLive -> PaceInsight("${u.sessionPct}% of this 5-hour session used, ${paceSpan(sessionLeft)} left.", "From your current session")
        u.sessionWindow -> PaceInsight("Full 5-hour session available.", "No usage in the current window")
        else -> PaceInsight("${u.weeklyPct}% of your weekly limit used.", "From your weekly usage")
    }
}

internal fun paceSpan(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(1)
    return when { m >= 48 * 60 -> "${m / (24 * 60)} days"; m >= 60 -> "${m / 60}h ${m % 60}m"; else -> "${m}m" }
}
