package com.sessionsense.session_sense

import android.app.*
import android.content.*
import android.graphics.Color
import android.os.*
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class UsagePollingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private lateinit var app: SessionSenseApp
    private lateinit var notifications: NotificationCenter

    override fun onCreate() {
        super.onCreate()
        app = application as SessionSenseApp
        notifications = NotificationCenter(this)
        // startForeground must be called now. Post the last stored usage, not a blank placeholder: a placeholder replaced by
        // the real content on the first poll is two posts, and each one flashes the Glyph.
        val (snapshot, label) = runBlocking { withTimeoutOrNull(500) { app.repository.snapshot() to activeLabel() } } ?: (UsageSnapshot() to null)
        glyphLights = runBlocking { withTimeoutOrNull(500) { app.repository.settings.first().glyphLights } } ?: true
        showOngoing(snapshot, label, quiet = false, force = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.coroutineContext.cancelChildren()
        scope.launch {
            // The Glyph setting changed: post once now so the progress bar appears or disappears without waiting for a change.
            if (intent?.getBooleanExtra(EXTRA_REPOST, false) == true) {
                glyphLights = app.repository.settings.first().glyphLights
                showOngoing(app.repository.snapshot(), activeLabel(), quiet = false, force = true)
            }
            while (isActive) {
                // An unexpected failure skips one poll; left uncaught it would crash the whole app.
                try { pollAll() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "Poll failed", e) }
                delay(5_000)
            }
        }
        return START_STICKY
    }

    private val lastPolled = mutableMapOf<String, Long>()
    private var lastActiveId: String? = null

    /** Every connected account keeps its own history; background accounts are polled less often. */
    private suspend fun pollAll() {
        val all = app.credentials.accounts.value
        val accounts = all.filter { it.connected }
        if (accounts.isEmpty()) return stopSelf()
        val activeId = app.repository.activeId()
        // A newly viewed account is polled straight away, whatever its cadence.
        val switched = activeId != lastActiveId; lastActiveId = activeId
        val now = System.currentTimeMillis()
        accounts.forEach { account ->
            val active = account.id == activeId
            if ((active && switched) || now - (lastPolled[account.id] ?: 0) >= interval(account, active)) {
                lastPolled[account.id] = now
                val label = label(account, all)
                when (account.provider) {
                    Provider.CLAUDE -> poll(account, active, label)
                    Provider.CODEX -> pollCodex(account, active, label)
                }
            }
        }
    }

    // wham/usage is unofficial and shared with the Codex CLI and IDE, so it is polled far less often than claude.ai.
    private fun interval(account: Account, active: Boolean) = when (account.provider) {
        Provider.CLAUDE -> if (active) 0L else BACKGROUND_POLL_MS
        Provider.CODEX -> if (active) CODEX_ACTIVE_POLL_MS else CODEX_BACKGROUND_POLL_MS
    }

    /** How alerts and the live notification name an account: by provider once both are tracked, by name when a provider has several. */
    private fun label(account: Account, all: List<Account>): String? {
        val mixed = all.map { it.provider }.distinct().size > 1
        val siblings = all.count { it.provider == account.provider } > 1
        return when {
            mixed && siblings -> "${account.provider.label} · ${account.name}"
            mixed -> account.provider.label
            siblings -> account.name
            else -> null
        }
    }

    private suspend fun activeLabel(): String? {
        val all = app.credentials.accounts.value
        val id = app.repository.activeId()
        return all.firstOrNull { it.id == id }?.let { label(it, all) }
    }

    private val profileChecked = mutableSetOf<String>()

    /** Accounts migrated from the single-account store have no name/email yet; fill them in once from claude.ai. */
    private fun backfillProfile(account: Account) {
        if (account.email != null || !profileChecked.add(account.id)) return
        runCatching {
            client.newCall(authed(account, "https://claude.ai/api/account")).execute().use { response ->
                if (response.code != 200) return
                val json = JSONObject(response.body?.string() ?: return)
                val email = json.optString("email_address").takeIf { it.isNotBlank() }
                val full = listOf("display_name", "full_name").firstNotNullOfOrNull { json.optString(it).takeIf { v -> v.isNotBlank() && v != "null" } }
                val name = full?.substringBefore(' ') ?: email?.substringBefore('@') ?: return
                app.credentials.updateProfile(account.id, name, email)
            }
        }
    }

    private fun authed(account: Account, url: String) = Request.Builder().url(url)
        .header("Cookie", "sessionKey=${account.sessionKey}")
        .header("Accept", "application/json")
        .header("User-Agent", WEB_USER_AGENT)
        .header("Referer", "https://claude.ai")
        .header("Origin", "https://claude.ai")
        .build()

    private suspend fun poll(account: Account, active: Boolean, label: String?) {
        val k = AccountKeys(account.id)
        backfillProfile(account)
        val request = authed(account, "https://claude.ai/api/organizations/${account.orgId}/usage")
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> response.body?.string()?.let { persist(account, UsageParser.parse(it, System.currentTimeMillis()), active, label) }
                    401, 403 -> expire(account, active, label)
                    else -> offline(k)
                }
            }
        } catch (_: Exception) {
            offline(k)
        }
    }

    private suspend fun expire(account: Account, active: Boolean, label: String?) {
        app.credentials.markExpired(account.id)
        applicationContext.dataStore.edit { it[AccountKeys(account.id).CONNECTION] = "expired" }
        if (active) refreshActiveSurfaces(account, label)
    }

    private suspend fun offline(k: AccountKeys) = applicationContext.dataStore.edit { it[k.CONNECTION] = "offline" }

    // ─── Codex (chatgpt.com) ───────────────────────────────────────────────────────────────────────────

    private val lastRefreshAttempt = mutableMapOf<String, Long>()

    private sealed interface Refresh {
        data class Ok(val account: Account) : Refresh
        /** chatgpt.com answered with a signed-out session: the stored cookies are dead. */
        data object SignedOut : Refresh
        /** Network error or a Cloudflare challenge page; the current token may still work. */
        data object Unavailable : Refresh
    }

    private suspend fun pollCodex(account: Account, active: Boolean, label: String?) {
        val k = AccountKeys(account.id)
        var current = account
        var refreshed = false
        suspend fun refresh(): Boolean = when (val r = refreshCodexToken(current)) {
            is Refresh.Ok -> { current = r.account; refreshed = true; true }
            Refresh.SignedOut -> { expire(account, active, label); false }
            Refresh.Unavailable -> true
        }
        try {
            val now = System.currentTimeMillis()
            val expiring = current.accessToken.isEmpty() || (current.tokenExpMs > 0 && current.tokenExpMs - now < CODEX_REFRESH_MARGIN_MS)
            if (expiring && now - (lastRefreshAttempt[account.id] ?: 0) >= CODEX_REFRESH_RETRY_MS && !refresh()) return
            var (code, body, json) = whamUsage(current)
            if (code != 200) Log.w(TAG, "Codex usage: HTTP $code${if (json) "" else " (not JSON)"}")
            // An access token can be revoked before its exp; mint a new one once and retry.
            if (code == 401 && !refreshed) { if (!refresh()) return; if (refreshed) whamUsage(current).let { code = it.first; body = it.second; json = it.third } }
            when {
                code == 200 -> {
                    CodexUsageParser.parse(body, System.currentTimeMillis())?.let { persist(account, it, active, label) } ?: offline(k)
                    refreshCodexAnalytics(current)
                }
                // A Cloudflare challenge is also a 403, but an HTML one; only an API rejection means the login is gone.
                code == 401 || (code == 403 && json) -> expire(account, active, label)
                else -> offline(k)
            }
        } catch (_: Exception) {
            offline(k)
        }
    }

    /**
     * Per-model, per-surface and message analytics from chatgpt.com's Analytics page. These are daily totals from an
     * unofficial API, so they're fetched at most every 30 minutes (the last fetch time is stored, so restarts don't refetch).
     * Any failure keeps the last good summary.
     */
    private suspend fun refreshCodexAnalytics(account: Account) {
        val k = AccountKeys(account.id)
        val now = System.currentTimeMillis()
        val last = app.repository.preferences()[k.CODEX_ANALYTICS]?.let(CodexAnalytics::fromJson)?.fetchedAtMs ?: 0L
        if (now - maxOf(last, lastAnalyticsAttempt[account.id] ?: 0L) < CODEX_ANALYTICS_MS) return
        Log.i(TAG, "Codex analytics: fetching")
        lastAnalyticsAttempt[account.id] = now
        val end = LocalDate.now(); val range = "start_date=${end.minusDays(6)}&end_date=$end&group_by=day"
        fun get(path: String) = runCatching {
            client.newCall(wham(account, "https://chatgpt.com/backend-api/wham/analytics/$path")).execute().use {
                if (it.code != 200) Log.w(TAG, "Codex analytics ${path.substringBefore('?')}: HTTP ${it.code}")
                if (it.code == 200) it.body?.string().orEmpty() else ""
            }
        }.onFailure { Log.w(TAG, "Codex analytics ${path.substringBefore('?')}: ${it.javaClass.simpleName}") }.getOrDefault("")
        val analytics = CodexAnalyticsParser.parse(get("daily-token-usage-breakdown?$range"), get("daily-workspace-usage-counts?$range&workspace_user=true"), now)
            ?: return Log.w(TAG, "Codex analytics: nothing usable in either response").let { }
        applicationContext.dataStore.edit { it[k.CODEX_ANALYTICS] = analytics.toJson() }
    }

    private val lastAnalyticsAttempt = mutableMapOf<String, Long>()

    private fun wham(account: Account, url: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer ${account.accessToken}")
        .header("ChatGPT-Account-Id", account.orgId)
        .header("Accept", "application/json")
        .header("User-Agent", WEB_USER_AGENT)
        .header("Referer", "https://chatgpt.com/")
        .build()

    private fun whamUsage(account: Account): Triple<Int, String, Boolean> = client.newCall(wham(account, CODEX_USAGE_URL)).execute().use { Triple(it.code, it.body?.string().orEmpty(), it.header("Content-Type").orEmpty().contains("json")) }

    /** Mints a new access token from the stored chatgpt.com cookies, as the website does, and keeps any rotated cookies. */
    private fun refreshCodexToken(account: Account): Refresh {
        lastRefreshAttempt[account.id] = System.currentTimeMillis()
        val request = Request.Builder().url("https://chatgpt.com/api/auth/session")
            .header("Cookie", account.sessionKey)
            .header("Accept", "application/json")
            .header("User-Agent", WEB_USER_AGENT)
            .header("Referer", "https://chatgpt.com/")
            .build()
        return try {
            client.newCall(request).execute().use { r ->
                val json = r.header("Content-Type").orEmpty().contains("json")
                if (r.code != 200 || !json) { Log.w(TAG, "Codex token refresh: HTTP ${r.code}${if (json) "" else ", not JSON (likely a Cloudflare challenge)"}"); return Refresh.Unavailable }
                val session = CodexAuthParser.session(r.body?.string().orEmpty()) ?: return Refresh.SignedOut
                val cookies = CodexAuthParser.mergeCookies(account.sessionKey, r.headers("Set-Cookie"))
                app.credentials.updateToken(account.id, cookies, session.accessToken, session.tokenExpMs)
                Refresh.Ok(account.copy(sessionKey = cookies, accessToken = session.accessToken, tokenExpMs = session.tokenExpMs))
            }
        } catch (_: Exception) {
            Refresh.Unavailable
        }
    }

    private suspend fun persist(account: Account, reported: UsageSnapshot, active: Boolean, label: String?) {
        val k = AccountKeys(account.id)
        val old = app.repository.preferences()
        // Pin each window's reset time: alert keys, stored flags and the displayed time must not move with API jitter.
        val value = reported.copy(sessionResetMs = stableReset(old[k.SESSION_RESET] ?: 0, reported.sessionResetMs),
            weeklyResetMs = stableReset(old[k.WEEKLY_RESET] ?: 0, reported.weeklyResetMs))
        val transition = SessionTransition.apply(
            TrackingState(old[k.PREV_SESSION] ?: 0, old[k.ACTIVE_START] ?: 0, old[k.ACTIVE_PEAK] ?: 0, old[k.ACTIVE_RESET] ?: 0),
            value.sessionPct, value.sessionResetMs, value.lastUpdatedMs,
        )
        val completed = transition.ended
        if (completed != null && completed.durationMs >= 60_000) app.database.sessions().insert(completed.copy(accountId = account.id))

        // The last persisted values always match the last stored sample, because every change is recorded.
        val lastSample = old[k.LAST_SAMPLE]?.let { UsageSample(ts = it, sessionPct = old[k.SESSION] ?: 0, weeklyPct = old[k.WEEKLY] ?: 0, opusPct = old[k.OPUS] ?: 0, sonnetPct = old[k.SONNET] ?: 0, accountId = account.id) }
        val sample = UsageSample(ts = value.lastUpdatedMs, sessionPct = value.sessionPct, weeklyPct = value.weeklyPct, opusPct = value.opusPct, sonnetPct = value.sonnetPct, accountId = account.id)
        val recorded = SamplePolicy.shouldRecord(lastSample, sample)
        if (recorded) {
            app.database.samples().insert(sample)
            app.database.samples().prune(value.lastUpdatedMs - SamplePolicy.RETENTION_MS)
        }

        applicationContext.dataStore.edit { p ->
            p[k.SESSION] = value.sessionPct; p[k.WEEKLY] = value.weeklyPct
            p[k.OPUS] = value.opusPct; p[k.SONNET] = value.sonnetPct
            p[k.SESSION_RESET] = value.sessionResetMs; p[k.WEEKLY_RESET] = value.weeklyResetMs
            p[k.UPDATED] = value.lastUpdatedMs; p[k.CONNECTION] = "connected"
            p[k.PLAN_TYPE] = value.planType; p[k.SESSION_WINDOW] = value.sessionWindow
            p[k.OPUS_REPORTED] = value.opusReported; p[k.SONNET_REPORTED] = value.sonnetReported
            p[k.PREV_SESSION] = transition.state.previousPct
            p[k.ACTIVE_START] = transition.state.activeStartMs
            p[k.ACTIVE_PEAK] = transition.state.peakPct
            p[k.ACTIVE_RESET] = transition.state.resetMs
            if (recorded) p[k.LAST_SAMPLE] = sample.ts
        }
        // Alerts and the live notification belong to the account the user is viewing only; every account has a widget page.
        // A reset the user asked to hear about is the exception: it's announced whichever account is being viewed.
        if (active || old[k.NOTIFY_SESSION_RESET] != null || old[k.NOTIFY_WEEKLY_RESET] != null) notifications.onUsage(old, value, transition, k, label, account.provider, active)
        if (!active) return SessionSenseWidgets.updateAll(this)
        refreshActiveSurfaces(account, label)
    }

    private var shownOngoing: OngoingContent? = null
    private var shownOngoingAt = 0L
    /** [UserSettings.glyphLights]: off means no progress bar and re-posts only when a session starts or ends. */
    @Volatile private var glyphLights = true

    /** Re-posts the live notification only when [OngoingPolicy] says so: every post makes the Nothing Glyph flash. */
    private fun showOngoing(snapshot: UsageSnapshot, label: String?, quiet: Boolean, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val content = ongoingContent(snapshot, label, now)
        if (!force && !OngoingPolicy.shouldRepost(shownOngoing, shownOngoingAt, content, now, quiet, minimal = !glyphLights)) return
        shownOngoing = content; shownOngoingAt = now
        // The OS can refuse a foreground start (e.g. a background start on Android 12+); stop instead of crashing.
        // MainActivity starts the service again whenever the app is opened.
        try { startForeground(NotificationCenter.ONGOING_ID, notifications.ongoing(content, progress = glyphLights)) } catch (e: Exception) { Log.w(TAG, "startForeground refused", e); stopSelf() }
    }

    private suspend fun refreshActiveSurfaces(account: Account, label: String?) {
        val s = app.repository.settings.first()
        glyphLights = s.glyphLights
        showOngoing(app.repository.snapshot(), label, isQuietHour(s.quietHours, s.quietStart, s.quietEnd, LocalTime.now().hour))
        SessionSenseWidgets.updateAll(this)
    }

    override fun onDestroy() { scope.cancel(); client.dispatcher.executorService.shutdown(); super.onDestroy() }
    override fun onBind(intent: Intent?) = null

    companion object {
        private const val TAG = "SessionSense"
        private const val BACKGROUND_POLL_MS = 60_000L
        private const val CODEX_ACTIVE_POLL_MS = 60_000L
        private const val CODEX_BACKGROUND_POLL_MS = 5 * 60_000L
        private const val CODEX_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
        private const val CODEX_ANALYTICS_MS = 30 * 60_000L
        // Refresh a day before the access token's exp; if chatgpt.com refuses (e.g. a Cloudflare challenge), retry every 30 min.
        private const val CODEX_REFRESH_MARGIN_MS = 24 * 60 * 60 * 1000L
        private const val CODEX_REFRESH_RETRY_MS = 30 * 60_000L
        // Android 12+ refuses foreground-service starts while the app is in the background (e.g. the process was spun up
        // for a widget update or by WorkManager). That throws and would kill the process, so treat it as "try again later":
        // MainActivity starts the service whenever the app is opened.
        // A running service only gets onStartCommand, so [repost] asks it to post the live notification once, now.
        private const val EXTRA_REPOST = "repost"
        fun start(context: Context, repost: Boolean = false) = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, UsagePollingService::class.java).putExtra(EXTRA_REPOST, repost))
        }.isSuccess
        fun stop(context: Context) = context.stopService(Intent(context, UsagePollingService::class.java))
    }
}

object UsageParser {
    fun parse(body: String, now: Long): UsageSnapshot {
        val root = JSONObject(body)
        // null when the limit isn't reported at all (a null object or a null utilization), which is not the same as 0%.
        fun reported(name: String): Int? = when (val v = root.optJSONObject(name)?.opt("utilization")) {
            is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null
        }?.let { if (it > 0 && it < 1) 1 else it.toInt() }?.coerceIn(0, 100) // any real usage (<1%) still starts a session
        fun pct(name: String): Int = reported(name) ?: 0
        val opus = reported("seven_day_opus"); val sonnet = reported("seven_day_sonnet")
        fun reset(name: String): Long = root.optJSONObject(name)?.optString("resets_at")
            ?.takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0
        return UsageSnapshot(pct("five_hour"), pct("seven_day"), opus ?: 0, sonnet ?: 0,
            reset("five_hour"), reset("seven_day"), now, "connected", opusReported = opus != null, sonnetReported = sonnet != null)
    }
}

/**
 * Everything the live notification shows. Equal content means there is nothing to re-post. Being offline isn't part of
 * it: a dropped poll changes nothing on screen, so it mustn't cost a re-post.
 */
data class OngoingContent(val title: String, val text: String, val pct: Int, val expired: Boolean, val countdownToMs: Long)

fun ongoingContent(s: UsageSnapshot, account: String?, now: Long, zone: ZoneId = ZoneId.systemDefault()): OngoingContent {
    val live = s.sessionWindow && s.sessionPct > 0 && s.sessionResetMs > now
    val text = when {
        s.connection == "expired" -> "Session expired — open to reconnect"
        !s.sessionWindow -> "Weekly ${s.weeklyPct}% used"
        s.sessionPct > 0 && s.sessionResetMs > 0 -> "${s.sessionPct}% used · resets ${Instant.ofEpochMilli(s.sessionResetMs).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a"))}"
        s.sessionPct > 0 -> "${s.sessionPct}% used"
        else -> "Full 5-hour window available"
    }
    return OngoingContent(if (account != null) "SessionSense · $account" else "SessionSense", text, s.sessionPct, s.connection == "expired",
        if (live && s.connection != "expired") s.sessionResetMs else 0L)
}

/**
 * When the live notification is re-posted. Nothing phones flash the Glyph on every post (even a silent update), so a
 * percentage tick waits until [MIN_REPOST_MS] after the last post. Only changes the user needs straight away go out
 * at once: another account, the login state, a new or finished window, or a step into the amber/coral/full colour band.
 * During quiet hours only the account and login state get through; everything else waits until quiet hours end.
 * [minimal] (the Glyph switch is off) also drops percentage ticks and band changes: only the account, the login state
 * and a window starting or ending re-post, so on a Nothing phone the Glyph flashes a few times per session at most.
 */
object OngoingPolicy {
    const val MIN_REPOST_MS = 10 * 60_000L

    fun shouldRepost(shown: OngoingContent?, shownAtMs: Long, next: OngoingContent, now: Long, quiet: Boolean, minimal: Boolean = false): Boolean {
        if (shown == null) return true
        if (next == shown) return false
        if (next.title != shown.title || next.expired != shown.expired) return true
        if (quiet) return false
        if (next.countdownToMs != shown.countdownToMs) return true
        if (minimal) return false
        if (band(next.pct) != band(shown.pct)) return true
        return now - shownAtMs >= MIN_REPOST_MS
    }

    private fun band(pct: Int) = when { pct >= 100 -> 3; pct >= 85 -> 2; pct >= 60 -> 1; else -> 0 }
}

class NotificationCenter(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val app get() = context.applicationContext as SessionSenseApp

    init {
        manager.createNotificationChannel(NotificationChannel(ONGOING_CHANNEL, "Live session", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Usage alerts", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(DIGEST_CHANNEL, "Weekly digest", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(TIPS_CHANNEL, "Tips", NotificationManager.IMPORTANCE_MIN))
    }

    fun ongoing(c: OngoingContent, progress: Boolean = true): Notification {
        val builder = base(ONGOING_CHANNEL).setContentTitle(c.title).setContentText(c.text).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
        // The system ticks the countdown itself, so the notification doesn't have to be re-posted every minute.
        if (c.countdownToMs > 0) builder.setWhen(c.countdownToMs).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        else builder.setShowWhen(false)
        // A progress bar only while a window is running. Nothing's Glyph Progress mirrors it, so an idle, offline or
        // indeterminate (animated) bar would light the Glyph when there's nothing to show. None at all when the user
        // switched SessionSense's Glyph lights off.
        if (progress && c.countdownToMs > 0) {
            if (Build.VERSION.SDK_INT >= 36) builder.setStyle(Notification.ProgressStyle().setProgress(c.pct)
                .addProgressSegment(Notification.ProgressStyle.Segment(100).setColor(accent(c.pct))))
            else builder.setProgress(100, c.pct, false)
        }
        return builder.build()
    }

    /**
     * [account] prefixes alert titles (see UsagePollingService.label); [provider] names the window in alert bodies.
     * For a background account (not [active]) only reset requests are handled.
     */
    suspend fun onUsage(old: Preferences, s: UsageSnapshot, transition: TrackingResult, k: AccountKeys, account: String?, provider: Provider = Provider.CLAUDE, active: Boolean = true) {
        val settings = app.repository.settings.first()
        val flags = (old[k.ALERT_FLAGS] ?: emptySet()).toMutableSet()
        fun once(key: String, critical: Boolean = false, block: () -> Unit) {
            if (key !in flags && (!quiet(settings) || critical)) { block(); flags += key }
        }
        // With several accounts, name the one an alert is about.
        fun alert(id: Int, title: String, body: String, low: Boolean = false) = this@NotificationCenter.alert(id, if (account != null) "$account · $title" else title, body, low)
        val sessionFlag = old[k.NOTIFY_SESSION_RESET]; val weeklyFlag = old[k.NOTIFY_WEEKLY_RESET]
        suspend fun save(sessionRequest: ResetRequest, weeklyRequest: ResetRequest) = context.dataStore.edit {
            it[k.ALERT_FLAGS] = retainAlertFlags(flags, s.sessionResetMs, s.weeklyResetMs)
            // A request that fired or went stale is cleared, unless the user set a new one meanwhile.
            if (sessionRequest != ResetRequest.NONE && it[k.NOTIFY_SESSION_RESET] == sessionFlag) it.remove(k.NOTIFY_SESSION_RESET)
            if (weeklyRequest != ResetRequest.NONE && it[k.NOTIFY_WEEKLY_RESET] == weeklyFlag) it.remove(k.NOTIFY_WEEKLY_RESET)
        }
        // "Notify me when ready" from Home's reset planner. The user asked for it, so it ignores the alert switches and
        // quiet hours. A reset is caught on the next poll (about 5 s) while the service runs; if the service was stopped,
        // RecoveryWorker restarts it within 15 minutes, so the ping can be that late. There's no exact alarm in v1.1.
        val sessionRequest = resetNotifyDue(sessionFlag, old[k.SESSION_RESET] ?: 0, s.sessionResetMs, transition.ended != null)
        val oldWeeklyReset = old[k.WEEKLY_RESET] ?: 0
        val weeklyRequest = resetNotifyDue(weeklyFlag, oldWeeklyReset, s.weeklyResetMs, weeklyEnded(oldWeeklyReset, s.weeklyResetMs, old[k.WEEKLY] ?: 0, s.weeklyPct))
        val asked = sessionRequest == ResetRequest.FIRE
        if (transition.started && active && settings.sessionAlerts) once("start:${s.sessionResetMs}") {
            alert(101, "New 5-hour session started", "Resets at ${formatTime(s.sessionResetMs)}")
        }
        // On a rollover a new window is already running, so "full session available" would be wrong. A requested ping
        // shares the alert's id and once-key, so one reset never alerts twice.
        if (transition.ended != null && !transition.started && ((active && settings.sessionAlerts) || asked)) once(resetAlertKey(s.sessionResetMs), critical = asked) {
            alert(102, "Full session available", "Your 5-hour ${provider.label} window has reset.")
        }
        if (weeklyRequest == ResetRequest.FIRE) once("weekly-ready:$oldWeeklyReset", critical = true) {
            alert(205, "Weekly limit reset", "Your weekly ${provider.label} limit is available again.")
        }
        if (!active) { save(sessionRequest, weeklyRequest); return }
        val remaining = s.sessionResetMs - System.currentTimeMillis()
        if (s.sessionPct > 0 && settings.sessionAlerts) {
            listOf(60 to 103, 30 to 104, 10 to 105).forEach { (minutes, id) ->
                if (remaining in 1..minutes * 60_000L) once("$minutes:${s.sessionResetMs}", minutes == 10) {
                    alert(id, "$minutes min left", "${s.sessionPct}% of this ${provider.label} session is used.")
                }
            }
            val elapsed = 5 * 60 * 60 * 1000L - remaining
            if (elapsed >= 150 * 60_000L && s.sessionPct < 15) once("nudge:${s.sessionResetMs}") {
                alert(106, "Plenty of room this session", "Only ${s.sessionPct}% used with ${formatDuration(remaining)} left.", low = true)
            }
        }
        val oldWeekly = old[k.WEEKLY] ?: 0
        if (settings.weeklyAlerts) listOf(80 to 201, 95 to 202).forEach { (threshold, id) ->
            if (oldWeekly < threshold && s.weeklyPct >= threshold) once("weekly:$threshold:${s.weeklyResetMs}") {
                alert(id, "Weekly usage at ${s.weeklyPct}%", "Resets ${formatTime(s.weeklyResetMs)}")
            }
        }
        // Weekly reserve: once each per weekly window, like the other weekly alerts.
        val reserve = old[k.WEEKLY_RESERVE] ?: 0
        if (settings.weeklyAlerts) reserveAlerts(oldWeekly, s.weeklyPct, reserve).forEach { kind ->
            when (kind) {
                ReserveAlert.NEAR -> once("reserve-near:${s.weeklyResetMs}") {
                    alert(206, "${reserveState(s.weeklyPct, reserve).free}% left before your reserve", "Weekly at ${s.weeklyPct}% · $reserve% kept in reserve")
                }
                ReserveAlert.ENTERED -> once("reserve-in:${s.weeklyResetMs}") {
                    alert(207, "You’re into your $reserve% reserve", "Weekly at ${s.weeklyPct}% · resets ${formatTime(s.weeklyResetMs)}")
                }
            }
        }
        val oldOpus = old[k.OPUS] ?: 0
        if (settings.modelAlerts && s.opusReported && oldOpus < 80 && s.opusPct >= 80) once("opus:80:${s.weeklyResetMs}") {
            alert(203, "Opus quota at ${s.opusPct}%", "Your Opus allowance is nearing its limit.")
        }
        val weeklyRemaining = s.weeklyResetMs - System.currentTimeMillis()
        if (settings.weeklyAlerts && s.weeklyPct >= 80 && weeklyRemaining in 1..6 * 60 * 60 * 1000L) once("weekly-reset:${s.weeklyResetMs}") {
            alert(204, "Weekly quota resets soon", "${s.weeklyPct}% used · resets in ${formatDuration(weeklyRemaining)}")
        }
        save(sessionRequest, weeklyRequest)
    }

    suspend fun maybeSendWeeklyDigest(dao: SessionDao) {
        val now = ZonedDateTime.now()
        if (now.dayOfWeek != DayOfWeek.MONDAY || now.hour !in 8..10) return
        val settings = app.repository.settings.first()
        if (!settings.weeklyDigest || quiet(settings)) return
        val p = app.repository.preferences()
        val k = AccountKeys(app.repository.activeId())
        val key = "digest:${now.toLocalDate()}"
        if (key in (p[k.ALERT_FLAGS] ?: emptySet())) return
        val sessions = dao.observeAll(k.accountId).first().filter { it.startMs >= System.currentTimeMillis() - 7 * 86_400_000L }
        alert(301, "Your SessionSense week", "${sessions.size} sessions · ${sessions.maxOfOrNull { it.pctUsed } ?: 0}% peak · ${sessions.map { Instant.ofEpochMilli(it.startMs).atZone(ZoneId.systemDefault()).dayOfYear }.distinct().size}-day streak")
        context.dataStore.edit { it[k.ALERT_FLAGS] = (it[k.ALERT_FLAGS] ?: emptySet()) + key }
    }

    private fun alert(id: Int, title: String, body: String, low: Boolean = false) {
        // Belt and braces: re-posting an alert that's still showing updates it silently (no sound, vibration or Glyph).
        // Low-priority tips go to a minimum-importance channel: no status-bar icon, and no reason to light the Glyph.
        manager.notify(id, base(if (low) TIPS_CHANNEL else ALERT_CHANNEL).setContentTitle(title).setContentText(body).setAutoCancel(true).setOnlyAlertOnce(true).build())
    }
    private fun base(channel: String) = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_sessionsense).setColor(Color.rgb(110, 231, 208)).setContentIntent(
            PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    private fun quiet(s: UserSettings) = isQuietHour(s.quietHours, s.quietStart, s.quietEnd, LocalTime.now().hour)
    private fun accent(pct: Int) = when { pct >= 85 -> Color.rgb(244,138,122); pct >= 60 -> Color.rgb(244,199,122); else -> Color.rgb(110,231,208) }
    private fun formatTime(ms: Long) = if (ms <= 0) "soon" else Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE h:mm a"))
    private fun formatDuration(ms: Long): String { val m = ms / 60_000; return "${m / 60}h ${m % 60}m" }

    companion object { const val ONGOING_ID = 9001; private const val ONGOING_CHANNEL = "live_session"; private const val ALERT_CHANNEL = "usage_alerts"; private const val DIGEST_CHANNEL = "weekly_digest"; private const val TIPS_CHANNEL = "tips" }
}
