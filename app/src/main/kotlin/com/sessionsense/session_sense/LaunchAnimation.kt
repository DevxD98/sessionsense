package com.sessionsense.session_sense

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where Home's session ring sits (root coordinates), so the launch animation can land its ring exactly on it. */
internal val LocalHeroRing = staticCompositionLocalOf<MutableState<Rect?>?> { null }

/** The Home session ring's gradient for a session state, shared by SessionHero and the launch hand-off. */
internal fun heroColors(sessionState: String) = when (sessionState) { "danger" -> listOf(Amber, Coral); "warning" -> listOf(Teal, Amber); else -> listOf(Teal, Blue) }

/**
 * The app icon's three rings in its 108-unit adaptive-icon grid (res/drawable/ic_launcher_foreground.xml):
 * radius, progress and gradient. Stroke is [ICON_STROKE] units for all three.
 */
private class IconRing(val radius: Float, val progress: Float, val start: Color, val end: Color)
private val IconRings = listOf(
    IconRing(24f, .76f, Color(0xFFA6F6E7), Teal),
    IconRing(16f, .59f, Color(0xFFC9DBFF), Blue),
    IconRing(8f, .79f, Color(0xFFFBE3B6), Amber),
)
private const val ICON_STROKE = 6.8f
private const val ICON_OUTER = 2 * 24f + ICON_STROKE // outer ring's diameter in icon units

/**
 * Launch: the icon's rings draw themselves in, the name rises beneath, then the outer ring glides and grows into
 * Home's session ring (its size, stroke, colours and real progress) while everything else fades, revealing Home.
 * Tap to skip; with animations turned off in system settings it finishes at once. Home is composed underneath the
 * whole time, so [hero] is known by the time the hand-off starts (null when opening elsewhere: then it just fades).
 */
@Composable internal fun LaunchAnimation(hero: Rect?, heroProgress: Float, heroGradient: List<Color>, onFinished: () -> Unit) {
    val context = LocalContext.current
    val reduceMotion = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val draw = remember { IconRings.map { Animatable(0f) } }
    val tracks = remember { Animatable(0f) }
    val words = remember { Animatable(0f) }
    val morph = remember { Animatable(0f) }
    var skip by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (reduceMotion) { onFinished(); return@LaunchedEffect }
        launch { tracks.animateTo(1f, tween(320, easing = LinearOutSlowInEasing)) }
        IconRings.forEachIndexed { i, ring -> launch { delay(120L + i * 90L); draw[i].animateTo(ring.progress, spring(dampingRatio = .8f, stiffness = 120f)) } }
        launch { delay(560); words.animateTo(1f, spring(dampingRatio = .9f, stiffness = Spring.StiffnessLow)) }
        withTimeoutOrNull(1_450) { snapshotFlow { skip }.first { it } }
        // Apple's "emphasized" curve: quick to leave, long settle.
        morph.animateTo(1f, tween(720, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)))
        onFinished()
    }

    val density = LocalDensity.current
    val m = morph.value
    // The stage fades only once the ring has all but landed (the easing is ~98% there by .82), so Home's own ring
    // never shows beside the moving one.
    val fade = ((m - .82f) / .18f).coerceIn(0f, 1f)
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { skip = true } }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Bg, alpha = 1f - fade)
            val base = Offset(center.x, center.y - 56.dp.toPx())
            drawRect(Brush.radialGradient(listOf(Teal.copy(alpha = .16f * (1f - m)), Color.Transparent), base, size.width * .8f))
            val unit = 212.dp.toPx() / ICON_OUTER
            IconRings.forEachIndexed { i, ring ->
                val d0 = (2 * ring.radius + ICON_STROKE) * unit
                val w0 = ICON_STROKE * unit
                if (i == 0) {
                    // The outer ring becomes Home's session ring.
                    val target = hero
                    val c = if (target != null) lerp(base, target.center, m) else base
                    val d = if (target != null) lerp(d0, target.width, m) else d0 * (1f + .12f * m)
                    val w = lerp(w0, if (target != null) 24.dp.toPx() else w0, m)
                    val p = lerp(draw[0].value, if (target != null) heroProgress else draw[0].value, m)
                    val start = lerp(ring.start, heroGradient[0], m); val end = lerp(ring.end, heroGradient[1], m)
                    drawActivityRing(c, d, w, p, start, end, alpha = if (target != null) tracks.value else tracks.value * (1f - fade))
                } else {
                    // The inner rings fold into the centre and fade as the outer one leaves.
                    val shrink = 1f - .35f * m
                    drawActivityRing(base, d0 * shrink, w0 * shrink, draw[i].value, ring.start, ring.end, alpha = tracks.value * (1f - (m * 1.8f).coerceAtMost(1f)))
                }
            }
        }
        Column(Modifier.offset(y = with(density) { 104.dp }).graphicsLayer {
            val a = words.value * (1f - (m * 2.5f).coerceAtMost(1f))
            alpha = a; translationY = (1f - words.value) * 18.dp.toPx() - m * 12.dp.toPx()
        }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SessionSense", style = MaterialTheme.typography.headlineLarge, color = Text, fontSize = 34.sp, letterSpacing = (-.6).sp)
            Spacer(Modifier.height(6.dp))
            Text("tune in to your session", color = Muted, fontFamily = PlexMono, fontSize = 13.sp)
        }
    }
}
