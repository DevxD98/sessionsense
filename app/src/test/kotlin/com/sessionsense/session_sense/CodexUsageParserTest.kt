package com.sessionsense.session_sense

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CodexUsageParserTest {
    private val now = 1_790_520_000_000L
    private fun fixture(name: String) = javaClass.getResource("/codex/$name.json")!!.readText()
    private fun parse(name: String) = CodexUsageParser.parse(fixture(name), now)!!

    @Test fun snakeCase() {
        val s = parse("snake_case")
        assertEquals(42, s.sessionPct); assertEquals(15, s.weeklyPct)
        assertEquals(1_790_532_514_000L, s.sessionResetMs); assertEquals(1_791_119_314_000L, s.weeklyResetMs)
        assertEquals("plus", s.planType); assertTrue(s.sessionWindow)
        assertEquals(0, s.opusPct); assertEquals(0, s.sonnetPct)
        assertEquals(now, s.lastUpdatedMs); assertEquals("connected", s.connection)
    }

    @Test fun camelCaseWithNumericStrings() {
        val s = parse("camel_case")
        assertEquals(73, s.sessionPct); assertEquals(8, s.weeklyPct)
        assertEquals(1_790_532_514_000L, s.sessionResetMs); assertEquals("pro", s.planType)
    }

    @Test fun oldResetAndWindowFieldNames() {
        // The Codex CLI's rate_limits shape: primary/secondary, resets_at, window_minutes.
        val s = parse("old_reset_names")
        assertEquals(96, s.sessionPct); assertEquals(15, s.weeklyPct)
        assertEquals(1_790_532_514_000L, s.sessionResetMs); assertEquals(1_791_119_314_000L, s.weeklyResetMs)
    }

    @Test fun resetAfterSecondsIsRelativeToNow() {
        val s = parse("reset_after_only")
        assertEquals(now + 1_800_000L, s.sessionResetMs); assertEquals(now + 86_400_000L, s.weeklyResetMs)
    }

    @Test fun primaryWindowMissing() {
        val s = parse("primary_missing")
        assertFalse(s.sessionWindow)
        assertEquals(0, s.sessionPct); assertEquals(0L, s.sessionResetMs)
        assertEquals(55, s.weeklyPct); assertEquals(1_791_119_314_000L, s.weeklyResetMs)
    }

    @Test fun loneWeeklyWindowSentAsPrimaryIsClassifiedByLength() {
        val s = parse("weekly_as_primary")
        assertFalse(s.sessionWindow)
        assertEquals(30, s.weeklyPct); assertEquals(0, s.sessionPct)
    }

    @Test fun garbageValuesDegradeToMissing() {
        val s = parse("garbage_values")
        assertEquals(0, s.sessionPct); assertEquals(0L, s.sessionResetMs)
        assertEquals(100, s.weeklyPct) // 250 clamps
        assertEquals(0L, s.weeklyResetMs) // -5 is not an epoch and "NaN" is not finite
        assertEquals("", s.planType)
    }

    @Test fun limitReachedOverridesAZeroReading() {
        val s = parse("limit_reached_zero")
        assertEquals(100, s.sessionPct); assertEquals(0, s.weeklyPct)
    }

    @Test fun isoAndMillisecondResetsAndFractionalUsage() {
        val s = parse("iso_and_millis")
        assertEquals(1, s.sessionPct) // 0.3% is still a started session
        assertEquals(0, s.weeklyPct)
        assertEquals(1_790_530_114_000L, s.sessionResetMs)
        assertEquals(1_791_119_314_000L, s.weeklyResetMs)
    }

    @Test fun nonObjectBodiesAreRejected() {
        assertNull(CodexUsageParser.parse("<html>Just a moment…</html>", now))
        assertNull(CodexUsageParser.parse("[1,2]", now))
        assertNull(CodexUsageParser.parse("", now))
        val empty = CodexUsageParser.parse("{}", now)!!
        assertFalse(empty.sessionWindow); assertEquals(0, empty.weeklyPct)
    }
}

class CodexAuthParserTest {
    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
    private fun jwt(payload: String) = "${b64("""{"alg":"RS256"}""")}.${b64(payload)}.sig"

    @Test fun accountIdAndExpiryComeFromTheAccessTokenClaims() {
        val token = jwt("""{"exp":1791000000,"https://api.openai.com/auth":{"chatgpt_account_id":"acc-123","chatgpt_plan_type":"plus"},"https://api.openai.com/profile":{"email":"a@b.co"}}""")
        val s = CodexAuthParser.session("""{"user":{"name":"Dev Mondal","email":"dev@example.com"},"expires":"2026-12-01T00:00:00Z","accessToken":"$token"}""")!!
        assertEquals("acc-123", s.accountId); assertEquals(1_791_000_000_000L, s.tokenExpMs)
        assertEquals("Dev Mondal", s.name); assertEquals("dev@example.com", s.email); assertEquals("plus", s.planType)
        assertEquals(token, s.accessToken)
    }

    @Test fun accountIdFallsBackToTheSessionJson() {
        val s = CodexAuthParser.session("""{"account":{"id":"acc-9","planType":"team"},"accessToken":"${jwt("""{"exp":1}""")}"}""")!!
        assertEquals("acc-9", s.accountId); assertEquals("team", s.planType); assertNull(s.email)
    }

    @Test fun signedOutOrUnreadableSessions() {
        assertNull(CodexAuthParser.session("{}"))
        assertNull(CodexAuthParser.session("<html></html>"))
        val opaque = CodexAuthParser.session("""{"accessToken":"not-a-jwt"}""")!!
        assertNull(opaque.accountId); assertEquals(0L, opaque.tokenExpMs)
    }

    @Test fun rotatedCookiesReplaceStoredOnesAndClearedOnesAreDropped() {
        val merged = CodexAuthParser.mergeCookies("__Secure-next-auth.session-token=old; cf_clearance=cf; __cf_bm=bm",
            listOf("__Secure-next-auth.session-token=new; Path=/; HttpOnly; Secure", "__cf_bm=; Max-Age=0", "_puid=p; Path=/"))
        assertEquals("__Secure-next-auth.session-token=new; cf_clearance=cf; _puid=p", merged)
    }
}
