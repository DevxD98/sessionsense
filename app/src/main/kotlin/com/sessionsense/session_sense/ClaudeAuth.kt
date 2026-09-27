package com.sessionsense.session_sense

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

private class AuthBridge(
    private val onOrganizations: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    @JavascriptInterface fun organizations(raw: String) = onOrganizations(raw)
    @JavascriptInterface fun failed(message: String) = onFailure(message)
}

@OptIn(ExperimentalMaterial3Api::class)
// Lint misses annotations on this Kotlin bridge; javap confirms both methods carry RuntimeVisible @JavascriptInterface.
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
/**
 * claude.ai login in a WebView. [freshLogin] clears the WebView's cookies first so a different account can sign in;
 * the sessions already stored for other accounts are unaffected (their keys are kept in [CredentialStore]).
 * [onConnected] returns an error message to show, or null on success.
 */
@Composable fun ClaudeAuth(onConnected: (ClaudeLogin) -> String?, onClose: () -> Unit, freshLogin: Boolean = false) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val bridge = remember { AuthBridge(onOrganizations = { raw ->
        val view = webView ?: return@AuthBridge
        view.post {
            runCatching {
                require(android.net.Uri.parse(view.url).host == "claude.ai") { "Return to claude.ai before connecting." }
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
                onConnected(ClaudeLogin(key, orgId, name, email))?.let { message -> error = message; working = false }
            }.onFailure { error = it.message ?: "Could not connect to claude.ai."; working = false }
        }
    }, onFailure = { message -> webView?.post { error = message; working = false } }) }

    Scaffold(
        containerColor = Bg,
        topBar = { TopAppBar(title = { Text(if (freshLogin) "Add a Claude account" else "Log into Claude") }, navigationIcon = { TextButton(onClick = onClose) { Text("Close", color = Teal) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = Text)) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(16.dp, 10.dp, 16.dp, 28.dp)) {
                error?.let { Text(it, color = Coral, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(8.dp)) }
                Text("Log into claude.ai above, then connect.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                CapsuleButton(if (working) "Connecting…" else "I've logged in — Connect →", onClick = {
                    working = true; error = null
                    webView?.evaluateJavascript("""
                        fetch('/api/organizations', {credentials:'include'})
                          .then(r => { if (!r.ok) throw new Error('Not logged in (' + r.status + ').'); return r.json(); })
                          .then(d => fetch('/api/account', {credentials:'include'})
                            .then(r => r.ok ? r.json() : null).catch(() => null)
                            .then(a => SessionSenseAuth.organizations(JSON.stringify({orgs: Array.isArray(d) ? d : [], account: a}))))
                          .catch(e => SessionSenseAuth.failed(e.toString()));
                    """.trimIndent(), null)
                }, enabled = !working, haptic = HapticFeedbackType.Confirm)
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AndroidView(factory = { context ->
                WebView(context).apply {
                    webView = this
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"
                    CookieManager.getInstance().setAcceptCookie(true)
                    addJavascriptInterface(bridge, "SessionSenseAuth")
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { loading = true }
                        override fun onPageFinished(view: WebView?, url: String?) { loading = false }
                    }
                    if (freshLogin) CookieManager.getInstance().removeAllCookies { loadUrl("https://claude.ai/login") }
                    else loadUrl("https://claude.ai/login")
                }
            }, modifier = Modifier.fillMaxSize())
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    DisposableEffect(Unit) { onDispose { webView?.removeJavascriptInterface("SessionSenseAuth"); webView?.destroy() } }
}
