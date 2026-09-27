package com.sessionsense.session_sense

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant
import java.util.Base64

/**
 * Parser for chatgpt.com/backend-api/wham/usage (the ChatGPT-plan Codex limits). The endpoint is unofficial and its
 * field names have drifted (snake/camel case, reset_at/resets_at, limit_window_seconds/window_minutes), so every
 * lookup accepts all known spellings and anything unreadable degrades to "missing" rather than failing the poll.
 */
object CodexUsageParser {
    private const val HOUR_MS = 60 * 60 * 1000L

    private class Window(val pct: Int?, val resetMs: Long, val lengthMs: Long)

    /** Returns null only when [body] isn't a JSON object at all; a response without windows maps to an empty snapshot. */
    fun parse(body: String, now: Long): UsageSnapshot? {
        val root = runCatching { JSONTokener(body).nextValue() as? JSONObject }.getOrNull() ?: return null
        val limits = listOf("rate_limit", "rate_limits").firstNotNullOfOrNull { root.field(it) as? JSONObject }
        val primary = limits?.let { l -> listOf("primary_window", "primary").firstNotNullOfOrNull { l.field(it) as? JSONObject } }?.let { window(it, now) }
        val secondary = limits?.let { l -> listOf("secondary_window", "secondary").firstNotNullOfOrNull { l.field(it) as? JSONObject } }?.let { window(it, now) }

        // Classify by window length when it is reported (a lone weekly window has come back as the primary one);
        // otherwise, or if both would land in the same slot, fall back to position.
        fun isSession(w: Window) = w.lengthMs in 1..12 * HOUR_MS
        fun isWeekly(w: Window) = w.lengthMs >= 24 * HOUR_MS
        val session = primary?.takeUnless(::isWeekly) ?: secondary?.takeIf(::isSession)
        val weekly = secondary?.takeUnless(::isSession) ?: primary?.takeIf(::isWeekly)

        var sessionPct = session?.pct ?: 0
        var weeklyPct = weekly?.pct ?: 0
        // The endpoint has reported 0% while a limit was exhausted; trust the explicit flag over the percentages.
        val reached = limits?.let { bool(it.field("limit_reached")) == true || bool(it.field("allowed")) == false } == true
        if (reached && sessionPct < 100 && weeklyPct < 100) {
            if (session != null && (weekly == null || sessionPct >= weeklyPct)) sessionPct = 100 else if (weekly != null) weeklyPct = 100
        }
        val plan = (root.field("plan_type") as? String)?.trim().orEmpty()
        return UsageSnapshot(sessionPct = sessionPct, weeklyPct = weeklyPct, sessionResetMs = session?.resetMs ?: 0,
            weeklyResetMs = weekly?.resetMs ?: 0, lastUpdatedMs = now, connection = "connected", planType = plan, sessionWindow = session != null)
    }

    private fun window(o: JSONObject, now: Long): Window {
        val pct = listOf("used_percent", "used_pct", "utilization").firstNotNullOfOrNull { number(o.field(it)) }
            ?.let { if (it > 0 && it < 1) 1 else it.toInt().coerceIn(0, 100) } // any real usage (<1%) still starts a session
        val reset = listOf("reset_at", "resets_at").firstNotNullOfOrNull { epochMs(o.field(it)) }
            ?: listOf("reset_after_seconds", "resets_after_seconds", "reset_in_seconds", "resets_in_seconds")
                .firstNotNullOfOrNull { number(o.field(it)) }?.takeIf { it >= 0 }?.let { now + (it * 1000).toLong() }
            ?: 0L
        val length = number(o.field("limit_window_seconds"))?.let { it * 1000 }
            ?: number(o.field("window_seconds"))?.let { it * 1000 }
            ?: number(o.field("window_minutes"))?.let { it * 60_000 }
        return Window(pct, reset, length?.takeIf { it > 0 }?.toLong() ?: 0L)
    }

    /** Epoch seconds, epoch milliseconds or an ISO-8601 instant; anything before 2001 is treated as garbage. */
    private fun epochMs(v: Any?): Long? {
        (v as? String)?.trim()?.let { s -> runCatching { return Instant.parse(s).toEpochMilli() } }
        val n = number(v) ?: return null
        val ms = if (n >= 1e12) n.toLong() else (n * 1000).toLong()
        return ms.takeIf { it >= 1_000_000_000_000L }
    }
}

/** What a chatgpt.com login yields, plus helpers the service needs to keep the access token fresh without the WebView. */
object CodexAuthParser {
    private const val AUTH_CLAIM = "https://api.openai.com/auth"
    private const val PROFILE_CLAIM = "https://api.openai.com/profile"

    data class Session(val accessToken: String, val tokenExpMs: Long, val accountId: String?, val name: String?, val email: String?, val planType: String?)

    /** Parses the JSON from chatgpt.com/api/auth/session. Null when signed out (no accessToken) or not JSON. */
    fun session(body: String): Session? {
        val root = runCatching { JSONTokener(body).nextValue() as? JSONObject }.getOrNull() ?: return null
        val token = (root.field("access_token") as? String)?.takeIf { it.isNotBlank() } ?: return null
        val claims = jwtClaims(token)
        val auth = claims?.optJSONObject(AUTH_CLAIM)
        val user = root.optJSONObject("user")
        val accountId = (auth?.field("chatgpt_account_id") as? String)?.takeIf { it.isNotBlank() }
            ?: (root.optJSONObject("account")?.field("id") as? String)?.takeIf { it.isNotBlank() }
            ?: (root.optJSONObject("account")?.field("account_id") as? String)?.takeIf { it.isNotBlank() }
        val email = (user?.optString("email") ?: claims?.optJSONObject(PROFILE_CLAIM)?.optString("email"))?.takeIf { it.isNotBlank() && it != "null" }
        val name = user?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
        val plan = (auth?.field("chatgpt_plan_type") as? String ?: root.optJSONObject("account")?.field("plan_type") as? String)?.takeIf { it.isNotBlank() }
        val exp = number(claims?.opt("exp"))?.let { (it * 1000).toLong() } ?: 0L
        return Session(token, exp, accountId, name, email, plan)
    }

    /** The payload of a JWT, without verifying it (it is only read to learn the account id and expiry). */
    fun jwtClaims(token: String): JSONObject? = runCatching {
        val part = token.split('.').getOrNull(1) ?: return null
        JSONObject(String(Base64.getUrlDecoder().decode(part.trimEnd('=')), Charsets.UTF_8))
    }.getOrNull()

    /**
     * Applies Set-Cookie headers to a stored "a=1; b=2" Cookie header, so a rotated session token is kept.
     * A cookie set to an empty value or with Max-Age=0 is dropped.
     */
    fun mergeCookies(stored: String, setCookies: List<String>): String {
        val jar = LinkedHashMap<String, String>()
        stored.split(';').map { it.trim() }.filter { '=' in it }.forEach { jar[it.substringBefore('=')] = it.substringAfter('=') }
        setCookies.forEach { header ->
            val parts = header.split(';').map { it.trim() }
            val pair = parts.firstOrNull()?.takeIf { '=' in it } ?: return@forEach
            val name = pair.substringBefore('='); val value = pair.substringAfter('=')
            val expired = parts.drop(1).any { it.equals("Max-Age=0", ignoreCase = true) }
            if (value.isEmpty() || expired) jar.remove(name) else jar[name] = value
        }
        return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}

/** Looks a key up by its snake_case name and its camelCase twin, treating JSON null as absent. */
internal fun JSONObject.field(snake: String): Any? {
    val camel = snake.split('_').mapIndexed { i, p -> if (i == 0) p else p.replaceFirstChar(Char::uppercaseChar) }.joinToString("")
    return listOf(snake, camel).firstNotNullOfOrNull { key -> opt(key)?.takeUnless { it == JSONObject.NULL || it is JSONArray } }
}

internal fun number(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is String -> v.trim().removeSuffix("%").toDoubleOrNull()
    else -> null
}?.takeIf { it.isFinite() }

private fun bool(v: Any?): Boolean? = when (v) {
    is Boolean -> v
    is String -> v.trim().lowercase().toBooleanStrictOrNull()
    else -> null
}
