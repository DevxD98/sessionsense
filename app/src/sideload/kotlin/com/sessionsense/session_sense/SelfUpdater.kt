package com.sessionsense.session_sense

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.UnknownHostException
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Update state lives in its own store, apart from the usage data only UsagePollingService writes. */
private val Context.updateStore by preferencesDataStore("updates")

private object UpdateKeys {
    val LAST_CHECK = longPreferencesKey("last_check_ms")
    val MANIFEST = stringPreferencesKey("latest_manifest")
    val SKIPPED = intPreferencesKey("skipped_version_code")
    val NOTIFIED = intPreferencesKey("notified_version_code")
    val AUTO = booleanPreferencesKey("auto_check")
    /** Debug builds only (see UpdateDebugReceiver): read the manifest from a test release instead of the latest one. */
    val DEBUG_MANIFEST_URL = stringPreferencesKey("debug_manifest_url")
}

/** A redirect or URL outside [UpdateHosts]; the request is dropped before it's sent. */
private class BlockedHost(host: String) : IOException("Blocked a request to $host")

class SelfUpdater private constructor(private val app: SessionSenseApp) : UpdateController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = app.updateStore
    private val installedCode = BuildConfig.VERSION_CODE
    private val dir = File(app.cacheDir, "updates")

    // HTTPS to GitHub only, on every hop: the network interceptor sees each redirect before it is followed.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain -> chain.request().url.let { if (!UpdateHosts.isAllowed(it.toString())) throw BlockedHost(it.host) }; chain.proceed(chain.request()) }
        .build()

    private val check = MutableStateFlow<CheckState>(CheckState.Idle)
    private val phase = MutableStateFlow<InstallPhase>(InstallPhase.Idle)
    private var checkJob: Job? = null
    private var updateJob: Job? = null

    override val state: StateFlow<UpdateUiState> = combine(store.data, check, phase) { p, c, ph ->
        UpdateUiState(installedCode, BuildConfig.VERSION_NAME, p[UpdateKeys.AUTO] ?: true, c, p[UpdateKeys.LAST_CHECK] ?: 0, status(p), ph)
    }.stateIn(scope, SharingStarted.Eagerly, UpdateUiState(installedCode, BuildConfig.VERSION_NAME))

    private fun manifest(p: Preferences) = p[UpdateKeys.MANIFEST]?.let { (UpdateManifestParser.parse(it) as? ManifestResult.Ok)?.manifest }
    private fun status(p: Preferences) = UpdatePolicy.status(installedCode, manifest(p), p[UpdateKeys.SKIPPED] ?: 0)

    // ─── Checking ────────────────────────────────────────────────────────────────────────────────────────

    override fun onAppStart() {
        scope.launch {
            // An APK for this version or older is either installed already or useless now.
            dir.listFiles()?.forEach { f -> val code = f.name.removePrefix("sessionsense-").substringBefore('.').toIntOrNull(); if (code == null || code <= installedCode) f.delete() }
            val p = store.data.first()
            scheduleDaily(p[UpdateKeys.AUTO] ?: true)
            backgroundCheck()
        }
    }

    /** App start and the daily worker: only when automatic checks are on, at most every six hours. */
    suspend fun backgroundCheck() {
        val p = store.data.first()
        if (!(p[UpdateKeys.AUTO] ?: true) || !UpdatePolicy.shouldCheck(System.currentTimeMillis(), p[UpdateKeys.LAST_CHECK] ?: 0)) return
        runCheck(manual = false)
    }

    override fun checkNow() {
        if (checkJob?.isActive == true) return
        checkJob = scope.launch { runCheck(manual = true) }
    }

    private suspend fun runCheck(manual: Boolean) {
        check.value = CheckState.Checking
        try {
            val latest = fetchManifest()
            store.edit { p -> if (latest == null) p.remove(UpdateKeys.MANIFEST) else p[UpdateKeys.MANIFEST] = latest.toJson(); p[UpdateKeys.LAST_CHECK] = System.currentTimeMillis() }
            check.value = CheckState.Done
            // A manual check happens in the app, where the result is on screen already.
            if (!manual) notifyIfDue()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            check.value = CheckState.Failed(when (e) {
                is UnknownHostException -> "You’re offline."
                is UpdateRefused -> e.message
                is BlockedHost -> "Blocked an unexpected redirect."
                else -> "Couldn’t reach GitHub."
            })
        }
    }

    private suspend fun manifestUrl(): String =
        (if (BuildConfig.DEBUG) store.data.first()[UpdateKeys.DEBUG_MANIFEST_URL]?.takeIf(UpdateHosts::isAllowed) else null) ?: BuildConfig.UPDATE_MANIFEST_URL

    /** Null when nothing has been published yet (GitHub answers 404): that's simply "up to date". */
    private suspend fun fetchManifest(): UpdateManifest? {
        val request = Request.Builder().url(manifestUrl()).header("Accept", "application/json").header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return when (val parsed = UpdateManifestParser.parse(r.peekBody(MAX_MANIFEST_BYTES).string())) {
                is ManifestResult.Ok -> parsed.manifest
                is ManifestResult.Invalid -> throw UpdateRefused("Invalid release", "The published release is invalid (${parsed.reason}).")
            }
        }
    }

    suspend fun setManifestUrlOverride(url: String?) {
        store.edit { if (url.isNullOrBlank()) it.remove(UpdateKeys.DEBUG_MANIFEST_URL) else it[UpdateKeys.DEBUG_MANIFEST_URL] = url; it.remove(UpdateKeys.MANIFEST); it.remove(UpdateKeys.NOTIFIED); it.remove(UpdateKeys.LAST_CHECK); it.remove(UpdateKeys.SKIPPED) }
    }

    override fun setAutoCheck(enabled: Boolean) {
        scope.launch { store.edit { it[UpdateKeys.AUTO] = enabled }; scheduleDaily(enabled); if (enabled) backgroundCheck() }
    }

    override fun skip(versionCode: Int) { scope.launch { store.edit { it[UpdateKeys.SKIPPED] = versionCode } } }

    private fun scheduleDaily(enabled: Boolean) {
        val work = WorkManager.getInstance(app)
        if (!enabled) { work.cancelUniqueWork(WORK_NAME); return }
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    // ─── Notifying ───────────────────────────────────────────────────────────────────────────────────────

    override suspend fun onPeriodicTick() { if (store.data.first()[UpdateKeys.AUTO] ?: true) notifyIfDue() }

    /** Once per version, never for a skipped one, and held back during quiet hours (the next RecoveryWorker run retries). */
    private suspend fun notifyIfDue() {
        val p = store.data.first()
        val status = status(p)
        val s = app.repository.settings.first()
        val quiet = isQuietHour(s.quietHours, s.quietStart, s.quietEnd, LocalTime.now().hour)
        if (!UpdatePolicy.shouldNotify(status, p[UpdateKeys.NOTIFIED] ?: 0, quiet)) return
        val m = (status as? UpdateStatus.Required)?.manifest ?: (status as UpdateStatus.Available).manifest
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "New versions of SessionSense" })
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java).setAction(ACTION_OPEN_UPDATE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val summary = m.notes.lineSequence().map { it.trim().trimStart('-', '*', '•', ' ') }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        manager.notify(NOTIFICATION_ID, Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sessionsense).setColor(Color.rgb(110, 231, 208))
            .setContentTitle(if (status is UpdateStatus.Required) "Update required" else "SessionSense ${m.versionName} is available")
            .setContentText(summary ?: "Tap to see what’s new.")
            .setContentIntent(open).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Update", open).build())
            .build())
        store.edit { it[UpdateKeys.NOTIFIED] = m.versionCode }
    }

    // ─── Downloading, verifying, installing ──────────────────────────────────────────────────────────────

    override fun startUpdate() {
        if (updateJob?.isActive == true) return
        val m = state.value.offered ?: return
        if (!app.packageManager.canRequestPackageInstalls()) { phase.value = InstallPhase.NeedsPermission; return }
        app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        updateJob = scope.launch {
            val apk = File(dir, "sessionsense-${m.versionCode}.apk")
            try {
                if (apk.length() != m.sizeBytes) download(m, apk)
                phase.value = InstallPhase.Verifying
                ApkVerifier.checkHash(apk, m)
                ApkVerifier.checkPackage(app, apk, m)
                install(apk)
            } catch (e: CancellationException) {
                phase.value = InstallPhase.Idle
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update failed: ${e.message}")
                // A refused file is never kept; a network failure keeps the partial download so the retry resumes.
                if (e is UpdateRefused) { apk.delete(); partial(apk).delete() }
                phase.value = when (e) {
                    is UpdateRefused -> InstallPhase.Failed(e.title, e.message)
                    is UnknownHostException -> InstallPhase.Failed("You’re offline", "Connect to the internet and try again. The download will pick up where it stopped.")
                    is BlockedHost -> InstallPhase.Failed("Download blocked", "The download tried to leave GitHub (${e.message?.substringAfterLast(' ')}), so SessionSense stopped it.")
                    else -> InstallPhase.Failed("Download interrupted", "Try again — it will pick up where it stopped.")
                }
            }
        }
    }

    private fun partial(apk: File) = File(apk.path + ".part")

    /** Resumes a previous partial download with a Range request when the server allows it. */
    private suspend fun download(m: UpdateManifest, apk: File) {
        dir.mkdirs()
        dir.listFiles()?.filter { !it.name.startsWith("sessionsense-${m.versionCode}.") }?.forEach { it.delete() }
        val part = partial(apk)
        var offset = part.length().takeIf { it in 1 until m.sizeBytes } ?: 0L.also { part.delete() }
        phase.value = InstallPhase.Downloading(offset, m.sizeBytes)
        val request = Request.Builder().url(m.apkUrl).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        client.newCall(request).execute().use { r ->
            val resumed = r.code == 206 && r.header("Content-Range").orEmpty().startsWith("bytes $offset-")
            if (r.code == 416) { part.delete(); throw IOException("Range not satisfiable") }
            if (!resumed && r.code != 200) throw IOException("HTTP ${r.code}")
            if (!resumed) offset = 0L
            val body = r.body ?: throw IOException("Empty body")
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(offset); out.seek(offset)
                val buffer = ByteArray(64 * 1024); var written = offset; var lastEmit = 0L
                body.byteStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        written += n
                        if (written > m.sizeBytes) throw UpdateRefused("Download didn’t check out", "The file is larger than the published size, so it was deleted and nothing was installed.")
                        out.write(buffer, 0, n)
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 100) { lastEmit = now; phase.value = InstallPhase.Downloading(written, m.sizeBytes) }
                    }
                }
                phase.value = InstallPhase.Downloading(written, m.sizeBytes)
                if (written != m.sizeBytes) throw IOException("Incomplete download: $written of ${m.sizeBytes}")
            }
        }
        if (!part.renameTo(apk)) throw IOException("Couldn’t save the download")
    }

    private fun install(apk: File) {
        val installer = app.packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 34) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                // Mutable: the installer adds the status and, when it needs the user, the confirmation intent.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val callback = PendingIntent.getBroadcast(app, id, Intent(app, UpdateInstallReceiver::class.java).setPackage(app.packageName), flags)
                phase.value = InstallPhase.Installing
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** PackageInstaller's verdict, relayed by [UpdateInstallReceiver]. On success Android replaces this process right after. */
    fun onInstallStatus(status: Int, detail: String?, confirm: Intent?) {
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            phase.value = confirm?.let { InstallPhase.AwaitingConfirmation(it) } ?: InstallPhase.Failed("Update failed", "Android didn’t ask for confirmation.")
            return
        }
        val failure = InstallOutcome.of(status, detail)
        val apks = dir.listFiles().orEmpty()
        // Keep the verified file after a cancel so "Update now" doesn't download it again; it's re-verified before installing.
        if (failure == null || !failure.cancelled) apks.forEach { it.delete() }
        phase.value = if (failure == null) InstallPhase.Idle else InstallPhase.Failed(failure.title, failure.message)
        if (failure != null) Log.w(TAG, "Install status $status: $detail")
    }

    override fun confirmationLaunched() { if (phase.value is InstallPhase.AwaitingConfirmation) phase.value = InstallPhase.Installing }

    override fun cancel() { updateJob?.cancel(); phase.value = InstallPhase.Idle }

    override fun dismissError() { if (phase.value is InstallPhase.Failed || phase.value == InstallPhase.NeedsPermission) phase.value = InstallPhase.Idle }

    override fun openInstallPermissionSettings() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))

    companion object {
        private const val TAG = "SessionSenseUpdate"
        private const val CHANNEL = "app_updates"
        private const val NOTIFICATION_ID = 401
        private const val WORK_NAME = "update-check"
        private const val MAX_MANIFEST_BYTES = 64 * 1024L

        @Volatile private var instance: SelfUpdater? = null
        fun get(app: SessionSenseApp) = instance ?: synchronized(this) { instance ?: SelfUpdater(app).also { instance = it } }
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
        SelfUpdater.get(context.applicationContext as SessionSenseApp).onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), confirm)
    }
}

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { SelfUpdater.get(applicationContext as SessionSenseApp).backgroundCheck(); return Result.success() }
}
