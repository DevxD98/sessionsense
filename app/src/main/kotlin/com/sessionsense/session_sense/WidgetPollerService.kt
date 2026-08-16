package com.sessionsense.session_sense

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import es.antonborri.home_widget.HomeWidgetPlugin
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class WidgetPollerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var sessionKey: String? = null
    private var orgId: String?      = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            poll()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sessionKey = intent?.getStringExtra(EXTRA_SESSION_KEY) ?: sessionKey
        orgId      = intent?.getStringExtra(EXTRA_ORG_ID)     ?: orgId

        startForeground(NOTIF_ID, buildNotification())

        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }

    // ── Polling ──────────────────────────────────────────────────────────────

    private fun poll() {
        val sk = sessionKey ?: return
        val org = orgId     ?: return

        try {
            val url = URL("https://claude.ai/api/organizations/$org/usage")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Cookie",     "sessionKey=$sk")
                setRequestProperty("Accept",     "application/json")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                        "AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                        "Version/17.0 Safari/605.1.15")
                setRequestProperty("Referer",    "https://claude.ai")
                setRequestProperty("Origin",     "https://claude.ai")
                connectTimeout = 10_000
                readTimeout    = 10_000
            }

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                parseAndSave(body)
                triggerWidgetUpdates()
            } else {
                conn.disconnect()
                if (conn.responseCode == 401 || conn.responseCode == 403) stopSelf()
            }
        } catch (_: Exception) {
            // Silently ignore — next poll in 60 s
        }
    }

    private fun parseAndSave(json: String) {
        try {
            val root     = JSONObject(json)
            val fiveHour = root.optJSONObject("five_hour")
            val sevenDay = root.optJSONObject("seven_day")
            val sonnet   = root.optJSONObject("seven_day_sonnet")

            val prefs = HomeWidgetPlugin.getData(this).edit()

            prefs.putInt("session_pct", pct(fiveHour).coerceIn(0, 100))
            prefs.putInt("weekly_pct",  pct(sevenDay).coerceIn(0, 100))
            prefs.putInt("sonnet_pct",  pct(sonnet).coerceIn(0, 100))
            prefs.putLong("last_updated_ms", System.currentTimeMillis())

            resetAt(fiveHour)?.let { prefs.putString("session_reset", it) }
            resetAt(sevenDay)?.let { prefs.putString("weekly_reset",  it) }

            prefs.apply()
        } catch (_: Exception) { /* malformed JSON — skip */ }
    }

    private fun pct(obj: JSONObject?): Int {
        obj ?: return 0
        return when (val u = obj.opt("utilization")) {
            is Int    -> u
            is Double -> u.toInt()
            is String -> u.toDoubleOrNull()?.toInt() ?: 0
            else      -> 0
        }
    }

    private fun resetAt(obj: JSONObject?): String? = obj?.optString("resets_at")?.takeIf { it.isNotBlank() }

    private fun triggerWidgetUpdates() {
        val manager = AppWidgetManager.getInstance(this)
        listOf(
            SessionSenseWidgetSmall::class.java,
            SessionSenseWidgetMedium::class.java,
            SessionSenseWidgetLarge::class.java,
            SessionSenseWidgetBoba::class.java,
            SessionSenseWidgetMascot::class.java,
        ).forEach { cls ->
            val ids = manager.getAppWidgetIds(ComponentName(this, cls))
            if (ids.isNotEmpty()) {
                val intent = Intent(this, cls).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                sendBroadcast(intent)
            }
        }
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Widget sync", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Keeps home screen widgets up to date"
                    setShowBadge(false)
                }
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SessionSense")
            .setContentText("Keeping widgets up to date")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val EXTRA_SESSION_KEY  = "session_key"
        const val EXTRA_ORG_ID       = "org_id"
        private const val POLL_INTERVAL_MS = 5_000L
        private const val NOTIF_ID         = 9001
        private const val CHANNEL_ID       = "widget_sync"

        fun start(ctx: Context, sessionKey: String, orgId: String) {
            val intent = Intent(ctx, WidgetPollerService::class.java).apply {
                putExtra(EXTRA_SESSION_KEY, sessionKey)
                putExtra(EXTRA_ORG_ID,      orgId)
            }
            ctx.startForegroundService(intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WidgetPollerService::class.java))
        }
    }
}
