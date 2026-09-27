package com.sessionsense.session_sense

import android.app.*
import android.content.*
import android.graphics.Color
import android.os.*
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
        startForeground(NotificationCenter.ONGOING_ID, notifications.ongoing(UsageSnapshot(), null))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.coroutineContext.cancelChildren()
        scope.launch {
            while (isActive) {
                pollAll()
                delay(5_000)
            }
        }
        return START_STICKY
    }

    private val lastPolled = mutableMapOf<String, Long>()

    /** Every connected account keeps its own history; background accounts are polled less often. */
    private suspend fun pollAll() {
        val accounts = app.credentials.accounts.value.filter { it.connected }
        if (accounts.isEmpty()) return stopSelf()
        val activeId = app.repository.activeId()
        val now = System.currentTimeMillis()
        accounts.forEach { account ->
            val active = account.id == activeId
            if (active || now - (lastPolled[account.id] ?: 0) >= BACKGROUND_POLL_MS) {
                lastPolled[account.id] = now
                poll(account, active, accounts.size > 1)
            }
        }
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
        .header("User-Agent", USER_AGENT)
        .header("Referer", "https://claude.ai")
        .header("Origin", "https://claude.ai")
        .build()

    private suspend fun poll(account: Account, active: Boolean, multiple: Boolean) {
        val k = AccountKeys(account.id)
        backfillProfile(account)
        val request = authed(account, "https://claude.ai/api/organizations/${account.orgId}/usage")
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> response.body?.string()?.let { persist(account, UsageParser.parse(it, System.currentTimeMillis()), active, multiple) }
                    401, 403 -> {
                        app.credentials.markExpired(account.id)
                        applicationContext.dataStore.edit { it[k.CONNECTION] = "expired" }
                        if (active) refreshActiveSurfaces(account, multiple)
                    }
                    else -> applicationContext.dataStore.edit { it[k.CONNECTION] = "offline" }
                }
            }
        } catch (_: Exception) {
            applicationContext.dataStore.edit { it[k.CONNECTION] = "offline" }
        }
    }

    private suspend fun persist(account: Account, value: UsageSnapshot, active: Boolean, multiple: Boolean) {
        val k = AccountKeys(account.id)
        val old = app.repository.preferences()
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
            p[k.PREV_SESSION] = transition.state.previousPct
            p[k.ACTIVE_START] = transition.state.activeStartMs
            p[k.ACTIVE_PEAK] = transition.state.peakPct
            p[k.ACTIVE_RESET] = transition.state.resetMs
            if (recorded) p[k.LAST_SAMPLE] = sample.ts
        }
        // Alerts, the live notification and widgets belong to the account the user is viewing only.
        if (!active) return
        notifications.onUsage(old, value, transition, k, if (multiple) account.name else null)
        refreshActiveSurfaces(account, multiple)
    }

    private suspend fun refreshActiveSurfaces(account: Account, multiple: Boolean) {
        startForeground(NotificationCenter.ONGOING_ID, notifications.ongoing(app.repository.snapshot(), if (multiple) account.name else null))
        SessionSenseWidgets.updateAll(this)
    }

    override fun onDestroy() { scope.cancel(); client.dispatcher.executorService.shutdown(); super.onDestroy() }
    override fun onBind(intent: Intent?) = null

    companion object {
        private const val BACKGROUND_POLL_MS = 60_000L
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"
        // Android 12+ refuses foreground-service starts while the app is in the background (e.g. the process was spun up
        // for a widget update or by WorkManager). That throws and would kill the process, so treat it as "try again later":
        // MainActivity starts the service whenever the app is opened.
        fun start(context: Context) = runCatching { ContextCompat.startForegroundService(context, Intent(context, UsagePollingService::class.java)) }.isSuccess
        fun stop(context: Context) = context.stopService(Intent(context, UsagePollingService::class.java))
    }
}

object UsageParser {
    fun parse(body: String, now: Long): UsageSnapshot {
        val root = JSONObject(body)
        fun pct(name: String): Int = when (val v = root.optJSONObject(name)?.opt("utilization")) {
            is Number -> v.toDouble(); is String -> v.toDoubleOrNull() ?: 0.0; else -> 0.0
        }.let { if (it > 0 && it < 1) 1 else it.toInt() }.coerceIn(0, 100) // any real usage (<1%) still starts a session
        fun reset(name: String): Long = root.optJSONObject(name)?.optString("resets_at")
            ?.takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0
        return UsageSnapshot(pct("five_hour"), pct("seven_day"), pct("seven_day_opus"), pct("seven_day_sonnet"),
            reset("five_hour"), reset("seven_day"), now, "connected")
    }
}

class NotificationCenter(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val app get() = context.applicationContext as SessionSenseApp

    init {
        manager.createNotificationChannel(NotificationChannel(ONGOING_CHANNEL, "Live session", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Usage alerts", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(DIGEST_CHANNEL, "Weekly digest", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun ongoing(s: UsageSnapshot, account: String?): Notification {
        val remaining = (s.sessionResetMs - System.currentTimeMillis()).coerceAtLeast(0)
        val text = when {
            s.connection == "expired" -> "Session expired — open to reconnect"
            s.sessionPct > 0 -> "${s.sessionPct}% used · ${formatDuration(remaining)} left"
            else -> "Full 5-hour window available"
        }
        val builder = base(ONGOING_CHANNEL).setContentTitle(if (account != null) "SessionSense · $account" else "SessionSense").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(Notification.ProgressStyle().setProgress(s.sessionPct)
                .addProgressSegment(Notification.ProgressStyle.Segment(100).setColor(accent(s.sessionPct))))
        } else builder.setProgress(100, s.sessionPct, s.connection != "connected")
        return builder.build()
    }

    suspend fun onUsage(old: Preferences, s: UsageSnapshot, transition: TrackingResult, k: AccountKeys, account: String?) {
        val settings = app.repository.settings.first()
        val flags = (old[k.ALERT_FLAGS] ?: emptySet()).toMutableSet()
        fun once(key: String, critical: Boolean = false, block: () -> Unit) {
            if (key !in flags && (!quiet(settings) || critical)) { block(); flags += key }
        }
        // With several accounts, name the one an alert is about.
        fun alert(id: Int, title: String, body: String, low: Boolean = false) = this@NotificationCenter.alert(id, if (account != null) "$account · $title" else title, body, low)
        if (transition.started && settings.sessionAlerts) once("start:${s.sessionResetMs}") {
            alert(101, "New 5-hour session started", "Resets at ${formatTime(s.sessionResetMs)}")
        }
        // On a rollover a new window is already running, so "full session available" would be wrong.
        if (transition.ended != null && !transition.started && settings.sessionAlerts) once(resetAlertKey(s.sessionResetMs)) {
            alert(102, "Full session available", "Your 5-hour Claude window has reset.")
        }
        val remaining = s.sessionResetMs - System.currentTimeMillis()
        if (s.sessionPct > 0 && settings.sessionAlerts) {
            listOf(60 to 103, 30 to 104, 10 to 105).forEach { (minutes, id) ->
                if (remaining in 1..minutes * 60_000L) once("$minutes:${s.sessionResetMs}", minutes == 10) {
                    alert(id, "$minutes min left", "${s.sessionPct}% of this Claude session is used.")
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
        val oldOpus = old[k.OPUS] ?: 0
        if (settings.modelAlerts && oldOpus < 80 && s.opusPct >= 80) once("opus:80:${s.weeklyResetMs}") {
            alert(203, "Opus quota at ${s.opusPct}%", "Your Opus allowance is nearing its limit.")
        }
        val weeklyRemaining = s.weeklyResetMs - System.currentTimeMillis()
        if (settings.weeklyAlerts && s.weeklyPct >= 80 && weeklyRemaining in 1..6 * 60 * 60 * 1000L) once("weekly-reset:${s.weeklyResetMs}") {
            alert(204, "Weekly quota resets soon", "${s.weeklyPct}% used · resets in ${formatDuration(weeklyRemaining)}")
        }
        context.dataStore.edit { it[k.ALERT_FLAGS] = retainAlertFlags(flags, s.sessionResetMs, s.weeklyResetMs) }
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
        manager.notify(id, base(if (low) ONGOING_CHANNEL else ALERT_CHANNEL).setContentTitle(title).setContentText(body).setAutoCancel(true).build())
    }
    private fun base(channel: String) = Notification.Builder(context, channel)
        .setSmallIcon(android.R.drawable.stat_notify_sync).setColor(Color.rgb(110, 231, 208)).setContentIntent(
            PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    private fun quiet(s: UserSettings): Boolean {
        if (!s.quietHours) return false
        val h = LocalTime.now().hour
        return if (s.quietStart < s.quietEnd) h in s.quietStart until s.quietEnd else h >= s.quietStart || h < s.quietEnd
    }
    private fun accent(pct: Int) = when { pct >= 85 -> Color.rgb(244,138,122); pct >= 60 -> Color.rgb(244,199,122); else -> Color.rgb(110,231,208) }
    private fun formatTime(ms: Long) = if (ms <= 0) "soon" else Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE h:mm a"))
    private fun formatDuration(ms: Long): String { val m = ms / 60_000; return "${m / 60}h ${m % 60}m" }

    companion object { const val ONGOING_ID = 9001; private const val ONGOING_CHANNEL = "live_session"; private const val ALERT_CHANNEL = "usage_alerts"; private const val DIGEST_CHANNEL = "weekly_digest" }
}
