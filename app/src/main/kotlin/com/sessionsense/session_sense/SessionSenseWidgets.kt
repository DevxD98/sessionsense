package com.sessionsense.session_sense

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.appwidget.*
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.widget.RemoteViews
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpSize
import android.view.View
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// Same tokens as the in-app palette (SessionSenseUi.kt).
private val WBg = Color(0xFF111114)
private val WText = ColorProvider(Text)
private val WMuted = ColorProvider(Muted)
private val WFaint = ColorProvider(Faint)
private val Violet = Color(0xFFB79BFF)

object SessionSenseWidgets {
    @Volatile private var lastSignature: String? = null
    private val renderLock = Mutex()

    /** Called by [UsagePollingService] on every poll; only re-renders when what the widgets show actually changes. */
    suspend fun updateAll(context: Context, force: Boolean = false) {
        val repo = (context.applicationContext as SessionSenseApp).repository
        val s = repo.snapshot()
        val now = System.currentTimeMillis()
        val accounts = repo.accounts.first()
        // Every account's usage, not only the viewed one: a widget can be switched to any account.
        // The countdown is part of it only in the steps the widgets show (see countdownMinutes): re-rendering every minute
        // made the launcher re-inflate every widget's bitmaps that often, which shows up as lag swiping to their page.
        val signature = (listOf(repo.activeId(), countdownMinutes(s.sessionResetMs - now), startOfDayMs()) + accounts.flatMap { (a, u) ->
            listOf(a.id, a.name, a.connected, u.sessionPct, u.weeklyPct, u.opusPct, u.sonnetPct, u.connection, u.sessionResetMs, u.weeklyResetMs, u.planType, u.sessionWindow, u.opusReported, u.sonnetReported,
                countdownMinutes(u.sessionResetMs - now))
        }).joinToString()
        if (!force && signature == lastSignature) return
        lastSignature = signature
        val manager = AppWidgetManager.getInstance(context)
        listOf(SmallWidget, MediumWidget, LargeWidget).forEach { render(context, it, manager.getAppWidgetIds(ComponentName(context, it.receiver)).toList()) }
    }


    /**
     * Widgets are plain RemoteViews pushed by the app rather than Glance sessions, so the app controls which account each
     * one shows. The card itself is still composed with Glance.
     */
    internal suspend fun render(context: Context, widget: UsageWidget, ids: List<Int>) = renderLock.withLock {
        if (ids.isEmpty()) return@withLock
        val manager = AppWidgetManager.getInstance(context)
        val pages = widget.pages(context)
        ids.forEach { id ->
            val o = manager.getAppWidgetOptions(id)
            // Portrait size, as Glance used: the launcher reports min width x max height for it.
            val size = DpSize((o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: widget.defaultSize.width.value.toInt()).dp,
                (o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: widget.defaultSize.height.value.toInt()).dp)
            val index = pages.indexOfFirst { it.account?.id == shownAccount(context, id) }.coerceAtLeast(0)
            val views = runCatching { frame(context, widget, id, pages, index, size) }.onFailure { Log.w("SessionSense", "Widget render failed", it) }.getOrNull() ?: return@forEach
            manager.updateAppWidget(id, views)
        }
    }

    /** A tap on the page dots: show the next account (wrapping round) on that widget only. */
    internal suspend fun showNext(context: Context, widget: UsageWidget, id: Int) {
        val pages = widget.pages(context)
        if (pages.size < 2) return render(context, widget, listOf(id))
        val current = pages.indexOfFirst { it.account?.id == shownAccount(context, id) }.coerceAtLeast(0)
        pages[(current + 1) % pages.size].account?.let { shown(context).edit().putString("$id", it.id).apply() }
        render(context, widget, listOf(id))
    }

    internal fun forget(context: Context, ids: IntArray) = shown(context).edit().apply { ids.forEach { remove("$it") } }.apply()

    /** The account each widget was switched to; a widget never switched shows the account being viewed in the app. */
    private fun shown(context: Context) = context.getSharedPreferences("widget_accounts", Context.MODE_PRIVATE)
    private fun shownAccount(context: Context, id: Int) = shown(context).getString("$id", null)

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private suspend fun frame(context: Context, widget: UsageWidget, id: Int, pages: List<WidgetData>, index: Int, size: DpSize): RemoteViews {
        val card = GlanceRemoteViews().compose(context, size) {
            CompositionLocalProvider(LocalSize provides size) {
                Frame(context, overlay = { if (pages.size > 1) PageDots(pages.size, index) }) { with(widget) { Content(context, pages[index]) } }
            }
        }.remoteViews
        return RemoteViews(context.packageName, R.layout.widget_frame).apply {
            addView(R.id.card, card)
            setOnClickPendingIntent(R.id.card, PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            if (pages.size > 1) {
                setViewVisibility(R.id.page_next, View.VISIBLE)
                val next = Intent(context, widget.receiver).setAction(ACTION_NEXT_ACCOUNT).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                setOnClickPendingIntent(R.id.page_next, PendingIntent.getBroadcast(context, id, next, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
        }
    }

    internal const val ACTION_NEXT_ACCOUNT = "com.sessionsense.session_sense.widget.NEXT_ACCOUNT"
}

/** One widget page. [account] is set only when more than one account is tracked (its name and provider are shown then). */
internal class WidgetData(val usage: UsageSnapshot, val today: List<UsageSample>, val now: Long, val account: Account?, val provider: Provider, val claudePlan: String) {
    val codex get() = provider == Provider.CODEX
    /** Codex never reports a per-model limit, and most Claude plans don't either; then the plan fills that slot. */
    val opus get() = !codex && usage.opusReported
    val sonnet get() = !codex && usage.sonnetReported
    val plan get() = if (codex) planName(usage) else claudePlan
}

internal abstract class UsageWidget(val receiver: Class<out UsageWidgetReceiver>, val defaultSize: DpSize, private val large: Boolean = false) {
    /** One page per account, the viewed account first; with a single account there is one page and nothing to switch. */
    suspend fun pages(context: Context): List<WidgetData> {
        val repo = (context.applicationContext as SessionSenseApp).repository
        val db = (context.applicationContext as SessionSenseApp).database
        val accounts = repo.accounts.first(); val active = repo.activeId(); val p = repo.preferences(); val now = System.currentTimeMillis()
        suspend fun page(id: String, usage: UsageSnapshot, account: Account?, provider: Provider) = WidgetData(usage,
            if (large) db.samples().since(id, startOfDayMs()) else emptyList(), now, account, provider, claudePlanName(p[AccountKeys(id).PLAN] ?: "pro"))
        return if (accounts.isEmpty()) listOf(page(active, AccountKeys(active).usage(p), null, Provider.CLAUDE))
        else accounts.sortedBy { it.account.id != active }.map { (a, u) -> page(a.id, u, a.takeIf { accounts.size > 1 }, a.provider) }
    }

    @Composable abstract fun ColumnScope.Content(context: Context, d: WidgetData)
}

private object SmallWidget : UsageWidget(SessionSenseWidgetSmall::class.java, DpSize(110.dp, 110.dp)) {
    @Composable override fun ColumnScope.Content(context: Context, d: WidgetData) {
        // Scale with the cell the launcher gives the widget, so a big cell isn't a small ring in a corner.
        val s = d.usage; val size = LocalSize.current; val f = (min(size.width.value, size.height.value) / 110f).coerceIn(1f, 1.6f)
        val ring = min(size.width.value * .5f, size.height.value - 32f - 52f * f).coerceIn(52f, 150f)
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(GlanceModifier.size(ring.dp), contentAlignment = Alignment.Center) {
                // With no 5-hour window reported (Codex, sometimes), the weekly window is all there is to show.
                val (ringValue, pct) = if (s.sessionWindow) sessionRing(s) to s.sessionPct else weeklyRing(s) to s.weeklyPct
                Image(ImageProvider(rings(context, ring, listOf(ringValue), stroke = ring * .13f)), contentDescription = "${if (s.sessionWindow) "Session" else "Weekly"} $pct% used", modifier = GlanceModifier.fillMaxSize())
                Text("$pct%", style = style(WText, (ring * .19f).sp, FontWeight.Bold))
            }
            Spacer(GlanceModifier.defaultWeight())
            if (d.account != null) Column(horizontalAlignment = Alignment.End) {
                AccountBadge(d.account, s)
                Spacer(GlanceModifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderLogo(d.provider, 10)
                    Spacer(GlanceModifier.width(3.dp))
                    Text(d.provider.label.uppercase(), style = style(WMuted, 9.sp, FontWeight.Bold), maxLines = 1)
                }
            } else StatusDot(s)
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(headline(s, d.now), style = style(WText, (26 * f).sp, FontWeight.Bold), maxLines = 1)
        Text(caption(s, d.now), style = style(ColorProvider(stateColor(s)), (11 * f).sp, FontWeight.Medium), maxLines = 1)
    }
}

private object MediumWidget : UsageWidget(SessionSenseWidgetMedium::class.java, DpSize(250.dp, 110.dp)) {
    @Composable override fun ColumnScope.Content(context: Context, d: WidgetData) {
        val size = LocalSize.current; val f = (size.height.value / 110f).coerceIn(1f, 1.5f)
        // Centred in the cell: a taller cell than 2 rows would otherwise leave an empty band under the rings.
        Spacer(GlanceModifier.defaultWeight())
        RingsWithLegend(context, d, min(size.height.value - 32f, size.width.value * .42f).coerceIn(84f, 180f), f)
        Spacer(GlanceModifier.defaultWeight())
    }
}

private object LargeWidget : UsageWidget(SessionSenseWidgetLarge::class.java, DpSize(250.dp, 250.dp), large = true) {
    @Composable override fun ColumnScope.Content(context: Context, d: WidgetData) {
        val size = LocalSize.current; val s = d.usage
        RingsWithLegend(context, d, min(size.height.value - 196f, size.width.value * .42f).coerceIn(104f, 180f)) // fill the space above the chart
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text("TODAY", modifier = GlanceModifier.defaultWeight(), style = style(WFaint, 11.sp, FontWeight.Bold))
            Text("peak ${maxOf(d.today.maxOfOrNull { it.sessionPct } ?: 0, s.sessionPct)}%", style = style(WMuted, 11.sp, FontWeight.Medium))
        }
        Spacer(GlanceModifier.height(6.dp))
        val chartW = (size.width.value - 32f).coerceAtLeast(120f)
        Image(ImageProvider(todayCurve(context, chartW, 64f, d.today, d.now)), contentDescription = "Today's session usage", modifier = GlanceModifier.fillMaxWidth().height(64.dp), contentScale = ContentScale.FillBounds)
        Spacer(GlanceModifier.height(12.dp))
        Row(GlanceModifier.fillMaxWidth()) {
            Footer("RESETS", if (s.sessionPct > 0 && s.sessionResetMs > d.now) clockOf(s.sessionResetMs, "h:mm a") else "—", GlanceModifier.defaultWeight())
            Footer("WEEKLY RESET", if (s.weeklyResetMs > d.now) clockOf(s.weeklyResetMs, "EEE h a") else "—", GlanceModifier.defaultWeight())
            if (d.sonnet) Footer("SONNET", "${s.sonnetPct}%", GlanceModifier.defaultWeight()) else Footer("PLAN", d.plan, GlanceModifier.defaultWeight())
        }
    }
}

/** Renders on the launcher's own triggers (placement, the 30-minute update, a resize) and page-dot taps; the app pushes the rest. */
abstract class UsageWidgetReceiver : AppWidgetProvider() {
    internal abstract val widget: UsageWidget

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SessionSenseWidgets.ACTION_NEXT_ACCOUNT) return super.onReceive(context, intent)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) async { SessionSenseWidgets.showNext(context, widget, id) }
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = async { SessionSenseWidgets.render(context, widget, ids.toList()) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = async { SessionSenseWidgets.render(context, widget, listOf(id)) }
    override fun onDeleted(context: Context, ids: IntArray) = SessionSenseWidgets.forget(context, ids)

    private fun async(block: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch { try { block() } finally { pending.finish() } }
    }
}

class SessionSenseWidgetSmall : UsageWidgetReceiver() { override val widget: UsageWidget get() = SmallWidget }
class SessionSenseWidgetMedium : UsageWidgetReceiver() { override val widget: UsageWidget get() = MediumWidget }
class SessionSenseWidgetLarge : UsageWidgetReceiver() { override val widget: UsageWidget get() = LargeWidget }

// ─── Shared pieces ─────────────────────────────────────────────────────────────────────────────────────

/** The dark card every widget page sits on; [overlay] is drawn on top, centred on the right edge (the page dots). */
@Composable private fun Frame(context: Context, overlay: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Box(GlanceModifier.fillMaxSize().cornerRadius(26.dp).background(WBg), contentAlignment = Alignment.CenterEnd) {
        Image(ImageProvider(backdrop(context)), contentDescription = null, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Column(GlanceModifier.fillMaxSize().padding(16.dp), content = content)
        overlay()
    }
}

/** Which account the widget shows, one dot each; tapping them (see widget_frame.xml) switches to the next. */
@Composable private fun PageDots(count: Int, current: Int) = Column(GlanceModifier.padding(end = 6.dp)) {
    repeat(count) { i ->
        if (i > 0) Spacer(GlanceModifier.height(4.dp))
        Box(GlanceModifier.size(4.dp).cornerRadius(2.dp).background(if (i == current) Text else Faint.copy(alpha = .4f))) {}
    }
}

/** [f] scales the legend text along with the ring. */
@Composable private fun RingsWithLegend(context: Context, d: WidgetData, ring: Float, f: Float = 1f) {
    val s = d.usage
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Without a reported Opus limit the third ring is dropped and the plan takes the Opus row.
        val shown = if (d.opus) listOf(sessionRing(s), weeklyRing(s), Ring(s.opusPct / 100f, lerp(Amber, Text, .3f), Amber)) else listOf(sessionRing(s), weeklyRing(s))
        Image(ImageProvider(rings(context, ring, shown, stroke = ring * .105f)),
            contentDescription = if (d.opus) "Session ${s.sessionPct}%, weekly ${s.weeklyPct}%, Opus ${s.opusPct}%" else "Session ${s.sessionPct}%, weekly ${s.weeklyPct}%", modifier = GlanceModifier.size(ring.dp))
        Spacer(GlanceModifier.width(16.dp))
        Column(GlanceModifier.defaultWeight()) {
            val session = if (d.account != null) "${d.provider.label} · ${d.account.name}" else "Session"
            if (d.account != null) Row(verticalAlignment = Alignment.CenterVertically) { ProviderLogo(d.provider, 11); Spacer(GlanceModifier.width(4.dp)); Legend(session, null, null, stateColor(s), f) }
            if (s.sessionWindow) Legend(if (d.account != null) null else session, "${s.sessionPct}%", if (s.sessionPct > 0 && s.sessionResetMs > d.now) "${span(remaining(s, d.now))} left" else "ready", stateColor(s), f)
            else Legend(if (d.account != null) null else session, "—", "no 5h window", Faint, f)
            Spacer(GlanceModifier.height((6 * f).dp))
            Legend("Weekly", "${s.weeklyPct}%", if (s.weeklyResetMs > d.now) "resets ${clockOf(s.weeklyResetMs, "EEE")}" else null, Blue, f)
            Spacer(GlanceModifier.height((6 * f).dp))
            if (d.opus) Legend("Opus", "${s.opusPct}%", null, Amber, f) else Legend("Plan", d.plan, null, Text, f)
        }
    }
}

/** A null [label] or [value] leaves that line out (the session label is drawn beside the provider logo instead). */
@Composable private fun Legend(label: String?, value: String?, detail: String?, color: Color, f: Float = 1f) {
    if (label != null) Text(label.uppercase(), style = style(WMuted, (10 * f).sp, FontWeight.Bold), maxLines = 1)
    if (value != null) Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = style(ColorProvider(color), (19 * f).sp, FontWeight.Bold))
        if (detail != null) Text("  $detail", style = style(WMuted, (11 * f).sp, FontWeight.Medium), maxLines = 1)
    }
}

@Composable private fun Footer(label: String, value: String, modifier: GlanceModifier) = Column(modifier) {
    Text(value, style = style(WText, 13.sp, FontWeight.Bold), maxLines = 1)
    Text(label, style = style(WFaint, 9.sp, FontWeight.Bold), maxLines = 1)
}

@Composable private fun AccountBadge(account: Account, s: UsageSnapshot) {
    Box(GlanceModifier.size(22.dp).cornerRadius(11.dp).background(stateColor(s).copy(alpha = .22f)), contentAlignment = Alignment.Center) {
        Text(account.initial, style = style(ColorProvider(stateColor(s)), 11.sp, FontWeight.Bold))
    }
}

@Composable private fun ProviderLogo(p: Provider, sizeDp: Int) = Image(
    ImageProvider(if (p == Provider.CODEX) R.drawable.ic_provider_codex else R.drawable.ic_provider_claude), contentDescription = p.label,
    modifier = GlanceModifier.size(sizeDp.dp))

@Composable private fun StatusDot(s: UsageSnapshot) {
    val color = when (s.connection) { "connected" -> Teal; "expired" -> Coral; else -> Faint }
    Box(GlanceModifier.size(8.dp).cornerRadius(4.dp).background(color)) {}
}

private fun style(color: ColorProvider, size: TextUnit, weight: FontWeight) = TextStyle(color = color, fontSize = size, fontWeight = weight)

private fun stateColor(s: UsageSnapshot) = when { s.connection == "expired" -> Coral; s.sessionPct >= 85 -> Coral; s.sessionPct >= 60 -> Amber; else -> Teal }

private fun sessionRing(s: UsageSnapshot) = when {
    s.sessionPct >= 85 -> Ring(s.sessionPct / 100f, Amber, Coral)
    s.sessionPct >= 60 -> Ring(s.sessionPct / 100f, Teal, Amber)
    else -> Ring(s.sessionPct / 100f, Teal, lerp(Teal, Violet, .45f))
}

private fun weeklyRing(s: UsageSnapshot) = Ring(s.weeklyPct / 100f, lerp(Blue, Text, .35f), Blue)
private fun planName(s: UsageSnapshot) = s.planType.trim().ifEmpty { "—" }.split('_', ' ').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

private fun remaining(s: UsageSnapshot, now: Long) = (s.sessionResetMs - now).coerceAtLeast(0)
private fun span(ms: Long): String { val m = countdownMinutes(ms); return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" }

/** Minutes left as widgets show them: in 5-minute steps (rounded down) until the last half hour, then every minute. */
internal fun countdownMinutes(ms: Long): Long { val m = ms.coerceAtLeast(0) / 60_000; return if (m >= 30) m / 5 * 5 else m }
private fun headline(s: UsageSnapshot, now: Long) = when {
    s.connection == "expired" -> "Reconnect"
    !s.sessionWindow -> "Weekly"
    s.sessionPct > 0 && s.sessionResetMs > now -> span(remaining(s, now))
    else -> "Full 5h"
}
private fun caption(s: UsageSnapshot, now: Long) = when {
    s.connection == "expired" -> "session expired"
    !s.sessionWindow -> if (s.weeklyResetMs > now) "resets ${clockOf(s.weeklyResetMs, "EEE h a")}" else "no 5-hour window"
    s.sessionPct > 0 && s.sessionResetMs > now -> "left · resets ${clockOf(s.sessionResetMs, "h:mm a")}"
    else -> "ready to go"
}
// resets_at jitters by a second or two between polls; round so 12:19:59 and 12:20:00 read the same.
private fun clockOf(ms: Long, pattern: String) = Instant.ofEpochMilli((ms + 30_000) / 60_000 * 60_000).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))

// ─── Bitmap rendering (Glance has no Canvas, so gradients and rings are drawn once per update) ─────────

private class Ring(val progress: Float, val start: Color, val end: Color)

/**
 * Widget bitmaps are parcelled to the launcher and decoded there on every update, so they're drawn at no more than 2.5x:
 * sharp on any phone screen, at about two thirds the memory of 3x+ and without the scroll jank on the widget's page.
 */
private fun bitmapDensity(context: Context) = min(context.resources.displayMetrics.density, 2.5f)

/** Concentric Fitness-style rings, outermost first: tinted track, sweep-gradient arc, glowing tip. */
private fun rings(context: Context, sizeDp: Float, rings: List<Ring>, stroke: Float, gap: Float = stroke * .28f): Bitmap {
    val d = bitmapDensity(context)
    val px = (sizeDp * d).roundToInt().coerceIn(16, 450)
    val bmp = createBitmap(px, px); val c = android.graphics.Canvas(bmp)
    val w = stroke * d; val cx = px / 2f; val margin = w * .25f
    rings.forEachIndexed { i, ring ->
        val r = px / 2f - margin - w / 2f - i * (w + gap * d)
        if (r <= w / 2f) return@forEachIndexed
        c.drawCircle(cx, cx, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w; color = ring.start.copy(alpha = .2f).toArgb() })
        val p = ring.progress.coerceIn(0f, 1f); if (p < .005f) return@forEachIndexed
        val cap = Math.toDegrees((w / 2f / r).toDouble()).toFloat(); val sweep = 360f * p
        val stop = ((cap + sweep) / 360f).coerceIn(.01f, 1f)
        val gradient = SweepGradient(cx, cx, intArrayOf(ring.start.toArgb(), ring.end.toArgb(), ring.end.toArgb()), floatArrayOf(0f, stop, 1f))
            .apply { setLocalMatrix(Matrix().apply { setRotate(-90f - cap, cx, cx) }) }
        c.drawArc(RectF(cx - r, cx - r, cx + r, cx + r), -90f, sweep, false, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND; shader = gradient })
        val a = Math.toRadians((sweep - 90f).toDouble()); val tx = cx + r * cos(a).toFloat(); val ty = cx + r * sin(a).toFloat()
        if (p > .85f) c.drawCircle(tx, ty, w * .85f, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(tx, ty, w * .85f, Color.Black.copy(alpha = .45f).toArgb(), 0, Shader.TileMode.CLAMP) })
        c.drawCircle(tx, ty, w * 1.1f, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(tx, ty, w * 1.1f, ring.end.copy(alpha = .55f).toArgb(), 0, Shader.TileMode.CLAMP) })
        c.drawCircle(tx, ty, w / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ring.end.toArgb() })
        c.drawCircle(tx, ty, w * .15f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.White.copy(alpha = .5f).toArgb() })
    }
    return bmp
}

/** Today's 5-hour session % on a fixed 24h axis, as a step curve with a gradient fill. */
private fun todayCurve(context: Context, widthDp: Float, heightDp: Float, samples: List<UsageSample>, now: Long): Bitmap {
    val d = bitmapDensity(context)
    val w = (widthDp * d).roundToInt().coerceIn(32, 900); val h = (heightDp * d).roundToInt().coerceIn(16, 240)
    val bmp = createBitmap(w, h); val c = android.graphics.Canvas(bmp)
    val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.White.copy(alpha = .07f).toArgb(); strokeWidth = d }
    listOf(.5f, 1f).forEach { f -> val y = (h - d) * f; c.drawLine(0f, y, w.toFloat(), y, grid) }
    val start = startOfDayMs(); val pad = 3 * d
    fun x(ts: Long) = ((ts - start) / 86_400_000f).coerceIn(0f, 1f) * (w - pad * 2) + pad
    fun y(pct: Int) = h - pad - pct / 100f * (h - pad * 2)
    // "Now" marker so an empty morning still reads as a timeline.
    c.drawLine(x(now), 0f, x(now), h.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.White.copy(alpha = .10f).toArgb(); strokeWidth = d })
    if (samples.isEmpty()) return bmp
    val line = android.graphics.Path(); var lastY = y(samples.first().sessionPct)
    line.moveTo(x(samples.first().ts), lastY)
    samples.drop(1).forEach { val px = x(it.ts); line.lineTo(px, lastY); lastY = y(it.sessionPct); line.lineTo(px, lastY) }
    val endX = x(now); line.lineTo(endX, lastY)
    val area = android.graphics.Path(line).apply { lineTo(endX, h.toFloat()); lineTo(x(samples.first().ts), h.toFloat()); close() }
    c.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Teal.copy(alpha = .35f).toArgb(), 0, Shader.TileMode.CLAMP) })
    c.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        shader = LinearGradient(0f, 0f, w.toFloat(), 0f, Teal.toArgb(), Blue.toArgb(), Shader.TileMode.CLAMP) })
    c.drawCircle(endX, lastY, 7 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(endX, lastY, 7 * d, Blue.copy(alpha = .55f).toArgb(), 0, Shader.TileMode.CLAMP) })
    c.drawCircle(endX, lastY, 3 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Blue.toArgb() })
    return bmp
}

/** Soft teal wash from the top-left, behind the ring — the same single-glow language as the app backdrop. */
private fun backdrop(context: Context): Bitmap {
    val px = 160
    val bmp = createBitmap(px, px); val c = android.graphics.Canvas(bmp)
    c.drawColor(WBg.toArgb())
    c.drawCircle(px * .22f, px * .2f, px * 1.1f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(px * .22f, px * .2f, px * 1.1f, intArrayOf(Teal.copy(alpha = .16f).toArgb(), Teal.copy(alpha = .05f).toArgb(), 0), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
    })
    // Hairline top highlight, like the app's glass cards.
    c.drawLine(0f, 0.5f, px.toFloat(), 0.5f, Paint().apply { color = Color.White.copy(alpha = .08f).toArgb() })
    return bmp
}
