package com.sessionsense.session_sense

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.widget.RemoteViews
import es.antonborri.home_widget.HomeWidgetPlugin
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ─── Data ─────────────────────────────────────────────────────────

private data class WidgetData(
    val sessionPct: Int,
    val weeklyPct: Int,
    val updatedMs: Long,
    val sessionReset: String?,
    val weeklyReset: String?,
    val isLive: Boolean,
    val plan: String,
    val dailyPeaks: List<Int>,
)

private fun loadData(context: Context): WidgetData {
    val prefs = HomeWidgetPlugin.getData(context)
    val sessionPct = prefs.getInt("session_pct", -1)
    val weeklyPct  = prefs.getInt("weekly_pct",  -1)
    val updatedMs  = prefs.getLong("last_updated_ms", 0L)
    val sessionReset = prefs.getString("session_reset", null)
    val weeklyReset  = prefs.getString("weekly_reset",  null)
    val plan = prefs.getString("plan", "pro") ?: "pro"
    val dailyPeaks = prefs.getString("daily_peaks", null)
        ?.split(",")
        ?.mapNotNull { it.trim().toIntOrNull()?.coerceIn(0, 100) }
        ?.takeIf { it.size == 7 }
        ?: List(7) { 0 }
    val isLive = sessionPct >= 0 && updatedMs > 0 &&
        (System.currentTimeMillis() - updatedMs) < 10 * 60 * 1000L
    return WidgetData(
        sessionPct   = sessionPct.coerceAtLeast(0),
        weeklyPct    = weeklyPct.coerceAtLeast(0),
        updatedMs    = updatedMs,
        sessionReset = sessionReset,
        weeklyReset  = weeklyReset,
        isLive       = isLive,
        plan         = plan,
        dailyPeaks   = dailyPeaks,
    )
}

// ─── State helpers ─────────────────────────────────────────────────

private fun stateOf(pct: Int) = when {
    pct == 0 -> "idle"
    pct < 60 -> "safe"
    pct < 85 -> "warning"
    else     -> "danger"
}

private fun stateColor(pct: Int): Int = when (stateOf(pct)) {
    "warning" -> 0xFFF4C77A.toInt()
    "danger"  -> 0xFFF48A7A.toInt()
    else      -> 0xFF6EE7D0.toInt()
}

private fun statePillText(pct: Int): String = when (stateOf(pct)) {
    "idle"    -> "IDLE"
    "warning" -> "WARNING"
    "danger"  -> "DANGER"
    else      -> "SAFE"
}

// ─── Formatters ────────────────────────────────────────────────────

private fun timeAgo(ms: Long): String {
    if (ms == 0L) return "--"
    val diffMs  = System.currentTimeMillis() - ms
    val diffMin = diffMs / 60_000
    val diffH   = diffMs / 3_600_000
    return when {
        diffMin < 1  -> "just now"
        diffMin < 60 -> "${diffMin}m ago"
        diffH   < 24 -> "${diffH}h ago"
        else         -> "${diffH / 24}d ago"
    }
}

private fun fmtResetShort(isoStr: String?): String {
    if (isoStr == null) return "--"
    return try {
        val local = LocalDateTime.ofInstant(Instant.parse(isoStr), ZoneId.systemDefault())
        local.format(DateTimeFormatter.ofPattern("h:mm a"))
    } catch (_: Exception) { "--" }
}

private fun fmtResetDay(isoStr: String?): String {
    if (isoStr == null) return "--"
    return try {
        val local = LocalDateTime.ofInstant(Instant.parse(isoStr), ZoneId.systemDefault())
        local.format(DateTimeFormatter.ofPattern("EEE h:mm a"))
    } catch (_: Exception) { "--" }
}

// Returns "3:45:00" style (no zero-padding on hours)
private fun fmtCountdown(isoStr: String?): String {
    if (isoStr == null) return "--:--"
    return try {
        val resetAt = Instant.parse(isoStr)
        val diffSec = (resetAt.epochSecond - Instant.now().epochSecond).coerceAtLeast(0)
        val h = diffSec / 3600
        val m = (diffSec % 3600) / 60
        val s = diffSec % 60
        "%d:%02d:%02d".format(h, m, s)
    } catch (_: Exception) { "--:--" }
}

// Returns "03:45:00" style (zero-padded hours for large display)
private fun fmtCountdownPadded(isoStr: String?): String {
    if (isoStr == null) return "--:--:--"
    return try {
        val resetAt = Instant.parse(isoStr)
        val diffSec = (resetAt.epochSecond - Instant.now().epochSecond).coerceAtLeast(0)
        val h = diffSec / 3600
        val m = (diffSec % 3600) / 60
        val s = diffSec % 60
        "%02d:%02d:%02d".format(h, m, s)
    } catch (_: Exception) { "--:--:--" }
}

private fun estTokens(weeklyPct: Int, plan: String): String {
    val budget = when (plan) {
        "max5"  -> 2_250_000L
        "max20" -> 9_000_000L
        else    -> 450_000L
    }
    val tokens = budget * weeklyPct / 100
    return when {
        tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
        tokens >= 1_000     -> "${tokens / 1_000}K"
        tokens == 0L        -> "--"
        else                -> "$tokens"
    }
}

// ─── Bar chart bitmap ──────────────────────────────────────────────

private fun buildBarChart(dailyPeaks: List<Int>, stateColor: Int, ctx: Context): Bitmap {
    // Cap pixel dimensions so the bitmap stays well under the ~1 MB Binder
    // transaction limit on high-density screens (a RemoteViews bitmap that's
    // too large throws TransactionTooLargeException → "can't load widget").
    // scaleType="fitXY" on the ImageView scales this up to fill the slot.
    val density = ctx.resources.displayMetrics.density
    val w = (240 * density).toInt().coerceIn(1, 600)
    val h = (56 * density).toInt().coerceIn(1, 150)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val labelH = 11 * density
    val chartH = h - labelH
    val barW = w / 7f
    val gap = 3 * density
    val radius = 3 * density

    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(18, 255, 255, 255)
    }
    val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stateColor }
    val barDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stateColor; alpha = 160
    }
    val textFaintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 242, 242, 243)
        textSize = 8.5f * density
        textAlign = Paint.Align.CENTER
    }
    val textActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stateColor
        textSize = 8.5f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    val days = listOf("M", "T", "W", "T", "F", "S", "S")
    val todayIdx = LocalDate.now().dayOfWeek.value - 1  // 0 = Monday

    for (i in 0..6) {
        val left  = i * barW + gap
        val right = (i + 1) * barW - gap
        val cx    = (left + right) / 2f

        // Track
        canvas.drawRoundRect(RectF(left, 0f, right, chartH), radius, radius, trackPaint)

        // Fill
        val pct = dailyPeaks.getOrElse(i) { 0 }
        if (pct > 0) {
            val fillH = chartH * pct / 100f
            val paint = if (i == todayIdx) barPaint else barDimPaint
            canvas.drawRoundRect(
                RectF(left, chartH - fillH, right, chartH),
                radius, radius, paint,
            )
        }

        // Label
        val tPaint = if (i == todayIdx) textActivePaint else textFaintPaint
        canvas.drawText(days[i], cx, h.toFloat(), tPaint)
    }

    return bmp
}

// ─── Shared: apply state-colored session progress bars ────────────

private fun RemoteViews.applySessionBar(pct: Int) {
    val state = stateOf(pct)
    val tealVisible  = if (state != "warning" && state != "danger") View.VISIBLE else View.GONE
    val amberVisible = if (state == "warning") View.VISIBLE else View.GONE
    val coralVisible = if (state == "danger")  View.VISIBLE else View.GONE

    setViewVisibility(R.id.widget_session_bar_teal,  tealVisible)
    setViewVisibility(R.id.widget_session_bar_amber, amberVisible)
    setViewVisibility(R.id.widget_session_bar_coral, coralVisible)

    val activeBar = when (state) {
        "warning" -> R.id.widget_session_bar_amber
        "danger"  -> R.id.widget_session_bar_coral
        else      -> R.id.widget_session_bar_teal
    }
    setProgressBar(activeBar, 100, pct, false)
}

// ─── Small (2×2) ──────────────────────────────────────────────────

class SessionSenseWidgetSmall : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val d = loadData(ctx)
        val color = stateColor(d.sessionPct)
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_small)

            v.setTextViewText(R.id.widget_session_pct,
                if (d.sessionPct > 0) "${d.sessionPct}" else "--")
            v.setTextColor(R.id.widget_session_pct, color)

            v.setTextViewText(R.id.widget_weekly_pct,
                if (d.weeklyPct > 0) "${d.weeklyPct}%" else "--%")

            v.applySessionBar(d.sessionPct)

            // Dim the live dot when data is stale. setAlpha(float) is a
            // @RemotableViewMethod, so setFloat is safe; setInt("setAlpha") is NOT
            // (View has no setAlpha(int)) and would crash the whole RemoteViews.
            v.setFloat(R.id.widget_live_dot, "setAlpha", if (d.isLive) 1f else 0.3f)

            mgr.updateAppWidget(id, v)
        }
    }
}

// ─── Medium (4×2) ─────────────────────────────────────────────────

class SessionSenseWidgetMedium : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val d = loadData(ctx)
        val color = stateColor(d.sessionPct)
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_medium)

            // Status pill — colored text on a neutral chip. We avoid
            // setInt("setBackgroundResource", …) because setBackgroundResource is
            // not @RemotableViewMethod and RemoteViews rejects it on Android 12+.
            v.setTextViewText(R.id.widget_status_pill, statePillText(d.sessionPct))
            v.setTextColor(R.id.widget_status_pill, color)

            // Countdown
            v.setTextViewText(R.id.widget_session_remaining, fmtCountdown(d.sessionReset))
            v.setTextColor(R.id.widget_session_remaining, color)

            // Session % + reset
            v.setTextViewText(R.id.widget_session_pct,
                if (d.sessionPct > 0) "${d.sessionPct}% used" else "--")
            v.setTextColor(R.id.widget_session_pct, color)
            v.setTextViewText(R.id.widget_session_reset,
                "resets ${fmtResetShort(d.sessionReset)}")

            // Session progress bars (state-colored)
            v.applySessionBar(d.sessionPct)

            // Weekly
            v.setTextViewText(R.id.widget_weekly_pct,
                if (d.weeklyPct > 0) "${d.weeklyPct}%" else "--%")
            v.setProgressBar(R.id.widget_weekly_bar, 100, d.weeklyPct, false)

            // Token estimate
            v.setTextViewText(R.id.widget_est_tokens, estTokens(d.weeklyPct, d.plan))

            mgr.updateAppWidget(id, v)
        }
    }
}

// ─── Large (4×4) ──────────────────────────────────────────────────

class SessionSenseWidgetLarge : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val d = loadData(ctx)
        val color = stateColor(d.sessionPct)
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_large)

            // Status pill — colored text on a neutral chip (see Medium for why
            // we don't swap the background drawable dynamically).
            v.setTextViewText(R.id.widget_status_pill, statePillText(d.sessionPct))
            v.setTextColor(R.id.widget_status_pill, color)

            // Countdown (padded: 03:45:00)
            v.setTextViewText(R.id.widget_session_remaining, fmtCountdownPadded(d.sessionReset))
            v.setTextColor(R.id.widget_session_remaining, color)
            v.setTextViewText(R.id.widget_session_reset,
                "resets at ${fmtResetShort(d.sessionReset)}")

            // Weekly
            v.setTextViewText(R.id.widget_weekly_pct,
                if (d.weeklyPct > 0) "${d.weeklyPct}" else "--")
            v.setTextViewText(R.id.widget_weekly_reset,
                "resets ${fmtResetDay(d.weeklyReset)}")
            v.setProgressBar(R.id.widget_weekly_bar, 100, d.weeklyPct, false)

            // Token estimate
            v.setTextViewText(R.id.widget_est_tokens, "~${estTokens(d.weeklyPct, d.plan)} tokens")

            // Updated timestamp
            v.setTextViewText(R.id.widget_updated, "updated ${timeAgo(d.updatedMs)}")

            // Bar chart
            val chart = buildBarChart(d.dailyPeaks, color, ctx)
            v.setImageViewBitmap(R.id.widget_bar_chart, chart)

            mgr.updateAppWidget(id, v)
        }
    }
}
