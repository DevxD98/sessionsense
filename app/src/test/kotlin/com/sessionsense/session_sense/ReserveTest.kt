package com.sessionsense.session_sense

import org.junit.Assert.*
import org.junit.Test

class ReserveTest {
    @Test fun freeThenReserveThenNothing() {
        assertEquals(ReserveState(20, 12, 20), reserveState(68, 20)) // 12% freely available · 20% reserved
        assertFalse(reserveState(68, 20).inReserve)
        assertEquals(ReserveState(20, 0, 20), reserveState(80, 20)) // right at the line
        assertTrue(reserveState(80, 20).inReserve)
        assertEquals(ReserveState(20, 0, 8), reserveState(92, 20)) // using reserve · 8% of 20% left
        assertEquals(ReserveState(20, 0, 0), reserveState(100, 20)) // exhausted
        assertEquals(ReserveState(0, 40, 0), reserveState(60, 0)) // off
        assertFalse(reserveState(100, 0).inReserve)
    }

    @Test fun alertsFireOnCrossingOnly() {
        assertEquals(setOf(ReserveAlert.NEAR), reserveAlerts(74, 75, 20)) // free 6% → 5%
        assertEquals(emptySet<ReserveAlert>(), reserveAlerts(75, 77, 20)) // already near: no repeat
        assertEquals(setOf(ReserveAlert.ENTERED), reserveAlerts(79, 80, 20))
        assertEquals(emptySet<ReserveAlert>(), reserveAlerts(80, 85, 20)) // already in
        assertEquals(setOf(ReserveAlert.ENTERED), reserveAlerts(60, 90, 20)) // a jump says "into the reserve" only
        assertEquals(emptySet<ReserveAlert>(), reserveAlerts(60, 70, 20))
    }

    @Test fun noAlertsWithoutAReserveOrWhenUsageFalls() {
        assertEquals(emptySet<ReserveAlert>(), reserveAlerts(70, 99, 0))
        assertEquals(emptySet<ReserveAlert>(), reserveAlerts(95, 0, 20)) // the weekly reset
        assertEquals(emptySet<ReserveAlert>(), reserveAlerts(85, 85, 20)) // every poll after crossing
    }
}
