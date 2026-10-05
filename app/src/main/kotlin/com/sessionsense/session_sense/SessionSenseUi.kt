package com.sessionsense.session_sense

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.*
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.*

// outfit.ttf is a variable font whose default instance is Thin (100); pin each weight to its wght axis.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun outfit(weight: Int) = Font(R.font.outfit, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
internal val Outfit = FontFamily(outfit(300), outfit(400), outfit(500), outfit(600), outfit(700))
internal val PlexMono = FontFamily(Font(R.font.ibm_plex_mono))

// Muted ≈ 9.5:1 and Faint ≈ 5.6:1 against Bg; both stay above 4.5:1 on the frosted cards.
val Bg = Color(0xFF0B0B0D); val Surface = Color(0xFF16161A); val Surface2 = Color(0xFF24242A)
val Text = Color(0xFFF2F2F3); val Muted = Color(0xFFB4B4BB); val Faint = Color(0xFF8E8E96)
val Teal = Color(0xFF6EE7D0); val Amber = Color(0xFFF4C77A); val Coral = Color(0xFFF48A7A); val Blue = Color(0xFF8BB4FF)
internal val Hairline = Color.White.copy(alpha = .09f)
private val TealContainer = Teal.copy(alpha = .20f).compositeOver(Surface)

// Spacing scale: 8 / 12 / 16 / 24 / 32.
internal val S1 = 8.dp; internal val S2 = 12.dp; internal val S3 = 16.dp; internal val S4 = 24.dp; internal val S5 = 32.dp
internal val Gutter = 20.dp
private val TabBarHeight = 64.dp

private val CardGlass = HazeStyle(backgroundColor = Bg, tint = HazeTint(Surface.copy(alpha = .60f)), blurRadius = 24.dp, noiseFactor = .04f, fallbackTint = HazeTint(Surface.copy(alpha = .92f)))
// Liquid glass for the tab bar only: a light, see-through tint over a heavy blur, so content reads through it.
private val BarGlass = HazeStyle(backgroundColor = Bg, tints = listOf(HazeTint(Surface.copy(alpha = .30f)), HazeTint(Color.White.copy(alpha = .05f))),
    blurRadius = 30.dp, noiseFactor = .03f, fallbackTint = HazeTint(Surface.copy(alpha = .94f)))

private val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }
private val LocalRingAnchor = staticCompositionLocalOf<MutableState<Offset?>?> { null }

internal fun <T> ringSpring() = spring<T>(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)
internal fun <T> smooth(visibilityThreshold: T? = null) = spring(dampingRatio = .82f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = visibilityThreshold)

@Composable fun SessionSenseTheme(content: @Composable () -> Unit) {
    // MaterialKolor still supplies the tonal ramp, but every role a stock component can pick up is pinned to
    // the brand palette so nothing (nav indicators, Switch, RadioButton, FilterChip, sheets) drifts to a computed blue.
    val scheme = rememberDynamicColorScheme(seedColor = Teal, isDark = true, isAmoled = true,
        primary = Teal, secondary = Blue, tertiary = Amber, error = Coral, style = PaletteStyle.Expressive)
        .copy(primary = Teal, onPrimary = Bg, primaryContainer = TealContainer, onPrimaryContainer = Teal, inversePrimary = Teal,
            secondary = Teal, onSecondary = Bg, secondaryContainer = TealContainer, onSecondaryContainer = Teal,
            tertiary = Amber, onTertiary = Bg, error = Coral, onError = Bg,
            background = Bg, onBackground = Text, surface = Surface, onSurface = Text, surfaceVariant = Surface2, onSurfaceVariant = Muted,
            surfaceTint = Color.Transparent, outline = Faint, outlineVariant = Surface2,
            surfaceContainerLowest = Bg, surfaceContainerLow = Surface, surfaceContainer = Surface, surfaceContainerHigh = Surface2, surfaceContainerHighest = Surface2)
    val base = Typography()
    MaterialTheme(colorScheme = scheme, typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = PlexMono),
        headlineLarge = base.headlineLarge.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = Outfit, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontFamily = Outfit),
        bodyMedium = base.bodyMedium.copy(fontFamily = Outfit),
        bodySmall = base.bodySmall.copy(fontFamily = Outfit),
        labelLarge = base.labelLarge.copy(fontFamily = Outfit, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontFamily = Outfit),
        labelSmall = base.labelSmall.copy(fontFamily = Outfit),
    )) {
        // Text outside a Surface otherwise inherits an unspecified content colour and renders black on Bg.
        CompositionLocalProvider(LocalContentColor provides Text, LocalTextStyle provides TextStyle(fontFamily = Outfit, color = Text), content = content)
    }
}

@Composable fun SessionSenseRoot(vm: AppViewModel, launchAction: String?, onLaunchActionHandled: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var splash by rememberSaveable { mutableStateOf(true) }
    val hero = remember { mutableStateOf<Rect?>(null) }
    // The app runs underneath the launch animation from the first frame, so its ring can land on Home's.
    CompositionLocalProvider(LocalHeroRing provides hero) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            UpdateHost(vm.updates, launchAction == ACTION_OPEN_UPDATE, onLaunchActionHandled) {
                if (state.settings.route == "onboarding") Onboarding(vm, state)
                else MainShell(vm, state, if (launchAction == "com.sessionsense.OPEN_HISTORY") "history" else if (launchAction == "com.sessionsense.RECONNECT") "settings" else "home")
            }
            if (splash) LaunchAnimation(hero.value, state.usage.sessionPct / 100f, heroColors(state.sessionState)) { splash = false }
        }
    }
}

// ─── Shared building blocks ────────────────────────────────────────────────────────────────────────────

@Composable private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    return animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .55f, stiffness = Spring.StiffnessMedium), label = "press").value
}

/** Clickable surface with the Flutter app's Pressable feel: scales to 0.97 while held, springs back on release. */
@Composable internal fun Pressable(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, role: Role = Role.Button, contentAlignment: Alignment = Alignment.TopStart, content: @Composable BoxScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.then(modifier).clickable(source, indication = null, enabled = enabled, role = role, onClick = onClick), contentAlignment = contentAlignment, content = content)
}

enum class CapsuleStyle { Primary, Secondary, Destructive }

@Composable internal fun CapsuleButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: CapsuleStyle = CapsuleStyle.Primary, enabled: Boolean = true, haptic: HapticFeedbackType? = null) {
    val haptics = LocalHapticFeedback.current
    val fill: Brush; val content: Color; val edge: Color
    when (style) {
        CapsuleStyle.Primary -> { fill = Brush.horizontalGradient(listOf(Teal, lerp(Teal, Blue, .35f))); content = Bg; edge = Color.Transparent }
        CapsuleStyle.Secondary -> { fill = SolidColor(Color.White.copy(alpha = .06f)); content = Text; edge = Hairline }
        CapsuleStyle.Destructive -> { fill = SolidColor(Coral.copy(alpha = .12f)); content = Coral; edge = Coral.copy(alpha = .35f) }
    }
    Pressable(onClick = { haptic?.let(haptics::performHapticFeedback); onClick() },
        modifier.fillMaxWidth().heightIn(min = 54.dp).alpha(if (enabled) 1f else .5f).clip(CircleShape).background(fill).border(1.dp, edge, CircleShape),
        enabled = enabled, contentAlignment = Alignment.Center) {
        Text(text, Modifier.padding(horizontal = Gutter, vertical = 14.dp), color = content, fontFamily = Outfit, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Apple Fitness-style activity ring: a tinted track, a gradient stroke along the sweep, and a glowing tip.
 * Progress springs (medium-bouncy) rather than tweening, including on first appearance.
 */
@Composable private fun ActivityRing(progress: Float, colors: List<Color>, stroke: Dp, modifier: Modifier = Modifier, glow: Boolean = true) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(progress) { anim.animateTo(progress.coerceIn(0f, 1f), ringSpring()) }
    val start by animateColorAsState(colors[0], smooth(), label = "ringStart")
    val end by animateColorAsState(colors[1], smooth(), label = "ringEnd")
    Canvas(modifier) { drawActivityRing(center, size.minDimension, stroke.toPx(), anim.value, start, end, glow) }
}

/** One activity ring of outer [diameter] and stroke [w] around [c]: track, gradient sweep, glowing tip. Shared with the launch animation. */
internal fun DrawScope.drawActivityRing(c: Offset, diameter: Float, w: Float, progress: Float, start: Color, end: Color, glow: Boolean = true, alpha: Float = 1f) {
    val r = (diameter - w) / 2f
    if (r <= 0f || alpha <= 0f) return
    val topLeft = Offset(c.x - r, c.y - r); val arc = Size(r * 2, r * 2)
    drawCircle(start.copy(alpha = .14f * alpha), r, c, style = Stroke(w))
    val p = progress.coerceIn(0f, 1f)
    if (p < .002f) return
    // Start the gradient half a cap early so the rounded start cap is the start colour, not the wrapped end colour.
    val cap = Math.toDegrees((w / 2f / r).toDouble()).toFloat(); val sweep = 360f * p
    rotate(-90f - cap, c) {
        val stop = ((cap + sweep) / 360f).coerceIn(.01f, 1f)
        drawArc(Brush.sweepGradient(0f to start, stop to end, 1f to end, center = c), cap, sweep, false, topLeft, arc, alpha = alpha, style = Stroke(w, cap = StrokeCap.Round))
    }
    val a = Math.toRadians((sweep - 90f).toDouble())
    val tip = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
    // Near a full lap the tip overlaps the start cap; a soft shadow keeps the overlap readable.
    if (p > .85f) drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = .45f * alpha), Color.Transparent), tip, w * .85f), w * .85f, tip)
    if (glow) drawCircle(Brush.radialGradient(listOf(end.copy(alpha = .6f * alpha), Color.Transparent), tip, w * 1.6f), w * 1.6f, tip)
    drawCircle(end.copy(alpha = alpha), w / 2f, tip)
    drawCircle(Color.White.copy(alpha = .5f * alpha), w * .15f, tip)
}

/** A single radial wash anchored behind the hero ring (or screen-top when there is no ring), fading to the base colour. */
@Composable internal fun Backdrop(modifier: Modifier, glow: Color, intensity: Float, anchor: (Size) -> Offset?) = Canvas(modifier) {
    drawRect(Bg)
    val c = anchor(size) ?: Offset(size.width / 2f, size.height * .36f)
    drawRect(Brush.radialGradient(0f to glow.copy(alpha = .22f * intensity), .4f to glow.copy(alpha = .08f * intensity), 1f to Color.Transparent, center = c, radius = size.width * .95f))
}

@Composable internal fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = S3, horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp); val haze = LocalHazeState.current
    val glass = if (haze != null) Modifier.hazeEffect(haze, CardGlass) else Modifier.background(Surface.copy(alpha = .9f))
    val body = Modifier.fillMaxWidth().clip(shape).then(glass).border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .13f), Color.White.copy(alpha = .04f))), shape).padding(padding)
    if (onClick == null) Column(modifier.then(body), verticalArrangement, horizontalAlignment, content)
    else Pressable(onClick, modifier) { Column(body, verticalArrangement, horizontalAlignment, content) }
}

@Composable internal fun Label(value: String, modifier: Modifier = Modifier, color: Color = Faint) = Text(value, modifier, color = color, fontFamily = Outfit, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
@Composable internal fun SectionLabel(value: String) { Label(value, modifier = Modifier.padding(start = 4.dp, bottom = S2)) }
@Composable internal fun Banner(value: String, color: Color) = Text(value, color = color, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth().padding(bottom = S3).background(color.copy(.12f), RoundedCornerShape(16.dp)).border(1.dp, color.copy(.3f), RoundedCornerShape(16.dp)).padding(S2))

@Composable private fun Page(title: String, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) = Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gutter)) {
    Spacer(Modifier.height(S3))
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, color = Text); trailing() }
    Spacer(Modifier.height(S4)); content()
    // Clear the floating tab bar.
    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)); Spacer(Modifier.height(TabBarHeight + S5 + S3))
}

private fun stateColor(state: String) = when (state) { "danger" -> Coral; "warning" -> Amber; else -> Teal }

// ─── Onboarding ────────────────────────────────────────────────────────────────────────────────────────

@Composable private fun Onboarding(vm: AppViewModel, s: AppUiState) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    // null = no login sheet; "first" reuses any claude.ai session in the WebView, "fresh" signs in a different account.
    var auth by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val finish: () -> Unit = { vm.route("home") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }
    BackHandler(enabled = page > 0 && auth == null) { page-- }
    if (auth != null) {
        ClaudeAuth(onConnected = { login -> vm.connected(login).also { if (it == null) { auth = null; page = 2 } } }, onClose = { auth = null }, freshLogin = auth == "fresh")
        return
    }
    val existing = s.activeAccount?.takeIf { it.connected }
    Box(Modifier.fillMaxSize().background(Bg)) {
        Backdrop(Modifier.matchParentSize(), Teal, .75f) { Offset(it.width / 2f, it.height * .22f) }
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp)) {
            Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.CenterStart) {
                androidx.compose.animation.AnimatedVisibility(page > 0, enter = fadeIn(smooth()) + scaleIn(smooth(), initialScale = .8f), exit = fadeOut(smooth()) + scaleOut(smooth(), targetScale = .8f)) {
                    Pressable({ page-- }, Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .07f)), contentAlignment = Alignment.Center) { Glyph(GlyphKind.Back, Text, Modifier.size(18.dp)) }
                }
            }
            AnimatedContent(page, Modifier.weight(1f), transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(smooth(IntOffset.VisibilityThreshold)) { dir * it / 5 } + fadeIn(smooth())) togetherWith (slideOutHorizontally(smooth(IntOffset.VisibilityThreshold)) { -dir * it / 5 } + fadeOut(spring(stiffness = Spring.StiffnessMedium)))
            }, label = "onboarding") { p ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (p) {
                        0 -> WelcomePage()
                        1 -> ConnectPage(existing)
                        else -> AlertsPage()
                    }
                }
            }
            PageDots(page, 3, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(S4))
            when (page) {
                0 -> CapsuleButton("Continue", { page = 1 })
                1 -> if (existing != null) CapsuleButton("Continue as ${existing.name}", { page = 2 }) else CapsuleButton("Sign in with Claude", { auth = "first" }, haptic = HapticFeedbackType.ContextClick)
                else -> CapsuleButton("Turn On Alerts", {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else finish()
                }, haptic = HapticFeedbackType.Confirm)
            }
            // Fixed-height slot so the primary button never jumps between pages.
            Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                val secondary: Pair<String, () -> Unit>? = when {
                    page == 1 && existing != null && s.canAddAccount -> "Use a different account" to { auth = "fresh" }
                    page == 2 -> "Not Now" to finish
                    else -> null
                }
                if (secondary != null) Pressable(secondary.second, Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) { Text(secondary.first, color = Teal, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@Composable private fun WelcomePage() {
    Spacer(Modifier.height(S3))
    Reveal(0) {
        Box(Modifier.size(156.dp), contentAlignment = Alignment.Center) {
            ActivityRing(.74f, listOf(Teal, Blue), 16.dp, Modifier.size(156.dp))
            ActivityRing(.52f, listOf(lerp(Blue, Text, .35f), Blue), 16.dp, Modifier.size(116.dp))
            ActivityRing(.88f, listOf(lerp(Amber, Text, .3f), Amber), 16.dp, Modifier.size(76.dp))
        }
    }
    Spacer(Modifier.height(S5))
    Reveal(1) { OnboardingTitle("Welcome to\nSessionSense") }
    Spacer(Modifier.height(S5 + S1))
    Column(verticalArrangement = Arrangement.spacedBy(S4)) {
        Reveal(2) { FeatureRow(GlyphKind.Ring, Teal, "Your live 5-hour window", "See exactly how much of the current session is used and when it resets.") }
        Reveal(3) { FeatureRow(GlyphKind.Bell, Amber, "Alerts before you hit a wall", "Heads-ups as a window runs out, when it resets, and when weekly quota gets tight.") }
        Reveal(4) { FeatureRow(GlyphKind.Chart, Blue, "History that sticks", "Every session and usage reading, saved on this device.") }
    }
    Spacer(Modifier.height(S4))
}

@Composable private fun ConnectPage(existing: Account?) {
    Spacer(Modifier.height(S3))
    Reveal(0) {
        Box(Modifier.size(92.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Teal, lerp(Teal, Blue, .6f)))), contentAlignment = Alignment.Center) {
            Glyph(GlyphKind.Lock, Bg, Modifier.size(44.dp))
        }
    }
    Spacer(Modifier.height(S5))
    Reveal(1) { OnboardingTitle(if (existing != null) "You’re signed in" else "Connect your\nClaude account") }
    Spacer(Modifier.height(S2))
    Reveal(2) {
        Text(if (existing != null) "SessionSense is already tracking ${existing.email ?: existing.name}." else "Works with Claude Pro, Team and Max.",
            color = Muted, fontSize = 16.sp, lineHeight = 23.sp, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(S5))
    Column(verticalArrangement = Arrangement.spacedBy(S4)) {
        Reveal(3) { FeatureRow(GlyphKind.Globe, Teal, "Official claude.ai sign-in", "You log in on claude.ai itself. SessionSense never sees your password.") }
        Reveal(4) { FeatureRow(GlyphKind.Shield, Blue, "Encrypted on this device", "Only the session cookie and organisation ID are stored, and they never leave your phone.") }
        Reveal(5) { FeatureRow(GlyphKind.People, Amber, "Up to ${CredentialStore.MAX_ACCOUNTS} accounts", "Add a work and a personal account later and switch between them in a tap.") }
        Reveal(6) { FeatureRow(GlyphKind.Plus, Text, logo = Provider.CODEX, title = "Codex limits too", body = "Use Codex on a ChatGPT plan? Add that account from the account switcher to track its 5-hour and weekly limits alongside Claude.") }
    }
    Spacer(Modifier.height(S4))
}

@Composable private fun AlertsPage() {
    Spacer(Modifier.height(S4))
    Reveal(0) { NotificationPreview() }
    Spacer(Modifier.height(S5 + S1))
    Reveal(1) { OnboardingTitle("Stay ahead of\nyour limits") }
    Spacer(Modifier.height(S2))
    Reveal(2) { Text("Alerts follow the account you’re viewing, so you only hear about the one you’re using.", color = Muted, fontSize = 16.sp, lineHeight = 23.sp, textAlign = TextAlign.Center) }
    Spacer(Modifier.height(S5))
    Column(verticalArrangement = Arrangement.spacedBy(S4)) {
        Reveal(3) { FeatureRow(GlyphKind.Bell, Teal, "Before a window runs out", "60, 30 and 10 minute heads-ups, plus a ping when it resets.") }
        Reveal(4) { FeatureRow(GlyphKind.Clock, Blue, "Quiet hours", "Silence everything but the last-10-minute warning overnight.") }
    }
    Spacer(Modifier.height(S4))
}

@Composable private fun OnboardingTitle(text: String) =
    Text(text, color = Text, fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.6).sp, textAlign = TextAlign.Center)

/** [logo], when set, replaces the drawn [glyph] with that provider's mark. */
@Composable private fun FeatureRow(glyph: GlyphKind, color: Color, title: String, body: String, logo: Provider? = null) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = .15f)), contentAlignment = Alignment.Center) {
        if (logo != null) ProviderLogo(logo, Modifier.size(24.dp)) else Glyph(glyph, color, Modifier.size(24.dp))
    }
    Spacer(Modifier.width(S3))
    Column(Modifier.weight(1f)) {
        Text(title, color = Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(body, color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

/** A lock-screen style notification, so the ask for permission shows exactly what the user will get. */
@Composable private fun NotificationPreview() = Box(Modifier.fillMaxWidth().height(128.dp), contentAlignment = Alignment.TopCenter) {
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.padding(top = 24.dp).fillMaxWidth(.86f).height(88.dp).clip(shape).background(Surface2.copy(alpha = .45f)))
    Row(Modifier.fillMaxWidth().clip(shape).background(Surface2.copy(alpha = .92f)).border(1.dp, Hairline, shape).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(listOf(Teal, lerp(Teal, Blue, .6f)))), contentAlignment = Alignment.Center) { Glyph(GlyphKind.Ring, Bg, Modifier.size(22.dp)) }
        Spacer(Modifier.width(S2))
        Column(Modifier.weight(1f)) {
            Row { Text("SessionSense", color = Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text("now", color = Faint, fontSize = 12.sp) }
            Text("30 min left", color = Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("72% of this Claude session is used.", color = Muted, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable private fun PageDots(page: Int, count: Int, modifier: Modifier = Modifier) = Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    repeat(count) { i ->
        val width by animateDpAsState(if (i == page) 22.dp else 7.dp, spring(dampingRatio = .7f, stiffness = Spring.StiffnessMediumLow), label = "dot")
        val color by animateColorAsState(if (i == page) Text else Faint.copy(alpha = .45f), smooth(), label = "dotColor")
        Box(Modifier.size(width, 7.dp).clip(CircleShape).background(color))
    }
}

/** Staggered entrance: fades and lifts children in order, Apple-onboarding style. */
@Composable private fun Reveal(index: Int, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(60L + index * 70L); progress.animateTo(1f, spring(dampingRatio = .85f, stiffness = Spring.StiffnessLow)) }
    Box(Modifier.graphicsLayer { alpha = progress.value.coerceIn(0f, 1f); translationY = (1f - progress.value) * 22.dp.toPx() }) { content() }
}

// ─── Glyphs (drawn, so no icon dependency) ─────────────────────────────────────────────────────────────

internal enum class GlyphKind { Ring, Bell, Chart, Lock, Shield, Globe, People, Clock, Chevron, Back, Check, Plus, Download }

@Composable internal fun Glyph(kind: GlyphKind, color: Color, modifier: Modifier) = Canvas(modifier) {
    val w = size.minDimension; val sw = w * .1f
    fun o(x: Float, y: Float) = Offset(x * w, y * w)
    val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(vararg pts: Pair<Float, Float>) = drawPath(Path().apply { pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * w, y * w) else lineTo(x * w, y * w) } }, color, style = stroke)
    when (kind) {
        GlyphKind.Ring -> { drawCircle(color.copy(alpha = .3f), w * .34f, style = Stroke(sw * 1.2f)); drawArc(color, -90f, 260f, false, o(.16f, .16f), Size(w * .68f, w * .68f), style = Stroke(sw * 1.2f, cap = StrokeCap.Round)) }
        GlyphKind.Bell -> {
            drawPath(Path().apply { moveTo(.26f * w, .7f * w); lineTo(.26f * w, .46f * w); cubicTo(.26f * w, .28f * w, .37f * w, .18f * w, .5f * w, .18f * w); cubicTo(.63f * w, .18f * w, .74f * w, .28f * w, .74f * w, .46f * w); lineTo(.74f * w, .7f * w) }, color, style = stroke)
            line(.16f to .72f, .84f to .72f); line(.42f to .85f, .58f to .85f)
        }
        GlyphKind.Chart -> { line(.26f to .8f, .26f to .56f); line(.5f to .8f, .5f to .24f); line(.74f to .8f, .74f to .44f) }
        GlyphKind.Lock -> {
            drawRoundRect(color, o(.2f, .44f), Size(w * .6f, w * .42f), CornerRadius(w * .1f))
            drawArc(color, 180f, 180f, false, o(.33f, .16f), Size(w * .34f, w * .34f), style = stroke)
            line(.33f to .33f, .33f to .44f); line(.67f to .33f, .67f to .44f)
        }
        GlyphKind.Shield -> {
            drawPath(Path().apply { moveTo(.5f * w, .12f * w); lineTo(.8f * w, .24f * w); lineTo(.8f * w, .48f * w); cubicTo(.8f * w, .68f * w, .67f * w, .82f * w, .5f * w, .89f * w); cubicTo(.33f * w, .82f * w, .2f * w, .68f * w, .2f * w, .48f * w); lineTo(.2f * w, .24f * w); close() }, color, style = stroke)
            line(.37f to .5f, .47f to .6f, .64f to .41f)
        }
        GlyphKind.Globe -> { drawCircle(color, w * .36f, style = stroke); drawOval(color, o(.36f, .14f), Size(w * .28f, w * .72f), style = stroke); line(.15f to .5f, .85f to .5f) }
        GlyphKind.People -> {
            drawCircle(color, w * .13f, o(.4f, .34f), style = stroke)
            drawPath(Path().apply { moveTo(.16f * w, .82f * w); cubicTo(.18f * w, .58f * w, .62f * w, .58f * w, .64f * w, .82f * w) }, color, style = stroke)
            drawCircle(color, w * .09f, o(.7f, .38f), style = stroke)
            drawPath(Path().apply { moveTo(.7f * w, .58f * w); cubicTo(.8f * w, .58f * w, .86f * w, .66f * w, .87f * w, .76f * w) }, color, style = stroke)
        }
        GlyphKind.Clock -> { drawCircle(color, w * .36f, style = stroke); line(.5f to .3f, .5f to .5f, .64f to .6f) }
        GlyphKind.Chevron -> line(.28f to .4f, .5f to .62f, .72f to .4f)
        GlyphKind.Back -> line(.62f to .24f, .36f to .5f, .62f to .76f)
        GlyphKind.Check -> line(.24f to .52f, .42f to .7f, .78f to .32f)
        GlyphKind.Plus -> { line(.5f to .22f, .5f to .78f); line(.22f to .5f, .78f to .5f) }
        GlyphKind.Download -> { line(.5f to .16f, .5f to .6f); line(.32f to .44f, .5f to .62f, .68f to .44f); line(.2f to .7f, .2f to .82f, .8f to .82f, .8f to .7f) }
    }
}

// ─── Shell + floating tab bar ──────────────────────────────────────────────────────────────────────────

private data class Tab(val route: String, val label: String, val icon: Int)
private val Tabs = listOf(Tab("home", "Home", R.drawable.ic_nav_home), Tab("history", "History", R.drawable.ic_nav_history), Tab("settings", "Settings", R.drawable.ic_nav_settings))
private fun tabIndex(entry: NavBackStackEntry?) = Tabs.indexOfFirst { it.route == entry?.destination?.route }.coerceAtLeast(0)

/**
 * Shell-level actions any tab can trigger: the account sheet ([addAccount] opens it on the provider choice)
 * and the provider's login, to add an account or reconnect one.
 */
private class ShellActions(val openAccounts: () -> Unit, val addAccount: () -> Unit, val signIn: (Provider) -> Unit, val reconnect: (Provider) -> Unit)
private val LocalShell = staticCompositionLocalOf { ShellActions({}, {}, {}, {}) }

@Composable private fun MainShell(vm: AppViewModel, state: AppUiState, start: String) {
    var auth by rememberSaveable { mutableStateOf<String?>(null) } // provider id of the login being shown
    var accountsOpen by rememberSaveable { mutableStateOf<String?>(null) } // "list" | "add"
    if (auth != null) {
        // Always a clean WebView session: the one left over is usually a different account's.
        val done = { login: AccountLogin -> vm.connected(login).also { if (it == null) auth = null } }
        if (Provider.of(auth) == Provider.CODEX) CodexAuth(onConnected = done, onClose = { auth = null }, freshLogin = true)
        else ClaudeAuth(onConnected = done, onClose = { auth = null }, freshLogin = true)
        return
    }
    val actions = remember { ShellActions(openAccounts = { accountsOpen = "list" }, addAccount = { accountsOpen = "add" },
        signIn = { accountsOpen = null; auth = it.id }, reconnect = { accountsOpen = null; auth = it.id }) }
    accountsOpen?.let { mode -> AccountsSheet(vm, state, actions, startAdding = mode == "add") { accountsOpen = null } }
    CompositionLocalProvider(LocalShell provides actions) { ShellContent(vm, state, start) }
}

@Composable private fun ShellContent(vm: AppViewModel, state: AppUiState, start: String) {
    val nav = rememberNavController()
    val cardHaze = remember { HazeState() }; val barHaze = remember { HazeState() }
    // Liquid glass reads the screen behind the tab bar from this layer; older phones use the Haze blur instead.
    val backdrop = if (liquidGlassSupported) rememberGraphicsLayer() else null
    val ringAnchor = remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(start) { if (start != "home") nav.navigate(start) }
    val tab = tabIndex(nav.currentBackStackEntryAsState().value)
    val glowColor by animateColorAsState(stateColor(state.sessionState), spring(stiffness = Spring.StiffnessVeryLow), label = "glow")
    val glow by animateFloatAsState(if (tab == 0) 1f else .45f, spring(stiffness = Spring.StiffnessLow), label = "glowIntensity")
    CompositionLocalProvider(LocalHazeState provides cardHaze, LocalRingAnchor provides ringAnchor) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.drawWithContent { backdrop.record { this@drawWithContent.drawContent() }; drawLayer(backdrop) } else Modifier.hazeSource(barHaze))) {
                Backdrop(Modifier.matchParentSize().hazeSource(cardHaze), glowColor, glow) { if (tab == 0) ringAnchor.value else null }
                val slide = { from: NavBackStackEntry, to: NavBackStackEntry -> sign((tabIndex(to) - tabIndex(from)).toFloat()).toInt().takeIf { it != 0 } ?: 1 }
                NavHost(nav, "home", Modifier.fillMaxSize().statusBarsPadding(),
                    enterTransition = { val d = slide(initialState, targetState); slideInHorizontally(smooth(IntOffset.VisibilityThreshold)) { d * it / 6 } + fadeIn(smooth()) },
                    exitTransition = { val d = slide(initialState, targetState); slideOutHorizontally(smooth(IntOffset.VisibilityThreshold)) { -d * it / 6 } + fadeOut(spring(stiffness = Spring.StiffnessMedium)) },
                    popEnterTransition = { val d = slide(initialState, targetState); slideInHorizontally(smooth(IntOffset.VisibilityThreshold)) { d * it / 6 } + fadeIn(smooth()) },
                    popExitTransition = { val d = slide(initialState, targetState); slideOutHorizontally(smooth(IntOffset.VisibilityThreshold)) { -d * it / 6 } + fadeOut(spring(stiffness = Spring.StiffnessMedium)) }) {
                    composable("home") { Home(vm, state) }; composable("history") { History(vm, state) }; composable("settings") { Settings(vm, state) }
                }
            }
            FloatingTabBar(tab, barHaze, backdrop, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = S2)) { i ->
                nav.navigate(Tabs[i].route) { popUpTo("home"); launchSingleTop = true }
            }
        }
    }
}

@Composable private fun FloatingTabBar(selected: Int, haze: HazeState, backdrop: GraphicsLayer?, modifier: Modifier, onSelect: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val liquid = backdrop != null && liquidGlassSupported
    // The pill's position in tab slots. Its speed stretches it like a droplet in liquid mode.
    val pillX = remember { Animatable(selected.toFloat()) }
    LaunchedEffect(selected) { pillX.animateTo(selected.toFloat(), spring(dampingRatio = .6f, stiffness = 260f)) }
    BoxWithConstraints(modifier.padding(horizontal = 44.dp).fillMaxWidth().height(TabBarHeight)
        .shadow(28.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)) {
        val pad = 6.dp
        val slot = (maxWidth - pad * 2) / Tabs.size
        if (liquid) {
            val density = LocalDensity.current
            val light = rememberTiltLight()
            LiquidGlassSurface(backdrop!!, pill = {
                with(density) {
                    val stretch = (kotlin.math.abs(pillX.velocity) / 7f).coerceAtMost(.5f)
                    val w = slot.toPx() * (1f + stretch); val h = (TabBarHeight - pad * 2).toPx() * (1f - stretch * .16f)
                    val cx = pad.toPx() + slot.toPx() * (pillX.value + .5f); val cy = TabBarHeight.toPx() / 2f
                    Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
                }
            }, light, Modifier.matchParentSize())
        } else {
            Box(Modifier.matchParentSize().clip(CircleShape).hazeEffect(haze, BarGlass)
                // Specular sheen on the upper half and a bright rim that fades down the sides, like light catching glass.
                .drawWithContent { drawContent(); drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = .10f), .55f to Color.Transparent)) }
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .34f), Color.White.copy(alpha = .08f), Color.White.copy(alpha = .14f))), CircleShape))
            // A frosted pill under the selected tab (the icon and label carry the teal).
            Box(Modifier.padding(pad).offset { IntOffset((slot * pillX.value).roundToPx(), 0) }.width(slot).fillMaxHeight().clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .17f), Color.White.copy(alpha = .07f))))
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .28f), Color.White.copy(alpha = .06f))), CircleShape))
        }
        Row(Modifier.fillMaxSize().padding(pad)) {
            Tabs.forEachIndexed { i, tab ->
                val active = i == selected
                val tint by animateColorAsState(if (active) Teal else Muted, smooth(), label = "tabTint")
                // The selected icon swells a little under the pill's lens.
                val grow by animateFloatAsState(if (active) 1.1f else 1f, spring(dampingRatio = .5f, stiffness = Spring.StiffnessMedium), label = "tabGrow")
                val source = remember { MutableInteractionSource() }; val scale = pressScale(source) * grow
                Column(Modifier.weight(1f).fillMaxHeight().graphicsLayer { scaleX = scale; scaleY = scale }
                    .selectable(active, interactionSource = source, indication = null, role = Role.Tab) { if (!active) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(i) } },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(painterResource(tab.icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(tab.label, color = tint, fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

// ─── Home ──────────────────────────────────────────────────────────────────────────────────────────────

@Composable private fun Home(vm: AppViewModel, s: AppUiState) = Page("SessionSense", trailing = { AccountChip(s) }) {
    if (s.usage.connection == "expired") { val shell = LocalShell.current; Pressable({ shell.reconnect(s.provider) }) { Banner("${s.activeAccount?.name ?: "This account"}’s session expired — tap to reconnect", Amber) } }
    UpdatePill()
    SessionHero(s)
    Spacer(Modifier.height(S5))
    // Per-model rings only for limits claude.ai actually reports (most plans have none, and Codex never does);
    // otherwise the weekly window sits next to the plan it runs on.
    val models = if (s.provider == Provider.CLAUDE) listOfNotNull(Triple("Opus", s.usage.opusPct, Amber).takeIf { s.usage.opusReported },
        Triple("Sonnet", s.usage.sonnetPct, Teal).takeIf { s.usage.sonnetReported }) else emptyList()
    if (models.isEmpty()) Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(S2)) {
        Metric("Weekly", s.usage.weeklyPct, Blue, Modifier.weight(1f).fillMaxHeight(), large = true)
        val plan = if (s.provider == Provider.CODEX) planLabel(s.usage.planType) else "Claude ${claudePlanName(s.settings.plan)}"
        PlanTile(s.provider, plan, s.usage.weeklyResetMs, s.now, Modifier.weight(1f).fillMaxHeight())
    }
    else Row(horizontalArrangement = Arrangement.spacedBy(S2)) { Metric("Weekly", s.usage.weeklyPct, Blue, Modifier.weight(1f)); models.forEach { (label, pct, color) -> Metric(label, pct, color, Modifier.weight(1f)) } }
    Spacer(Modifier.height(S2))
    if (s.provider == Provider.CODEX) s.codexAnalytics?.let { CodexModelsCard(it); Spacer(Modifier.height(S2)) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(Teal.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) { Text("↗", color = Teal, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.width(S2))
            Column(Modifier.weight(1f)) { Label("PACE INSIGHT", color = Teal); Spacer(Modifier.height(4.dp)); Text(s.insight.text, color = Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp) }
        }
        Spacer(Modifier.height(S2)); Text(s.insight.basis, color = Muted, fontSize = 13.sp)
    }
    Spacer(Modifier.height(S4)); SectionLabel("TODAY")
    val live = s.activeWindowStartMs > 0
    if (!live && s.todaySessions.isEmpty()) Card {
        Text("No sessions yet today", color = Text, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp)); Text("Your current window shows here live, and each finished one is kept in History. Swipe a finished row left to delete it.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
    } else Column(verticalArrangement = Arrangement.spacedBy(S1)) {
        if (live) LiveSessionRow(s)
        s.todaySessions.forEach { SessionRow(it, s.history.between(it.startMs, it.endMs), vm::deleteSession) }
    }
}

private val Violet = Color(0xFFB79BFF)
private val AccountColors = listOf(Teal, Blue, Violet)
private fun accountColor(s: AppUiState, id: String) = AccountColors[s.accounts.indexOfFirst { it.account.id == id }.coerceAtLeast(0) % AccountColors.size]

private val ClaudeOrange = Color(0xFFD97757)
private fun providerTint(p: Provider) = if (p == Provider.CODEX) Text else ClaudeOrange

/** The provider's own mark (Claude spark, OpenAI blossom), in its brand colour. */
@Composable private fun ProviderLogo(p: Provider, modifier: Modifier) =
    Image(painterResource(if (p == Provider.CODEX) R.drawable.ic_provider_codex else R.drawable.ic_provider_claude), contentDescription = p.label, modifier = modifier)
private fun providerName(p: Provider) = if (p == Provider.CODEX) "ChatGPT · Codex" else "Claude"

/** Gradient monogram with a provider badge and a live-status dot, like a Contacts avatar. */
@Composable private fun Avatar(account: Account, color: Color, size: Dp, connection: String?) = Box(Modifier.size(size)) {
    Box(Modifier.fillMaxSize().clip(CircleShape).background(Brush.linearGradient(listOf(lerp(color, Text, .25f), color))), contentAlignment = Alignment.Center) {
        Text(account.initial, color = Bg, fontSize = (size.value * .42f).sp, fontWeight = FontWeight.Bold)
    }
    Box(Modifier.align(Alignment.TopEnd).offset(x = size * .08f, y = -size * .08f).size(size * .44f).background(Bg, CircleShape).padding(size * .04f)
        .background(Surface2, CircleShape), contentAlignment = Alignment.Center) {
        ProviderLogo(account.provider, Modifier.fillMaxSize(.7f))
    }
    if (connection != null) {
        val dot = when (connection) { "connected" -> Teal; "expired" -> Coral; else -> Faint }
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(.7f, 1f, infiniteRepeatable(tween(1_100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulseAlpha")
        Box(Modifier.align(Alignment.BottomEnd).size(size * .34f).background(Bg, CircleShape).padding(size * .06f)) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (connection == "connected") pulse else 1f }.background(dot, CircleShape))
        }
    }
}

/** Header control showing whose data is on screen; opens the account switcher. */
@Composable private fun AccountChip(s: AppUiState) {
    val account = s.activeAccount ?: return
    val shell = LocalShell.current
    Pressable(shell.openAccounts, Modifier.clip(CircleShape).background(Color.White.copy(alpha = .06f)).border(1.dp, Hairline, CircleShape)) {
        Row(Modifier.padding(start = 4.dp, end = S2, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(account, accountColor(s, account.id), 30.dp, s.usage.connection)
            Spacer(Modifier.width(S1))
            Text(account.name, color = Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.widthIn(max = 96.dp))
            Spacer(Modifier.width(6.dp))
            Glyph(GlyphKind.Chevron, Muted, Modifier.size(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AccountsSheet(vm: AppViewModel, s: AppUiState, shell: ShellActions, startAdding: Boolean, dismiss: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var adding by rememberSaveable { mutableStateOf(startAdding) }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Surface, contentColor = Text, scrimColor = Color.Black.copy(alpha = .55f)) {
        Column(Modifier.padding(horizontal = Gutter).padding(bottom = S5)) {
            Text("Accounts", style = MaterialTheme.typography.titleLarge, color = Text)
            Spacer(Modifier.height(4.dp))
            Text("Alerts, widgets and the live notification follow the account you’re viewing. The others keep recording history quietly.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(S3))
            val shape = RoundedCornerShape(20.dp)
            Column(Modifier.fillMaxWidth().clip(shape).background(Surface2.copy(alpha = .55f)).border(1.dp, Hairline, shape)) {
                s.accounts.forEachIndexed { i, (account, usage) ->
                    val active = account.id == s.activeAccountId; val color = accountColor(s, account.id)
                    Pressable({
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        if (!active) vm.switchAccount(account.id)
                        if (account.connected) dismiss() else shell.reconnect(account.provider)
                    }, Modifier.fillMaxWidth(), role = Role.RadioButton) {
                        Row(Modifier.padding(horizontal = S3, vertical = S2).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(account, color, 42.dp, usage.connection)
                            Spacer(Modifier.width(S2))
                            Column(Modifier.weight(1f)) {
                                Text(account.name, color = Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(when {
                                    !account.connected -> "Session expired · tap to reconnect"
                                    !usage.sessionWindow -> "Weekly ${usage.weeklyPct}% used"
                                    usage.sessionPct > 0 && usage.sessionResetMs > s.now -> "${usage.sessionPct}% used · ${formatSpan(usage.sessionResetMs - s.now)} left"
                                    else -> account.email ?: "Full 5-hour window"
                                }, color = if (account.connected) Muted else Coral, fontSize = 13.sp, maxLines = 1)
                            }
                            if (account.connected) Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                                ActivityRing(usage.sessionPct / 100f, listOf(lerp(color, Text, .3f), color), 4.dp, Modifier.fillMaxSize(), glow = false)
                                Text("${usage.sessionPct}", color = Text, fontFamily = PlexMono, fontSize = 10.sp)
                            }
                            Box(Modifier.padding(start = S2).size(22.dp), contentAlignment = Alignment.Center) {
                                androidx.compose.animation.AnimatedVisibility(active, enter = scaleIn(ringSpring()) + fadeIn(), exit = scaleOut() + fadeOut()) { Glyph(GlyphKind.Check, Teal, Modifier.size(20.dp)) }
                            }
                        }
                    }
                    if (i < s.accounts.lastIndex) Box(Modifier.padding(start = 70.dp).fillMaxWidth().height(1.dp).background(Hairline))
                }
            }
            Spacer(Modifier.height(S2))
            if (s.canAddAccount) Column(Modifier.fillMaxWidth().clip(shape).background(Teal.copy(alpha = .08f)).border(1.dp, Teal.copy(alpha = .25f), shape)) {
                Pressable({ haptics.performHapticFeedback(HapticFeedbackType.ContextClick); adding = !adding }, Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = S3, vertical = S2).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        val turn by animateFloatAsState(if (adding) 45f else 0f, ringSpring(), label = "plus")
                        Box(Modifier.size(42.dp).background(Teal.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) { Glyph(GlyphKind.Plus, Teal, Modifier.size(20.dp).rotate(turn)) }
                        Spacer(Modifier.width(S2))
                        Column(Modifier.weight(1f)) {
                            Text("Add account", color = Teal, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (adding) "Which service?" else "${s.accounts.size} of ${CredentialStore.MAX_ACCOUNTS} used · Claude or ChatGPT", color = Muted, fontSize = 13.sp)
                        }
                    }
                }
                AnimatedVisibility(adding, enter = fadeIn(smooth()) + expandVertically(smooth()), exit = fadeOut(smooth()) + shrinkVertically(smooth())) {
                    Column(Modifier.padding(start = S2, end = S2, bottom = S2), verticalArrangement = Arrangement.spacedBy(S1)) {
                        ProviderOption(Provider.CLAUDE, "Claude", "Pro, Team or Max · signs in on claude.ai") { shell.signIn(Provider.CLAUDE) }
                        ProviderOption(Provider.CODEX, "ChatGPT · Codex", "Codex limits on a ChatGPT plan · signs in on chatgpt.com") { shell.signIn(Provider.CODEX) }
                    }
                }
            } else Text("You’re tracking the maximum of ${CredentialStore.MAX_ACCOUNTS} accounts. Sign out of one in Settings to add another.", color = Faint, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable private fun ProviderOption(provider: Provider, title: String, detail: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Pressable(onClick, Modifier.fillMaxWidth().clip(shape).background(Surface.copy(alpha = .9f)).border(1.dp, Hairline, shape)) {
        Row(Modifier.padding(horizontal = S2, vertical = S2).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(providerTint(provider).copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                ProviderLogo(provider, Modifier.size(24.dp))
            }
            Spacer(Modifier.width(S2))
            Column(Modifier.weight(1f)) {
                Text(title, color = Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Glyph(GlyphKind.Back, Faint, Modifier.size(14.dp).rotate(180f))
        }
    }
}

/** The plan an account runs on (reported by chatgpt.com for Codex, picked in Settings for Claude), beside the weekly ring. */
@Composable private fun PlanTile(provider: Provider, plan: String, weeklyResetMs: Long, now: Long, modifier: Modifier) =
    Card(modifier, padding = S2, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    Spacer(Modifier.height(4.dp))
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(providerTint(provider).copy(alpha = .10f)), contentAlignment = Alignment.Center) { ProviderLogo(provider, Modifier.size(28.dp)) }
    Spacer(Modifier.height(S2))
    Text(plan, color = Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    Text(if (weeklyResetMs > now) "week resets ${clock(weeklyResetMs, "EEE")}" else "${providerName(provider)} plan", color = Muted, fontSize = 12.sp, maxLines = 1)
}

private fun planLabel(plan: String) = plan.trim().ifEmpty { "ChatGPT" }.split('_', ' ').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

@Composable private fun SessionHero(s: AppUiState) {
    val anchor = LocalRingAnchor.current
    val accent = stateColor(s.sessionState)
    val gradient = heroColors(s.sessionState)
    val heroRing = LocalHeroRing.current
    val active = s.usage.sessionPct > 0
    val noWindow = !s.usage.sessionWindow
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(top = S1).size(248.dp).onGloballyPositioned { val o = it.positionInRoot(); anchor?.value = Offset(o.x + it.size.width / 2f, o.y + it.size.height / 2f); heroRing?.value = it.boundsInRoot() }, contentAlignment = Alignment.Center) {
            ActivityRing(s.usage.sessionPct / 100f, gradient, 24.dp, Modifier.fillMaxSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (noWindow) "—:—" else if (active) formatRemaining(s.remainingMs) else "5:00:00", color = Text, fontFamily = PlexMono, fontSize = 38.sp, letterSpacing = (-.5).sp)
                Spacer(Modifier.height(2.dp)); Label(if (noWindow) "NO 5-HOUR WINDOW REPORTED" else if (active) "TIME REMAINING" else "FULL WINDOW AVAILABLE", color = Muted)
            }
        }
        Spacer(Modifier.height(S4))
        Row(Modifier.fillMaxWidth()) {
            HeroStat("USED", "${s.usage.sessionPct}%", accent, Modifier.weight(1f))
            HeroStat("RESETS", if (active && s.usage.sessionResetMs > s.now) clock(s.usage.sessionResetMs, "h:mm a") else "—", Text, Modifier.weight(1f))
            HeroStat("WEEKLY RESET", if (s.usage.weeklyResetMs > s.now) clock(s.usage.weeklyResetMs, "EEE h a") else "—", Text, Modifier.weight(1f))
        }
    }
}

@Composable private fun HeroStat(label: String, value: String, color: Color, modifier: Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, color = color, fontFamily = PlexMono, fontSize = 17.sp, maxLines = 1); Spacer(Modifier.height(4.dp)); Label(label)
}

/** [large] is the two-up layout (weekly beside the plan tile): a bigger, bolder ring so it holds its own against the plan badge. */
@Composable private fun Metric(label: String, pct: Int, color: Color, modifier: Modifier = Modifier, large: Boolean = false) =
    Card(modifier, padding = S2, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    Spacer(Modifier.height(4.dp))
    Box(Modifier.size(if (large) 96.dp else 62.dp), contentAlignment = Alignment.Center) {
        ActivityRing(pct / 100f, listOf(lerp(color, Text, .35f), color), if (large) 11.dp else 7.dp, Modifier.fillMaxSize())
        Text("$pct%", color = Text, fontFamily = PlexMono, fontSize = if (large) 20.sp else 13.sp)
    }
    Spacer(Modifier.height(S2)); Text(label, color = if (large) Text else Muted, fontSize = if (large) 15.sp else 13.sp, fontWeight = if (large) FontWeight.SemiBold else FontWeight.Medium)
    if (large) Text("this week", color = Muted, fontSize = 12.sp)
}

// ─── History ───────────────────────────────────────────────────────────────────────────────────────────

@Composable private fun History(vm: AppViewModel, s: AppUiState) = Page("History", trailing = { AccountChip(s) }) {
    Row(horizontalArrangement = Arrangement.spacedBy(S2)) { Stat("Weekly", "${s.usage.weeklyPct}%", Modifier.weight(1f)); val weekCount = s.sessionsThisWeek + if (s.activeWindowStartMs > 0) 1 else 0; Stat("7-day", "$weekCount", Modifier.weight(1f), if (weekCount == 1) "session" else "sessions"); Stat("Active", "${s.weeklyStreakDays}/7", Modifier.weight(1f), "days") }
    if (s.provider == Provider.CLAUDE) { Spacer(Modifier.height(S2)); Stat("Estimated tokens this week", formatTokens(s.estimatedTokens), Modifier.fillMaxWidth()) }
    if (s.provider == Provider.CODEX) s.codexAnalytics?.let { Spacer(Modifier.height(S4)); CodexHistorySection(it, s.now) }
    Spacer(Modifier.height(S4)); SectionLabel("TODAY")
    val dayStart = remember(s.now / 60_000) { startOfDayMs() }
    val today = remember(s.history, dayStart) { s.history.between(dayStart, Long.MAX_VALUE) }
    Card(padding = S3) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) { Label("5-HOUR SESSION USAGE"); Spacer(Modifier.height(4.dp)); Text("Peak ${s.dailyPeaks.last()}%", color = Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
            Text("${today.size} readings", color = Faint, fontSize = 12.sp)
        }
        Spacer(Modifier.height(S3))
        if (today.isEmpty()) Text("Readings are saved while SessionSense runs in the background — today’s curve fills in from the next poll.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        else TodayChart(today, dayStart, s.now)
    }
    Spacer(Modifier.height(S4)); SectionLabel("7-DAY PEAKS"); Card(padding = S3) { PeakChart(s.dailyPeaks) }
    Spacer(Modifier.height(S4))
    if (s.activeWindowStartMs > 0) { SectionLabel("NOW"); LiveSessionRow(s); Spacer(Modifier.height(S4)) }
    if (s.sessions.isEmpty()) Card { Text("No finished sessions yet", color = Text, fontWeight = FontWeight.Medium); Spacer(Modifier.height(4.dp)); Text("When a 5-hour window resets it’s saved here with its peak usage and curve.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp) }
    else s.sessions.groupBy { dateLabel(it.startMs) }.forEach { (date, rows) ->
        SectionLabel(date.uppercase())
        Column(verticalArrangement = Arrangement.spacedBy(S1)) { rows.forEach { SessionRow(it, s.history.between(it.startMs, it.endMs), vm::deleteSession) } }
        Spacer(Modifier.height(S4))
    }
}

/** Session % across today on a fixed 24h axis, drawn as a step curve (usage only moves when Claude reports a change). */
@Composable private fun TodayChart(samples: List<UsageSample>, dayStartMs: Long, nowMs: Long) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessVeryLow)) }
    val day = 86_400_000f
    Column {
        Canvas(Modifier.fillMaxWidth().height(132.dp)) {
            val h = size.height; val w = size.width
            listOf(0f, .5f, 1f).forEach { f -> drawLine(Color.White.copy(alpha = if (f == 1f) .10f else .05f), Offset(0f, h * f), Offset(w, h * f), 1.dp.toPx()) }
            fun x(ts: Long) = ((ts - dayStartMs) / day).coerceIn(0f, 1f) * w
            fun y(pct: Int) = h - pct / 100f * (h - 4.dp.toPx())
            val line = Path(); var lastY = y(samples.first().sessionPct)
            line.moveTo(x(samples.first().ts), lastY)
            samples.drop(1).forEach { val px = x(it.ts); line.lineTo(px, lastY); lastY = y(it.sessionPct); line.lineTo(px, lastY) }
            val endX = x(nowMs); line.lineTo(endX, lastY)
            val area = Path().apply { addPath(line); lineTo(endX, h); lineTo(x(samples.first().ts), h); close() }
            clipRect(right = w * reveal.value) {
                drawPath(area, Brush.verticalGradient(listOf(Teal.copy(alpha = .32f), Teal.copy(alpha = 0f))))
                drawPath(line, Brush.horizontalGradient(listOf(Teal, Blue)), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (reveal.value > .98f) {
                val tip = Offset(endX, lastY)
                drawCircle(Brush.radialGradient(listOf(Blue.copy(alpha = .55f), Color.Transparent), tip, 12.dp.toPx()), 12.dp.toPx(), tip)
                drawCircle(Blue, 4.dp.toPx(), tip); drawCircle(Bg, 1.5.dp.toPx(), tip)
            }
        }
        Spacer(Modifier.height(S1))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("12a", "6a", "12p", "6p", "12a").forEach { Text(it, color = Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) } }
    }
}

@Composable private fun Sparkline(samples: List<UsageSample>, color: Color, modifier: Modifier) = Canvas(modifier) {
    if (samples.size < 2) return@Canvas
    val t0 = samples.first().ts; val span = (samples.last().ts - t0).coerceAtLeast(1).toFloat()
    val path = Path(); var lastY = 0f
    samples.forEachIndexed { i, s -> val px = (s.ts - t0) / span * size.width; val py = size.height - s.sessionPct / 100f * size.height; if (i == 0) path.moveTo(px, py) else { path.lineTo(px, lastY); path.lineTo(px, py) }; lastY = py }
    drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable private fun PeakChart(values: List<Int>) {
    val growth = remember(values) { Animatable(0f) }
    LaunchedEffect(values) { growth.animateTo(1f, spring(dampingRatio = .7f, stiffness = Spring.StiffnessLow)) }
    Column {
        Canvas(Modifier.fillMaxWidth().height(148.dp)) {
            val gap = 10.dp.toPx(); val width = (size.width - gap * 6) / 7; val radius = CornerRadius(width / 2f)
            values.forEachIndexed { i, v ->
                val x = i * (width + gap)
                drawRoundRect(Color.White.copy(alpha = .05f), Offset(x, 0f), Size(width, size.height), radius)
                val c = if (v >= 85) Coral else if (v >= 60) Amber else Teal
                val h = (size.height * v / 100f * growth.value).coerceIn(if (v > 0) width else 0f, size.height)
                if (h > 0f) drawRoundRect(Brush.verticalGradient(listOf(lerp(c, Text, .3f), c), size.height - h, size.height), Offset(x, size.height - h), Size(width, h), radius)
            }
        }
        Spacer(Modifier.height(S1))
        Row(Modifier.fillMaxWidth()) {
            (6 downTo 0).forEach { ago -> Text(LocalDate.now().minusDays(ago.toLong()).dayOfWeek.name.take(1), Modifier.weight(1f), color = if (ago == 0) Text else Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
        }
    }
}

@Composable private fun Stat(label: String, value: String, modifier: Modifier, unit: String? = null) = Card(modifier) {
    Label(label.uppercase()); Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.Bottom) { Text(value, color = Text, fontFamily = PlexMono, fontSize = 24.sp); if (unit != null) Text(" $unit", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp)) }
}

private fun pctColor(pct: Int) = if (pct >= 85) Coral else if (pct >= 60) Amber else Teal

@Composable private fun LiveSessionRow(s: AppUiState) {
    val shape = RoundedCornerShape(18.dp); val color = stateColor(s.sessionState)
    val curve = remember(s.history, s.activeWindowStartMs) { s.history.between(s.activeWindowStartMs, Long.MAX_VALUE) }
    Row(Modifier.fillMaxWidth().clip(shape).background(color.copy(alpha = .08f)).border(1.dp, color.copy(alpha = .28f), shape).heightIn(min = 64.dp).padding(horizontal = S3, vertical = S2), verticalAlignment = Alignment.CenterVertically) {
        ActivityRing(s.usage.sessionPct / 100f, listOf(lerp(color, Text, .3f), color), 4.dp, Modifier.size(30.dp), glow = false)
        Spacer(Modifier.width(S2))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("In progress", color = Text, fontSize = 15.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.width(6.dp)); Label("LIVE", color = color) }
            Text("Started ${clock(s.activeWindowStartMs, "h:mm a")} · resets ${clock(s.usage.sessionResetMs, "h:mm a")}", color = Muted, fontSize = 12.sp)
        }
        Sparkline(curve, color, Modifier.padding(horizontal = S2).size(56.dp, 24.dp))
        Text("${s.usage.sessionPct}%", color = color, fontFamily = PlexMono, fontSize = 15.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SessionRow(r: SessionRecord, curve: List<UsageSample>, onDelete: (SessionRecord) -> Unit) {
    val dismiss = rememberSwipeToDismissBoxState()
    val shape = RoundedCornerShape(18.dp); val color = pctColor(r.pctUsed)
    LaunchedEffect(dismiss.currentValue) { if (dismiss.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete(r) }
    SwipeToDismissBox(state = dismiss, enableDismissFromStartToEnd = false, backgroundContent = {
        Box(Modifier.fillMaxSize().background(Coral.copy(alpha = .18f), shape).padding(horizontal = Gutter), contentAlignment = Alignment.CenterEnd) { Text("Delete", color = Coral, fontWeight = FontWeight.SemiBold) }
    }) {
        Row(Modifier.fillMaxWidth().clip(shape).background(Surface.copy(alpha = .94f)).border(1.dp, Hairline, shape).heightIn(min = 64.dp).padding(horizontal = S3, vertical = S2), verticalAlignment = Alignment.CenterVertically) {
            ActivityRing(r.pctUsed / 100f, listOf(lerp(color, Text, .3f), color), 4.dp, Modifier.size(30.dp), glow = false)
            Spacer(Modifier.width(S2))
            Column(Modifier.weight(1f)) {
                Text("${clock(r.startMs, "h:mm a")} – ${clock(r.endMs, "h:mm a")}", color = Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text("${formatSpan(r.durationMs)} window · peak ${r.pctUsed}%", color = Muted, fontSize = 12.sp)
            }
            Sparkline(curve, color, Modifier.padding(horizontal = S2).size(56.dp, 24.dp))
            Text("${r.pctUsed}%", color = color, fontFamily = PlexMono, fontSize = 15.sp)
        }
    }
}

// ─── Settings ──────────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Settings(vm: AppViewModel, s: AppUiState) {
    var choosePlan by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current; val haptics = LocalHapticFeedback.current; val shell = LocalShell.current
    val name = s.activeAccount?.name ?: "this account"
    val plans = listOf(Triple("pro", "Pro · $20/mo", "~450K tokens/wk"), Triple("max5", "Max 5× · $100/mo", "~2.25M tokens/wk"), Triple("max20", "Max 20× · $200/mo", "~9M tokens/wk"))
    if (choosePlan) ModalBottomSheet(onDismissRequest = { choosePlan = false }, containerColor = Surface, contentColor = Text, scrimColor = Color.Black.copy(alpha = .55f)) {
        Column(Modifier.padding(horizontal = Gutter).padding(bottom = S5)) {
            Text("Choose your Claude plan", style = MaterialTheme.typography.titleLarge, color = Text); Spacer(Modifier.height(S3))
            plans.forEach { (id, title, detail) ->
                val selected = s.settings.plan == id
                val select = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); vm.plan(id); choosePlan = false }
                val bg by animateColorAsState(if (selected) Teal.copy(alpha = .12f) else Color.Transparent, smooth(), label = "planBg")
                Pressable(select, Modifier.fillMaxWidth().padding(bottom = S1).clip(RoundedCornerShape(18.dp)).background(bg).border(1.dp, if (selected) Teal.copy(alpha = .35f) else Hairline, RoundedCornerShape(18.dp)), role = Role.RadioButton) {
                    Row(Modifier.padding(horizontal = S1, vertical = S2), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, { select() }, colors = RadioButtonDefaults.colors(selectedColor = Teal, unselectedColor = Faint))
                        Column { Text(title, color = Text, fontWeight = FontWeight.Medium); Text(detail, color = Muted, fontSize = 13.sp) }
                    }
                }
            }
        }
    }
    Page("Settings", trailing = { AccountChip(s) }) {
        SectionLabel("ACCOUNT")
        s.activeAccount?.let { account ->
            Card(onClick = shell.openAccounts) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(account, accountColor(s, account.id), 46.dp, s.usage.connection)
                    Spacer(Modifier.width(S2))
                    Column(Modifier.weight(1f)) {
                        Text(account.name, color = Text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(account.email ?: "${providerName(account.provider)} account", color = Muted, fontSize = 13.sp, maxLines = 1)
                    }
                    Text(if (s.accounts.size > 1) "Switch" else "Manage", color = Teal, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(S4)); SectionLabel("PLAN · ${name.uppercase()}"); val selected = plans.firstOrNull { it.first == s.settings.plan } ?: plans.first()
        // Codex reports its plan itself, so there is nothing to choose.
        if (s.provider == Provider.CODEX) Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(if (s.usage.planType.isBlank()) "ChatGPT" else "ChatGPT ${planLabel(s.usage.planType)}", color = Text, fontSize = 17.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(2.dp)); Text("Reported by chatgpt.com · Codex limits", color = Muted, fontSize = 13.sp) }
                ProviderLogo(Provider.CODEX, Modifier.size(22.dp))
            }
        } else Card(onClick = { choosePlan = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(selected.second, color = Text, fontSize = 17.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(2.dp)); Text(selected.third, color = Muted, fontSize = 13.sp) }
                Text("Change", color = Teal, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(S4)); SectionLabel("ALERTS")
        Card(padding = 0.dp) {
            Toggle("Session and reset alerts", s.settings.sessionAlerts) { vm.toggle(Keys.SESSION_ALERTS, it) }; Divider()
            Toggle("Weekly quota alerts", s.settings.weeklyAlerts) { vm.toggle(Keys.WEEKLY_ALERTS, it) }; Divider()
            // Only meaningful when claude.ai reports a separate Opus limit for this plan.
            if (s.usage.opusReported) { Toggle("Per-model alerts", s.settings.modelAlerts) { vm.toggle(Keys.MODEL_ALERTS, it) }; Divider() }
            Toggle("Monday digest", s.settings.weeklyDigest) { vm.toggle(Keys.DIGEST, it) }; Divider()
            Toggle("Quiet hours", s.settings.quietHours) { vm.toggle(Keys.QUIET, it) }
            AnimatedVisibility(s.settings.quietHours, enter = fadeIn(smooth()) + expandVertically(smooth()), exit = fadeOut(smooth()) + shrinkVertically(smooth())) {
                Row(Modifier.fillMaxWidth().padding(start = S3, end = S3, bottom = S3), verticalAlignment = Alignment.CenterVertically) {
                    Text("${hour(s.settings.quietStart)} – ${hour(s.settings.quietEnd)}", Modifier.weight(1f), color = Muted, fontFamily = PlexMono, fontSize = 14.sp)
                    Chip("Start") { TimePickerDialog(context, { _, h, _ -> vm.quietHours(h, s.settings.quietEnd) }, s.settings.quietStart, 0, false).show() }
                    Spacer(Modifier.width(S1))
                    Chip("End") { TimePickerDialog(context, { _, h, _ -> vm.quietHours(s.settings.quietStart, h) }, s.settings.quietEnd, 0, false).show() }
                }
            }
        }
        // Nothing phones with Glyph lights only; the switch changes SessionSense's own notifications, nothing else.
        val glyph = remember { GlyphSupport.available(context) }
        if (glyph) {
            Spacer(Modifier.height(S4)); SectionLabel("GLYPH")
            Card(padding = 0.dp) {
                Toggle("SessionSense lights up the Glyph", s.settings.glyphLights, detail = "Off: SessionSense’s live notification has no progress bar and only updates when a session starts or ends. Your other apps aren’t affected. SessionSense alerts can still light the Glyph; to stop those too, switch off SessionSense in Glyph notifications.") { vm.glyphLights(it) }
                Divider()
                Pressable({ GlyphSupport.openSettings(context) }, Modifier.fillMaxWidth(), role = Role.Button) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = S3), verticalAlignment = Alignment.CenterVertically) {
                        Text("SessionSense in Glyph notifications", Modifier.weight(1f), color = Teal, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Glyph(GlyphKind.Back, Teal, Modifier.size(14.dp).rotate(180f))
                    }
                }
            }
        }
        Spacer(Modifier.height(S4)); AboutSection(s.now)
        Spacer(Modifier.height(S4)); SectionLabel("ACCOUNT & DATA")
        Column(verticalArrangement = Arrangement.spacedBy(S2)) {
            if (s.canAddAccount) CapsuleButton("Add another account", shell.addAccount, style = CapsuleStyle.Secondary)
            CapsuleButton("Reconnect $name", { shell.reconnect(s.provider) }, style = CapsuleStyle.Secondary)
            CapsuleButton("Clear $name’s history", vm::clearHistory, style = CapsuleStyle.Secondary)
            Spacer(Modifier.height(S1))
            CapsuleButton("Sign out of $name", { vm.signOut() }, style = CapsuleStyle.Destructive, haptic = HapticFeedbackType.LongPress)
            if (s.accounts.size > 1) Text("Your other ${if (s.accounts.size == 2) "account stays" else "accounts stay"} signed in.", color = Faint, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

@Composable internal fun Divider() = Box(Modifier.padding(start = S3).fillMaxWidth().height(1.dp).background(Hairline))

@Composable private fun Chip(label: String, onClick: () -> Unit) = Pressable(onClick, Modifier.clip(CircleShape).background(Teal.copy(alpha = .12f)).border(1.dp, Teal.copy(alpha = .3f), CircleShape)) {
    Text(label, Modifier.padding(horizontal = 14.dp, vertical = 7.dp), color = Teal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

@Composable internal fun Toggle(label: String, checked: Boolean, detail: String? = null, set: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val change = { v: Boolean -> haptics.performHapticFeedback(if (v) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff); set(v) }
    Row(Modifier.fillMaxWidth().selectable(checked, role = Role.Switch) { change(!checked) }.heightIn(min = 60.dp).padding(horizontal = S3, vertical = if (detail != null) S2 else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = S2)) {
            Text(label, color = Text, fontSize = 16.sp)
            if (detail != null) { Spacer(Modifier.height(4.dp)); Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 18.sp) }
        }
        Switch(checked, change, colors = SwitchDefaults.colors(checkedThumbColor = Bg, checkedTrackColor = Teal, checkedBorderColor = Teal,
            uncheckedThumbColor = Muted, uncheckedTrackColor = Surface2, uncheckedBorderColor = Faint.copy(alpha = .6f)))
    }
}

private fun formatRemaining(ms: Long): String { val seconds = ms / 1000; return "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) }
private fun formatSpan(ms: Long): String { val m = ms / 60_000; return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" }
private fun clock(ms: Long, pattern: String) = Instant.ofEpochMilli((ms + 30_000) / 60_000 * 60_000).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))
private fun dateLabel(ms: Long): String { val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate(); return when(d){LocalDate.now()->"Today";LocalDate.now().minusDays(1)->"Yesterday";else->d.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))} }
private fun formatTokens(v: Int) = if(v>=1_000_000) "%.1fM".format(v/1_000_000.0) else if(v>=1000) "${v/1000}K" else "$v"
private fun hour(h: Int) = LocalTime.of(h, 0).format(DateTimeFormatter.ofPattern("h a"))
