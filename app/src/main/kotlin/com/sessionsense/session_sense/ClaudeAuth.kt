package com.sessionsense.session_sense

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * The login WebView's user agent. [UsagePollingService] sends the same string, because chatgpt.com's Cloudflare
 * clearance cookie is only honoured for the user agent it was issued to.
 */
internal const val WEB_USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"

/**
 * Fallback channel for WebViews without WEB_MESSAGE_LISTENER. Unlike the listener it is visible to every page and
 * frame, so [WebLogin] only acts on its messages while the WebView is on the provider's host.
 */
private class AuthBridge(private val onMessage: (String) -> Unit) {
    @JavascriptInterface fun postMessage(message: String) = onMessage(message)
}

private const val BRIDGE = "SessionSenseAuth"

/** Runs [js] with `send(type, value)`, which posts `{t, v}` to the app over [BRIDGE]. */
private fun withBridge(js: String) = """
    (function () {
      function send(t, v) { $BRIDGE.postMessage(JSON.stringify({t: t, v: v === undefined ? '' : String(v)})); }
    $js
    })();
""".trimIndent()

/**
 * claude.ai login in a WebView. [freshLogin] clears the WebView's cookies first so a different account can sign in;
 * the sessions already stored for other accounts are unaffected (their keys are kept in [CredentialStore]).
 * [onConnected] returns an error message to show, or null on success.
 */
@Composable fun ClaudeAuth(onConnected: (AccountLogin) -> String?, onClose: () -> Unit, freshLogin: Boolean = false) = WebLogin(
    title = if (freshLogin) "Add a Claude account" else "Log into Claude",
    hint = "Log into claude.ai above.",
    startUrl = "https://claude.ai/login", host = "claude.ai", freshLogin = freshLogin, onClose = onClose,
    detect = """
        fetch('/api/organizations', {credentials:'include'}).then(r => r.ok ? r.json() : null)
          .then(d => { if (Array.isArray(d) && d.length) send('signedIn'); }).catch(() => {});
    """.trimIndent(),
    script = """
        fetch('/api/organizations', {credentials:'include'})
          .then(r => { if (!r.ok) throw new Error('Not logged in (' + r.status + ').'); return r.json(); })
          .then(d => fetch('/api/account', {credentials:'include'})
            .then(r => r.ok ? r.json() : null).catch(() => null)
            .then(a => send('result', JSON.stringify({orgs: Array.isArray(d) ? d : [], account: a}))))
          .catch(e => send('failed', e.toString()));
    """.trimIndent(),
) { raw ->
    val payload = JSONObject(raw)
    val orgs = payload.optJSONArray("orgs") ?: JSONArray()
    require(orgs.length() > 0) { "No Claude account found." }
    val org = orgs.getJSONObject(0)
    val orgId = org.getString("uuid")
    require(orgId.matches(Regex("[A-Za-z0-9-]{8,80}"))) { "Unexpected organisation ID." }
    val cookies = CookieManager.getInstance().getCookie("https://claude.ai").orEmpty()
    val named = cookies.split(';').map { it.trim() }.firstOrNull { it.startsWith("sessionKey=") }?.substringAfter('=')
    val fallback = cookies.split(';').map { it.trim().substringAfter('=', "") }.firstOrNull { it.startsWith("sk-ant-") }
    val key = named ?: fallback ?: error("Session cookie not found. Log out and back in above.")
    val profile = payload.optJSONObject("account")
    val email = profile?.optString("email_address")?.takeIf { it.isNotBlank() }
    val fullName = listOf("display_name", "full_name").firstNotNullOfOrNull { profile?.optString(it)?.takeIf { v -> v.isNotBlank() && v != "null" } }
    val name = fullName?.substringBefore(' ') ?: email?.substringBefore('@') ?: org.optString("name").takeIf { it.isNotBlank() } ?: "Claude"
    onConnected(AccountLogin(key, orgId, name, email))
}

@OptIn(ExperimentalMaterial3Api::class)
// Lint misses annotations on this Kotlin bridge; javap confirms postMessage carries RuntimeVisible @JavascriptInterface.
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
/**
 * Shared sign-in scaffold: the provider's own site in a WebView, and a Connect button that runs [script] there.
 * The script reports back with `send('result', json)` / `send('failed', message)`; [onPayload] runs on the main thread
 * only while the WebView is still on [host], and returns an error to show or null.
 * [detect] runs every few seconds while the WebView is on [host] and calls `send('signedIn')` once the login has gone
 * through; that connects automatically, so the button is only a fallback for when detection can't tell.
 *
 * Messages are only accepted from the main frame of https://[host]: the login also loads Google and Apple sign-in pages
 * and third-party frames, and none of them may drive the connect.
 */
@Composable internal fun WebLogin(title: String, hint: String, startUrl: String, host: String, freshLogin: Boolean, detect: String, script: String,
                                  onClose: () -> Unit, note: String? = null, onPayload: (String) -> String?) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var signedIn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun connect() { working = true; error = null; webView?.evaluateJavascript(withBridge(script), null) }

    /** A message from the page, on the main thread. Ignored unless the WebView is on [host]. */
    fun receive(message: String) {
        val view = webView ?: return
        if (android.net.Uri.parse(view.url.orEmpty()).host != host) return
        val m = runCatching { JSONObject(message) }.getOrNull() ?: return
        val value = m.optString("v")
        when (m.optString("t")) {
            "result" -> runCatching { onPayload(value)?.let { error = it; working = false } }
                .onFailure { error = it.message ?: "Could not connect to $host."; working = false }
            "failed" -> { error = value; working = false }
            // Once only: if the automatic connect fails, the error stays up and the button retries.
            "signedIn" -> if (!signedIn) { signedIn = true; if (!working) connect() }
        }
    }

    LaunchedEffect(Unit) {
        while (!signedIn) {
            delay(2_500)
            val view = webView ?: continue
            if (!loading && !working && android.net.Uri.parse(view.url.orEmpty()).host == host) view.evaluateJavascript(withBridge(detect), null)
        }
    }

    Scaffold(
        containerColor = Bg,
        topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onClick = onClose) { Text("Close", color = Teal) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = Text)) },
        bottomBar = {
            // Above the navigation bar (the app is edge-to-edge), and opaque so the page never shows through underneath.
            Column(Modifier.fillMaxWidth().background(Bg).navigationBarsPadding().padding(16.dp, 10.dp, 16.dp, 12.dp)) {
                error?.let { Text(it, color = Coral, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(8.dp)) }
                if (signedIn) Text("Signed in ✓", color = Teal, style = MaterialTheme.typography.bodySmall)
                else Text("$hint SessionSense connects as soon as you're signed in.", color = Muted, style = MaterialTheme.typography.bodySmall)
                note?.let { Spacer(Modifier.height(4.dp)); Text(it, color = Amber, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(10.dp))
                CapsuleButton(when { working -> "Connecting…"; signedIn -> "Connect →"; else -> "I've logged in — Connect →" }, onClick = ::connect,
                    style = if (signedIn) CapsuleStyle.Primary else CapsuleStyle.Secondary, enabled = !working, haptic = HapticFeedbackType.Confirm)
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AndroidView(factory = { context ->
                WebView(context).apply {
                    webView = this
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                    settings.userAgentString = WEB_USER_AGENT
                    CookieManager.getInstance().setAcceptCookie(true)
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                        // Injected into https://host frames only; the main-frame check keeps out frames that host embeds.
                        WebViewCompat.addWebMessageListener(this, BRIDGE, setOf("https://$host")) { _, message, origin, isMainFrame, _ ->
                            if (isMainFrame && origin.host == host) message.data?.let(::receive)
                        }
                    } else addJavascriptInterface(AuthBridge { message -> post { receive(message) } }, BRIDGE)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { loading = true }
                        override fun onPageFinished(view: WebView?, url: String?) { loading = false }
                    }
                    if (freshLogin) CookieManager.getInstance().removeAllCookies { loadUrl(startUrl) }
                    else loadUrl(startUrl)
                }
            }, modifier = Modifier.fillMaxSize())
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }
}
