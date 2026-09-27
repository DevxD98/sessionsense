package com.sessionsense.session_sense

import android.content.pm.PackageInstaller
import org.junit.Assert.*
import org.junit.Test

class InstallOutcomeTest {
    @Test fun successAndPendingAreNotFailures() {
        assertNull(InstallOutcome.of(PackageInstaller.STATUS_SUCCESS, null))
        assertNull(InstallOutcome.of(PackageInstaller.STATUS_PENDING_USER_ACTION, null))
    }

    @Test fun userCancelIsSoft() {
        val f = InstallOutcome.of(PackageInstaller.STATUS_FAILURE_ABORTED, "User rejected permissions")!!
        assertTrue(f.cancelled); assertEquals("Update cancelled", f.title)
    }

    @Test fun failuresCarryTheReason() {
        val conflict = InstallOutcome.of(PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match")!!
        assertEquals("Signature conflict", conflict.title); assertFalse(conflict.cancelled)
        assertTrue(conflict.message.contains("signatures do not match"))
        assertEquals("Not compatible", InstallOutcome.of(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, null)!!.title)
        assertEquals("Not enough space", InstallOutcome.of(PackageInstaller.STATUS_FAILURE_STORAGE, null)!!.title)
        assertEquals("Update failed", InstallOutcome.of(PackageInstaller.STATUS_FAILURE, " ")!!.title)
        assertFalse(InstallOutcome.of(PackageInstaller.STATUS_FAILURE, " ")!!.message.contains("("))
    }
}
