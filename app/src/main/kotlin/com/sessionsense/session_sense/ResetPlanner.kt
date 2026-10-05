package com.sessionsense.session_sense

/** What to do with a "notify me when ready" request on this poll. */
enum class ResetRequest { NONE, FIRE, STALE }

/**
 * A reset request from Home's reset planner. [flag] is the reset time the user asked about (null: no request), [oldReset]
 * and [newReset] the window's reset time before and after this poll, [ended] whether that window just ended. It fires
 * for the window it was set for; one that matches neither the window that ended nor the current one is stale.
 */
fun resetNotifyDue(flag: Long?, oldReset: Long, newReset: Long, ended: Boolean): ResetRequest = when {
    flag == null || flag <= 0 -> ResetRequest.NONE
    ended && flag == oldReset -> ResetRequest.FIRE
    flag == newReset -> ResetRequest.NONE
    else -> ResetRequest.STALE
}

// A weekly reading this far below the last one is a reset, even if the reported reset time hasn't moved yet.
private const val WEEKLY_RESET_DROP = 20

/**
 * The weekly window has no tracker like [SessionTransition]: it ended when its reset time moved on or went away, or
 * usage dropped sharply. Reset times are pinned by [stableReset], so any other change is a real one.
 */
fun weeklyEnded(oldReset: Long, newReset: Long, oldPct: Int, newPct: Int) =
    (oldReset > 0 && newReset != oldReset && (newReset == 0L || newReset > oldReset)) || oldPct - newPct >= WEEKLY_RESET_DROP
