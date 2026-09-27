package com.sessionsense.session_sense

import android.app.Application
import android.content.*
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.work.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class SessionSenseApp : Application() {
    lateinit var database: SessionDatabase
    lateinit var repository: SessionRepository
    lateinit var credentials: CredentialStore
    /** GitHub Releases self-updater in sideload builds, null in play builds. */
    val updates: UpdateController? by lazy { UpdateFeature.create(this) }

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(this, SessionDatabase::class.java, "session_sense.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        credentials = CredentialStore(this)
        repository = SessionRepository(this, database, credentials)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "usage-service-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RecoveryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
        if (credentials.hasConnected()) UsagePollingService.start(this)
        updates?.onAppStart()
    }
}

class RecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SessionSenseApp
        if (app.credentials.hasConnected()) UsagePollingService.start(app)
        NotificationCenter(app).maybeSendWeeklyDigest(app.database.sessions())
        app.updates?.onPeriodicTick()
        return Result.success()
    }
}

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as SessionSenseApp
        if (app.credentials.hasConnected()) UsagePollingService.start(context)
    }
}
