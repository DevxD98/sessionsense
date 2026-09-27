package com.sessionsense.session_sense

import android.util.Log
import android.webkit.CookieManager
import androidx.compose.runtime.Composable
import org.json.JSONObject
import org.json.JSONTokener

/**
 * chatgpt.com login for tracking Codex limits. Connect reads the access token from /api/auth/session and makes one
 * wham/usage call with it, so an account whose plan has no Codex limits is rejected here rather than on the first poll.
 * The whole chatgpt.com cookie jar is kept (encrypted) so [UsagePollingService] can mint new access tokens later.
 */
@Composable fun CodexAuth(onConnected: (AccountLogin) -> String?, onClose: () -> Unit, freshLogin: Boolean = false) = WebLogin(
    title = if (freshLogin) "Add a ChatGPT account" else "Log into ChatGPT",
    hint = "Log into chatgpt.com above with the account you use for Codex, then connect.",
    startUrl = "https://chatgpt.com/auth/login", host = "chatgpt.com", freshLogin = freshLogin, onClose = onClose,
    script = """
        (async () => {
          try {
            const r = await fetch('/api/auth/session', {credentials: 'include'});
            const text = await r.text();
            let s = null; try { s = JSON.parse(text); } catch (e) {}
            if (!r.ok || !s || !s.accessToken) throw new Error('Not signed in yet (' + r.status + '). Finish logging in above.');
            let id = null;
            try {
              const claims = JSON.parse(atob(s.accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
              id = (claims['https://api.openai.com/auth'] || {}).chatgpt_account_id || null;
            } catch (e) {}
            if (!id && s.account) id = s.account.id || null;
            const headers = {Authorization: 'Bearer ' + s.accessToken, Accept: 'application/json'};
            if (id) headers['ChatGPT-Account-Id'] = id;
            const u = await fetch('/backend-api/wham/usage', {headers, credentials: 'include'});
            SessionSenseAuth.result(JSON.stringify({session: text, usageStatus: u.status, usage: await u.text()}));
          } catch (e) { SessionSenseAuth.failed(e.message || String(e)); }
        })();
    """.trimIndent(),
) { raw ->
    val payload = JSONObject(raw)
    val sessionBody = payload.optString("session")
    val session = CodexAuthParser.session(sessionBody) ?: error("Not signed in yet. Finish logging in above.")
    val accountId = session.accountId ?: error("Couldn't find your ChatGPT account ID.")
    val status = payload.optInt("usageStatus")
    val usageBody = payload.optString("usage")
    // Shapes only (key names and types, never values), to check the unofficial endpoints against a live account.
    val claimId = CodexAuthParser.jwtClaims(session.accessToken)?.optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id").orEmpty()
    val sessionId = runCatching { JSONObject(sessionBody).optJSONObject("account")?.optString("id") }.getOrNull().orEmpty()
    Log.i(TAG, "auth/session shape: ${shape(sessionBody)}; account id in jwt=${claimId.isNotBlank()} session=${sessionId.isNotBlank()} same=${claimId == sessionId}")
    Log.i(TAG, "wham/usage HTTP $status shape: ${shape(usageBody)}")
    when (status) {
        200 -> Unit
        401, 403 -> error("ChatGPT refused the Codex usage request (HTTP $status). Codex limits may not be available on this plan.")
        else -> error("Couldn't read Codex usage (HTTP $status). Try again in a moment.")
    }
    val usage = CodexUsageParser.parse(usageBody, System.currentTimeMillis()) ?: error("Codex usage came back in an unexpected format.")
    val cookies = CookieManager.getInstance().getCookie("https://chatgpt.com").orEmpty()
    require(cookies.isNotBlank()) { "Session cookie not found. Log out and back in above." }
    if ("session-token" !in cookies) Log.w(TAG, "No next-auth session-token cookie; background token refresh will not work.")
    val email = session.email
    val name = session.name?.substringBefore(' ') ?: email?.substringBefore('@') ?: usage.planType.ifBlank { null }?.let { "ChatGPT ${it.replaceFirstChar(Char::uppercaseChar)}" } ?: "ChatGPT"
    onConnected(AccountLogin(cookies, accountId, name, email, Provider.CODEX, session.accessToken, session.tokenExpMs))
}

private const val TAG = "SessionSense"

/** `{"a":{"b":num,"c":str}}` → `{a:{b:num,c:str}}`: structure for diagnostics with every value stripped. */
internal fun shape(body: String): String = runCatching {
    fun of(v: Any?, d: Int): String = when (v) {
        is JSONObject -> if (d > 3) "{…}" else v.keys().asSequence().joinToString(",", "{", "}") { k -> "$k:${of(v.opt(k), d + 1)}" }
        is org.json.JSONArray -> "[${v.length()}]"
        is Number -> "num"; is Boolean -> "bool"; is String -> "str"; JSONObject.NULL -> "null"; else -> "?"
    }
    of(JSONTokener(body).nextValue(), 0)
}.getOrDefault("<not json, ${body.length} chars>")
