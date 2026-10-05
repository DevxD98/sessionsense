package com.sessionsense.session_sense

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private fun manifest(name: String, min: Int = 0) =
        UpdateManifest(Semver.parse(name)!!.code, name, "https://github.com/x/y/releases/download/v$name/a.apk", "a".repeat(64), 1, min)
    private val installed = Semver.parse("1.2.0")!!.code

    @Test fun semverCodesAndOrdering() {
        assertEquals(1_000_000, Semver.parse("1.0.0")!!.code)
        assertEquals(2_010_009, Semver.parse("2.10.9")!!.code)
        assertTrue(Semver.parse("1.10.0")!! > Semver.parse("1.9.99")!!)
        assertTrue(Semver.parse("2.0.0")!! > Semver.parse("1.999.999")!!)
        assertTrue(Semver.parse("1.0.1")!! > Semver.parse("1.0.0")!!)
        listOf("1.0", "1.0.0.0", "1.1000.0", "a.b.c", "", "1.0.0-beta", "9999.0.0").forEach { assertNull(it, Semver.parse(it)) }
    }

    @Test fun versionComparison() {
        assertEquals(UpdateStatus.UpToDate, UpdatePolicy.status(installed, null, 0))
        assertEquals(UpdateStatus.UpToDate, UpdatePolicy.status(installed, manifest("1.2.0"), 0))
        assertEquals(UpdateStatus.UpToDate, UpdatePolicy.status(installed, manifest("1.1.9"), 0))
        assertEquals(UpdateStatus.Available(manifest("1.2.1"), skipped = false), UpdatePolicy.status(installed, manifest("1.2.1"), 0))
        assertEquals(UpdateStatus.Available(manifest("2.0.0"), skipped = false), UpdatePolicy.status(installed, manifest("2.0.0"), 0))
    }

    @Test fun minSupportedBlocks() {
        val m = manifest("1.3.0", min = Semver.parse("1.2.5")!!.code)
        assertEquals(UpdateStatus.Required(m), UpdatePolicy.status(installed, m, 0))
        // Skipping can't dodge a required update.
        assertEquals(UpdateStatus.Required(m), UpdatePolicy.status(installed, m, m.versionCode))
        // Exactly at the minimum is still supported.
        assertTrue(UpdatePolicy.status(installed, manifest("1.3.0", min = installed), 0) is UpdateStatus.Available)
    }

    @Test fun skipVersion() {
        val m = manifest("1.3.0")
        assertEquals(UpdateStatus.Available(m, skipped = true), UpdatePolicy.status(installed, m, m.versionCode))
        // A newer release than the skipped one is offered again.
        val newer = manifest("1.4.0")
        assertEquals(UpdateStatus.Available(newer, skipped = false), UpdatePolicy.status(installed, newer, m.versionCode))
        val skipped = UpdateUiState(status = UpdateStatus.Available(m, skipped = true))
        assertNull(skipped.pending); assertEquals(m, skipped.offered)
        assertEquals(newer, UpdateUiState(status = UpdateStatus.Available(newer, skipped = false)).pending)
    }

    @Test fun checkThrottling() {
        val h = 60 * 60 * 1000L; val t = 1_790_000_000_000L
        assertTrue(UpdatePolicy.shouldCheck(t, lastCheckMs = 0))
        assertFalse(UpdatePolicy.shouldCheck(t, lastCheckMs = t - 5 * h))
        assertFalse(UpdatePolicy.shouldCheck(t, lastCheckMs = t - 6 * h + 1))
        assertTrue(UpdatePolicy.shouldCheck(t, lastCheckMs = t - 6 * h))
        assertTrue(UpdatePolicy.shouldCheck(t, lastCheckMs = t + h)) // clock moved backwards
        assertTrue(UpdatePolicy.shouldCheck(t, lastCheckMs = t - 1, manual = true))
    }

    @Test fun notifyOncePerVersion() {
        val m = manifest("1.3.0")
        val available = UpdateStatus.Available(m, skipped = false)
        assertTrue(UpdatePolicy.shouldNotify(available, notifiedCode = 0, quiet = false))
        assertFalse(UpdatePolicy.shouldNotify(available, notifiedCode = m.versionCode, quiet = false))
        assertFalse(UpdatePolicy.shouldNotify(available, notifiedCode = 0, quiet = true))
        assertFalse(UpdatePolicy.shouldNotify(UpdateStatus.Available(m, skipped = true), notifiedCode = 0, quiet = false))
        assertFalse(UpdatePolicy.shouldNotify(UpdateStatus.UpToDate, notifiedCode = 0, quiet = false))
        assertTrue(UpdatePolicy.shouldNotify(UpdateStatus.Required(m), notifiedCode = 0, quiet = false))
    }

    @Test fun quietHours() {
        assertFalse(isQuietHour(false, 22, 7, 23))
        assertTrue(isQuietHour(true, 22, 7, 23)); assertTrue(isQuietHour(true, 22, 7, 0)); assertTrue(isQuietHour(true, 22, 7, 6))
        assertFalse(isQuietHour(true, 22, 7, 7)); assertFalse(isQuietHour(true, 22, 7, 12))
        assertTrue(isQuietHour(true, 1, 5, 3)); assertFalse(isQuietHour(true, 1, 5, 5))
    }

    @Test fun hostAllowlist() {
        assertTrue(UpdateHosts.isAllowed("https://github.com/DevxD98/sessionsense/releases/latest/download/update.json"))
        assertTrue(UpdateHosts.isAllowed("https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sig=x"))
        assertTrue(UpdateHosts.isAllowed("https://objects.githubusercontent.com/a/b"))
        assertTrue(UpdateHosts.isAllowed("HTTPS://GitHub.com/a"))
        assertFalse(UpdateHosts.isAllowed("http://github.com/a"))
        assertFalse(UpdateHosts.isAllowed("https://raw.githubusercontent.com/a"))
        assertFalse(UpdateHosts.isAllowed("https://githubusercontent.com.evil.io/a"))
    }
}
