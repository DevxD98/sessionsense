package com.sessionsense.session_sense

import android.app.Application
import android.app.NotificationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.*

data class AppUiState(
    val usage: UsageSnapshot = UsageSnapshot(),
    val settings: UserSettings = UserSettings(),
    val sessions: List<SessionRecord> = emptyList(),
    val now: Long = System.currentTimeMillis(),
    val history: UsageHistory = UsageHistory(),
    val accounts: List<AccountOverview> = emptyList(),
    val activeAccountId: String = DEFAULT_ACCOUNT,
    /** Codex accounts only: last 7 days by model, surface and message count. */
    val codexAnalytics: CodexAnalytics? = null,
    /** Reset times the viewed account asked to be pinged about (0: none). A value for another window is stale. */
    val notifySessionReset: Long = 0,
    val notifyWeeklyReset: Long = 0,
) {
    val activeAccount get() = accounts.firstOrNull { it.account.id == activeAccountId }?.account
    val canAddAccount get() = accounts.size < CredentialStore.MAX_ACCOUNTS
    val provider get() = activeAccount?.provider ?: Provider.CLAUDE
    /** Both Claude and Codex accounts are tracked, so surfaces name the provider. */
    val mixedProviders get() = accounts.map { it.account.provider }.distinct().size > 1
    val sessionResetRequested get() = notifySessionReset > 0 && notifySessionReset == usage.sessionResetMs
    val weeklyResetRequested get() = notifyWeeklyReset > 0 && notifyWeeklyReset == usage.weeklyResetMs
    val remainingMs get() = (usage.sessionResetMs - now).coerceAtLeast(0)
    val sessionState get() = when { usage.sessionPct >= 85 -> "danger"; usage.sessionPct >= 60 -> "warning"; usage.sessionPct > 0 -> "safe"; else -> "idle" }
    val todaySessions get() = sessions.filter { Instant.ofEpochMilli(it.startMs).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now() }
    val weeklyStreakDays get() = history.activeDays
    val sessionsThisWeek get() = sessions.count { it.startMs >= now - 7 * 86_400_000L }
    val estimatedTokens get() = ((mapOf("pro" to 450_000, "max5" to 2_250_000, "max20" to 9_000_000)[settings.plan] ?: 450_000) * usage.weeklyPct / 100.0).toInt()
    val dailyPeaks: List<Int> get() = history.dailyPeaks
    /** The in-progress window, reconstructed from live usage (it is only written to Room once it ends). */
    val activeWindowStartMs get() = if (usage.sessionPct > 0 && usage.sessionResetMs > 0) usage.sessionResetMs - 5 * 60 * 60 * 1000L else 0L
    /** When this 5-hour window would run out; null when there's nothing to project. */
    val runway get() = runway(usage, history.between(usage.sessionResetMs - SESSION_MS, now), now)
    // The Runway card already says when the session limit is hit, so Pace insight doesn't repeat it.
    val reserve get() = reserveState(usage.weeklyPct, settings.weeklyReserve)
    val insight get() = paceInsight(usage, now, skipSessionLimit = runway != null)
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SessionSenseApp
    /** The self-updater (sideload builds only); the UI reads its state and calls its actions directly. */
    val updates: UpdateController? get() = app.updates
    private val ticker = flow { while (true) { emit(System.currentTimeMillis()); delay(1_000) } }
    // Derived off the main thread and only when samples/sessions change, not on every 1s tick.
    private val history = combine(app.repository.samplesSince(System.currentTimeMillis() - 14 * 86_400_000L), app.repository.sessions) { samples, sessions ->
        UsageHistory.build(samples, sessions)
    }.flowOn(Dispatchers.Default)
    private val accounts = combine(app.repository.accounts, app.repository.activeAccountId) { list, id -> list to id }
    val state = combine(app.repository.usage, app.repository.settings, app.repository.sessions, history, accounts) { usage, settings, sessions, history, (list, id) ->
        AppUiState(usage, settings, sessions, System.currentTimeMillis(), history, list, id)
    }.combine(app.repository.codexAnalytics) { s, analytics -> s.copy(codexAnalytics = analytics) }
        .combine(app.repository.resetRequests) { s, (session, weekly) -> s.copy(notifySessionReset = session, notifyWeeklyReset = weekly) }
        .combine(ticker) { s, now -> s.copy(now = now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    fun route(value: String) = viewModelScope.launch { app.repository.setRoute(value) }
    fun plan(value: String) = viewModelScope.launch { app.repository.setPlan(value); SessionSenseWidgets.updateAll(app, force = true) }
    fun toggle(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) = viewModelScope.launch { app.repository.setBoolean(key, value) }
    /** Re-posts the live notification once so its progress bar appears or goes straight away. */
    fun glyphLights(value: Boolean) = viewModelScope.launch {
        app.repository.setBoolean(Keys.GLYPH_LIGHTS, value)
        if (app.credentials.hasConnected()) UsagePollingService.start(app, repost = true)
    }
    /** Home's reset planner: ping when the window resetting at [resetMs] does; null cancels. */
    fun notifyReset(weekly: Boolean, resetMs: Long?) = viewModelScope.launch { app.repository.setResetRequest(weekly, resetMs) }
    fun reserve(value: Int) = viewModelScope.launch { app.repository.setReserve(value) }
    fun quietHours(start: Int, end: Int) = viewModelScope.launch { app.repository.setQuietHours(start, end) }
    fun deleteSession(record: SessionRecord) = viewModelScope.launch { app.database.sessions().delete(record.id) }
    /** Clears history for the account being viewed only. */
    fun clearHistory() = viewModelScope.launch { val id = app.repository.activeId(); app.database.sessions().clear(id); app.database.samples().clear(id) }

    fun switchAccount(id: String) = viewModelScope.launch {
        app.repository.setActive(id)
        // Restart the loop so the newly viewed account is polled (and its alerts/notification take over) right away.
        if (app.credentials.hasConnected()) UsagePollingService.start(app)
        SessionSenseWidgets.updateAll(app, force = true)
    }

    /** Signs out of the account being viewed and forgets its data; other accounts keep tracking. */
    fun signOut() = viewModelScope.launch {
        val id = app.repository.activeId()
        app.credentials.remove(id)
        val remaining = app.credentials.accounts.value
        app.repository.forgetAccount(id, remaining)
        app.getSystemService(NotificationManager::class.java).cancelAll()
        if (remaining.none { it.connected }) UsagePollingService.stop(app) else UsagePollingService.start(app)
        SessionSenseWidgets.updateAll(app, force = true)
    }

    /** Stores a claude.ai or chatgpt.com login and makes it the viewed account. Returns an error message when it can't be added. */
    fun connected(login: AccountLogin): String? {
        val account = app.credentials.upsert(login).getOrElse { return it.message }
        viewModelScope.launch {
            app.repository.setActive(account.id)
            UsagePollingService.start(app)
            SessionSenseWidgets.updateAll(app, force = true)
        }
        return null
    }
}
