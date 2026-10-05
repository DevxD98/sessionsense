package com.sessionsense.session_sense

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The Home "Runway" card: when the current 5-hour window would hit its limit. [useLeftMs] is set only when it would hit
 * it before the reset (it drives the card's colour); [hitAtMs] is when, rounded to 5 minutes.
 */
data class Runway(val text: String, val detail: String?, val basis: String, val hitAtMs: Long = 0, val useLeftMs: Long? = null)

private const val RECENT_MS = 30 * 60_000L
private const val MIN_RECENT_SPAN_MS = 10 * 60_000L
private const val ROUND_MS = 5 * 60_000L

/**
 * Session window only (weekly pace stays in [paceInsight]). Prefers the rate over the last 30 minutes, so a burst after
 * an idle hour shows up, and falls back to the window average. Null when there's nothing to project: no window, the
 * limit already reached, an expired login, or under 15 minutes in. [samples] are the account's, ascending.
 */
fun runway(u: UsageSnapshot, samples: List<UsageSample>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Runway? {
    if (u.connection == "expired" || !u.sessionWindow || u.sessionPct <= 0 || u.sessionPct >= 100 || u.sessionResetMs <= now) return null
    val windowStart = u.sessionResetMs - SESSION_MS
    val elapsed = now - windowStart
    if (elapsed < MIN_SESSION_ELAPSED_MS) return null
    fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a"))
    val resets = clock(u.sessionResetMs)

    // Usage only moves when a change is recorded, so the last sample before the cutoff is the value at the cutoff, and
    // the live reading is the value now. Samples from the previous window don't count.
    val cutoff = maxOf(windowStart, now - RECENT_MS)
    val inWindow = samples.filter { it.ts in windowStart..now }
    val anchor = inWindow.lastOrNull { it.ts <= cutoff }?.takeIf { cutoff > windowStart }
    val points = listOfNotNull(anchor?.let { cutoff to it.sessionPct }) + inWindow.filter { it.ts > cutoff }.map { it.ts to it.sessionPct } + (now to u.sessionPct)
    val rise = points.last().second - points.first().second
    val spanMs = points.last().first - points.first().first
    if (anchor != null && rise <= 0) return Runway("Idle for now · ${100 - u.sessionPct}% left until $resets", null, "No usage in the last 30 min")

    val recent = points.size >= 2 && spanMs >= MIN_RECENT_SPAN_MS && rise > 0
    val perMs = if (recent) rise.toDouble() / spanMs else u.sessionPct.toDouble() / elapsed
    val basis = if (recent) "From the last 30 min" else "From your average this session"
    val toFull = ((100 - u.sessionPct) / perMs).toLong()
    if (now + toFull < u.sessionResetMs) {
        val hitAt = roundTo5(now + toFull)
        val left = roundTo5(toFull).coerceAtLeast(ROUND_MS)
        return Runway("At your current pace you'll hit the limit around ${clock(hitAt)}", "~${paceSpan(left)} of use left at this rate", basis, hitAt, left)
    }
    val atReset = (u.sessionPct + perMs * (u.sessionResetMs - now)).roundToInt().coerceIn(u.sessionPct, 99)
    return Runway("At this pace you'll be at about $atReset% when it resets at $resets", null, basis)
}

// Home recomposes every second; a projection rounded to 5 minutes doesn't flicker as the seconds tick.
internal fun roundTo5(ms: Long) = (ms + ROUND_MS / 2) / ROUND_MS * ROUND_MS
