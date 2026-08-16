package com.sessionsense.session_sense

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
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

// Drives the countdown TextView (a Chronometer) so it keeps ticking every
// second on its own via SystemClock, instead of freezing between the
// app's ~60s data pushes or the system's 30-min forced widget refresh.
private fun applyCountdown(v: RemoteViews, viewId: Int, isoStr: String?, idleText: String) {
    val resetAt = isoStr?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val diffMs = resetAt?.let { it.toEpochMilli() - Instant.now().toEpochMilli() } ?: -1L
    if (resetAt == null || diffMs <= 0) {
        // Not running / already elapsed: show static text rather than letting
        // the Chronometer tick past zero into negative numbers.
        v.setTextViewText(viewId, idleText)
        return
    }
    val base = SystemClock.elapsedRealtime() + diffMs
    v.setChronometer(viewId, base, null, true)
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

// ─── Activity ring bitmap ───────────────────────────────────────────

// Replaces the old flat linear progress bars: a circular sweep (% used)
// drawn around the live countdown/number, the same "activity ring" idiom
// used by watch faces — far more glanceable than a thin bar at a distance.
private fun buildProgressRing(pct: Int, color: Int, ctx: Context, sizeDp: Int, strokeDp: Float): Bitmap {
    val density = ctx.resources.displayMetrics.density
    val size = (sizeDp * density).toInt().coerceIn(1, 400)
    val stroke = strokeDp * density
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val rect = RectF(stroke / 2, stroke / 2, size - stroke / 2, size - stroke / 2)

    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        this.color = Color.argb(28, 255, 255, 255)
    }
    val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }

    canvas.drawArc(rect, 0f, 360f, false, trackPaint)
    val sweep = 360f * pct.coerceIn(0, 100) / 100f
    if (sweep > 0f) canvas.drawArc(rect, -90f, sweep, false, progressPaint)

    return bmp
}

// ─── Boba energy cup bitmap ─────────────────────────────────────────

// Cup + liquid + pearls + straw + lid, all drawn into one bitmap. RemoteViews
// can't host custom shapes, so this mirrors the showcase's "Boba Energy Cup"
// concept (frontend/widgets_showcase.html) as a static Canvas illustration —
// the CSS wave/float animations don't translate, but the composition does.
private fun buildBobaBitmap(pct: Int, color: Int, ctx: Context): Bitmap {
    val density = ctx.resources.displayMetrics.density
    fun dp(v: Float) = v * density

    val w = dp(64f).toInt().coerceIn(1, 300)
    val h = dp(78f).toInt().coerceIn(1, 400)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val cupLeft = dp(12f)
    val cupRight = dp(56f)
    val cupTop = dp(20f)
    val cupBottom = dp(76f).coerceAtMost(h - dp(2f))
    val cupW = cupRight - cupLeft
    val cupH = cupBottom - cupTop
    val topRadius = dp(2f)
    val bottomRadius = dp(14f)

    val cupPath = Path().apply {
        addRoundRect(
            RectF(cupLeft, cupTop, cupRight, cupBottom),
            floatArrayOf(
                topRadius, topRadius, topRadius, topRadius,
                bottomRadius, bottomRadius, bottomRadius, bottomRadius,
            ),
            Path.Direction.CW,
        )
    }

    // Straw, rotated through the lid
    val strawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(120, 255, 255, 255) }
    canvas.save()
    canvas.translate(cupLeft + cupW * 0.74f, cupTop - dp(2f))
    canvas.rotate(-12f)
    canvas.drawRoundRect(RectF(-dp(2.5f), -dp(34f), dp(2.5f), dp(6f)), dp(2.5f), dp(2.5f), strawPaint)
    canvas.restore()

    // Liquid + pearls, clipped to the cup interior
    if (pct > 0) {
        canvas.save()
        canvas.clipPath(cupPath)

        val liquidH = cupH * pct.coerceIn(0, 100) / 100f
        val liquidTop = cupBottom - liquidH

        val liquidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 230 }
        val wavePath = Path().apply {
            moveTo(cupLeft, liquidTop + dp(4f))
            quadTo(cupLeft + cupW * 0.25f, liquidTop - dp(2f), cupLeft + cupW * 0.5f, liquidTop + dp(4f))
            quadTo(cupLeft + cupW * 0.75f, liquidTop + dp(10f), cupRight, liquidTop + dp(4f))
            lineTo(cupRight, cupBottom)
            lineTo(cupLeft, cupBottom)
            close()
        }
        canvas.drawPath(wavePath, liquidPaint)

        val pearlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.rgb(22, 16, 14) }
        val pearlHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(120, 255, 255, 255) }
        val pearlR = dp(3.2f)
        val pearlPositions = listOf(
            0.18f to 0.92f, 0.42f to 0.85f, 0.82f to 0.90f,
            0.30f to 0.72f, 0.68f to 0.75f, 0.55f to 0.95f,
        )
        for ((fx, fy) in pearlPositions) {
            val cx = cupLeft + cupW * fx
            val cy = cupTop + cupH * fy
            if (cy < liquidTop - dp(2f)) continue
            canvas.drawCircle(cx, cy, pearlR, pearlPaint)
            canvas.drawCircle(cx - pearlR * 0.3f, cy - pearlR * 0.3f, pearlR * 0.3f, pearlHighlight)
        }

        val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(40, 255, 255, 255) }
        canvas.drawRoundRect(
            RectF(cupLeft + dp(3f), cupTop + dp(2f), cupLeft + dp(6f), cupBottom - dp(4f)),
            dp(1.5f), dp(1.5f), shinePaint,
        )

        canvas.restore()
    }

    val cupStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        this.color = Color.argb(140, 255, 255, 255)
    }
    canvas.drawPath(cupPath, cupStroke)

    val lidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(46, 255, 255, 255) }
    val lidStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        this.color = Color.argb(140, 255, 255, 255)
    }
    val lidRect = RectF(cupLeft - dp(2f), cupTop - dp(3f), cupRight + dp(2f), cupTop + dp(1f))
    canvas.drawRoundRect(lidRect, dp(2f), dp(2f), lidPaint)
    canvas.drawRoundRect(lidRect, dp(2f), dp(2f), lidStroke)

    return bmp
}

// ─── Claude the Robo-Pet bitmap ──────────────────────────────────────

// Mirrors the showcase's "Claude the Robo-Pet" mascot concept (frontend/
// widgets_showcase.html): a head with a screen, eyes that change per state,
// and a small badge ("Z" while sleeping, "ALARM" while in danger). Static
// per refresh — the CSS bob/type/shiver animations don't translate to a
// RemoteViews bitmap.
private fun buildMascotBitmap(state: String, color: Int, ctx: Context): Bitmap {
    val density = ctx.resources.displayMetrics.density
    fun dp(v: Float) = v * density

    val w = dp(70f).toInt().coerceIn(1, 300)
    val h = dp(62f).toInt().coerceIn(1, 300)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val headW = dp(52f)
    val headH = dp(40f)
    val headLeft = (w - headW) / 2f
    val headTop = dp(16f)
    val headRect = RectF(headLeft, headTop, headLeft + headW, headTop + headH)
    val headRadius = dp(11f)

    val earsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(20, 255, 255, 255) }
    canvas.drawRoundRect(
        RectF(headLeft - dp(2f), headTop - dp(7f), headLeft + headW + dp(2f), headTop - dp(1f)),
        dp(3f), dp(3f), earsPaint,
    )

    val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.parseColor("#141416") }
    val headStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.3f)
        this.color = Color.argb(140, 255, 255, 255)
    }
    canvas.drawRoundRect(headRect, headRadius, headRadius, headPaint)
    canvas.drawRoundRect(headRect, headRadius, headRadius, headStroke)

    val screenW = dp(34f)
    val screenH = dp(22f)
    val screenLeft = headLeft + (headW - screenW) / 2f
    val screenTop = headTop + (headH - screenH) / 2f
    val screenRect = RectF(screenLeft, screenTop, screenLeft + screenW, screenTop + screenH)
    val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.parseColor("#070709") }
    canvas.drawRoundRect(screenRect, dp(5f), dp(5f), screenPaint)

    val cx1 = screenRect.centerX() - dp(6.5f)
    val cx2 = screenRect.centerX() + dp(6.5f)
    val cy = screenRect.centerY()

    when (state) {
        "idle" -> {
            val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(110, 242, 242, 243) }
            for (cx in listOf(cx1, cx2)) {
                canvas.drawRoundRect(RectF(cx - dp(3.5f), cy - dp(1f), cx + dp(3.5f), cy + dp(1f)), dp(1f), dp(1f), eyePaint)
            }
            val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.argb(140, 242, 242, 243)
                typeface = Typeface.DEFAULT_BOLD
            }
            zPaint.textSize = dp(9f)
            canvas.drawText("Z", headLeft + headW - dp(4f), headTop - dp(5f), zPaint)
            zPaint.textSize = dp(6.5f)
            canvas.drawText("z", headLeft + headW + dp(3f), headTop - dp(1f), zPaint)
        }
        "safe" -> {
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 70 }
            val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            for (cx in listOf(cx1, cx2)) {
                canvas.drawCircle(cx, cy, dp(4.2f), glowPaint)
                canvas.drawCircle(cx, cy, dp(2.6f), eyePaint)
            }
        }
        "warning" -> {
            val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            for (cx in listOf(cx1, cx2)) {
                canvas.drawRoundRect(RectF(cx - dp(3f), cy - dp(2f), cx + dp(3f), cy + dp(2f)), dp(0.8f), dp(0.8f), eyePaint)
            }
            val sweatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.parseColor("#8BB4FF") }
            val sx = headLeft + headW + dp(1f)
            val sy = headTop + dp(6f)
            val sweatPath = Path().apply {
                moveTo(sx, sy)
                quadTo(sx + dp(3f), sy + dp(5f), sx, sy + dp(9f))
                quadTo(sx - dp(3f), sy + dp(5f), sx, sy)
                close()
            }
            canvas.drawPath(sweatPath, sweatPaint)
        }
        else -> { // danger
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 90 }
            val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            for (cx in listOf(cx1, cx2)) {
                canvas.drawCircle(cx, cy, dp(5f), glowPaint)
                canvas.drawCircle(cx, cy, dp(3.2f), eyePaint)
            }
            val badgeText = "ALARM"
            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.parseColor("#070709")
                typeface = Typeface.DEFAULT_BOLD
                textSize = dp(7f)
            }
            val textW = textPaint.measureText(badgeText)
            val badgeRect = RectF(headLeft - dp(2f), dp(1f), headLeft - dp(2f) + textW + dp(8f), dp(1f) + dp(11f))
            canvas.drawRoundRect(badgeRect, dp(3f), dp(3f), badgePaint)
            canvas.drawText(badgeText, badgeRect.left + dp(4f), badgeRect.bottom - dp(3f), textPaint)
        }
    }

    return bmp
}

private fun mascotStatusText(pct: Int): String = when {
    pct == 0 -> "Sleeping · 0%"
    pct < 60 -> "Active · $pct%"
    pct < 85 -> "Focused · $pct%"
    else     -> "Alert · $pct%"
}

// ─── Small (2×2) ──────────────────────────────────────────────────

class SessionSenseWidgetSmall : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val d = loadData(ctx)
        val color = stateColor(d.sessionPct)
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_small)

            v.setTextViewText(R.id.widget_session_pct,
                if (d.sessionPct > 0) "${d.sessionPct}%" else "--")
            v.setTextColor(R.id.widget_session_pct, color)

            v.setTextViewText(R.id.widget_weekly_pct,
                if (d.weeklyPct > 0) "${d.weeklyPct}%" else "--%")

            val ring = buildProgressRing(d.sessionPct, color, ctx, sizeDp = 72, strokeDp = 6f)
            v.setImageViewBitmap(R.id.widget_session_ring, ring)

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
            applyCountdown(v, R.id.widget_session_remaining, d.sessionReset, "0:00")
            v.setTextColor(R.id.widget_session_remaining, color)

            // Session % + reset
            v.setTextViewText(R.id.widget_session_pct,
                if (d.sessionPct > 0) "${d.sessionPct}% used" else "--")
            v.setTextColor(R.id.widget_session_pct, color)
            v.setTextViewText(R.id.widget_session_reset,
                "resets ${fmtResetShort(d.sessionReset)}")

            // Activity ring around the countdown (sweep = % session used)
            val ring = buildProgressRing(d.sessionPct, color, ctx, sizeDp = 66, strokeDp = 5f)
            v.setImageViewBitmap(R.id.widget_session_ring, ring)

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

            // Countdown
            applyCountdown(v, R.id.widget_session_remaining, d.sessionReset, "00:00:00")
            v.setTextColor(R.id.widget_session_remaining, color)
            v.setTextViewText(R.id.widget_session_reset,
                "resets at ${fmtResetShort(d.sessionReset)}")

            // Session % + activity ring (sweep = % session used)
            v.setTextViewText(R.id.widget_session_pct,
                if (d.sessionPct > 0) "${d.sessionPct}% used" else "Not started")
            v.setTextColor(R.id.widget_session_pct, color)
            val ring = buildProgressRing(d.sessionPct, color, ctx, sizeDp = 112, strokeDp = 8f)
            v.setImageViewBitmap(R.id.widget_session_ring, ring)

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

// ─── Boba Energy Cup (2×2) ────────────────────────────────────────

class SessionSenseWidgetBoba : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val d = loadData(ctx)
        val color = stateColor(d.sessionPct)
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_boba)

            val cup = buildBobaBitmap(d.sessionPct, color, ctx)
            v.setImageViewBitmap(R.id.widget_boba_cup, cup)

            v.setTextViewText(R.id.widget_boba_val,
                if (d.sessionPct > 0 || d.isLive) "${d.sessionPct}%" else "--%")
            v.setTextColor(R.id.widget_boba_val, color)

            mgr.updateAppWidget(id, v)
        }
    }
}

// ─── Claude the Robo-Pet (2×2) ────────────────────────────────────

class SessionSenseWidgetMascot : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val d = loadData(ctx)
        val state = stateOf(d.sessionPct)
        val color = stateColor(d.sessionPct)
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_mascot)

            val face = buildMascotBitmap(state, color, ctx)
            v.setImageViewBitmap(R.id.widget_mascot_face, face)

            v.setTextViewText(R.id.widget_mascot_status, mascotStatusText(d.sessionPct))
            v.setTextColor(R.id.widget_mascot_status, color)

            mgr.updateAppWidget(id, v)
        }
    }
}
