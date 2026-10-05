package com.sessionsense.session_sense

/**
 * The weekly limit with [reserve]% kept back. [free] is what's left before the reserve, [reserveLeft] what's left of the
 * reserve itself (all of it until [free] runs out).
 */
data class ReserveState(val reserve: Int, val free: Int, val reserveLeft: Int) {
    val inReserve get() = reserve > 0 && free == 0
}

fun reserveState(weeklyPct: Int, reserve: Int): ReserveState {
    val w = weeklyPct.coerceIn(0, 100); val r = reserve.coerceIn(0, 100)
    return ReserveState(r, (100 - r - w).coerceAtLeast(0), (100 - w).coerceAtMost(r))
}

enum class ReserveAlert { NEAR, ENTERED }

/** Free share at or below which the user hears they're close to the reserve. */
const val RESERVE_NEAR_PCT = 5

/**
 * Alerts for a weekly reading moving from [oldWeekly] to [newWeekly]: only when a threshold is crossed, so a reading that
 * stays past it doesn't repeat them. A jump straight into the reserve only says so, not "nearly there" as well.
 */
fun reserveAlerts(oldWeekly: Int, newWeekly: Int, reserve: Int): Set<ReserveAlert> {
    if (reserve <= 0 || newWeekly <= oldWeekly) return emptySet()
    val before = reserveState(oldWeekly, reserve); val after = reserveState(newWeekly, reserve)
    return when {
        !before.inReserve && after.inReserve -> setOf(ReserveAlert.ENTERED)
        before.free > RESERVE_NEAR_PCT && after.free <= RESERVE_NEAR_PCT -> setOf(ReserveAlert.NEAR)
        else -> emptySet()
    }
}
