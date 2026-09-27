package com.sessionsense.session_sense

import android.content.ActivityNotFoundException
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The self-updater as the UI sees it; absent in the play flavor, where every update surface is hidden. */
internal class UpdateHandle(val controller: UpdateController, val state: UpdateUiState, val openSheet: () -> Unit)
internal val LocalUpdate = compositionLocalOf<UpdateHandle?> { null }

/**
 * Wraps the app: hosts the update sheet (Home's pill, Settings and the notification all open it), swaps in the
 * blocking screen when this version is no longer supported, and launches the system install dialog.
 */
@Composable internal fun UpdateHost(controller: UpdateController?, openRequest: Boolean, onOpenHandled: () -> Unit, content: @Composable () -> Unit) {
    if (controller == null) { content(); return }
    val state by controller.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(openRequest) { if (openRequest) { sheet = true; onOpenHandled() } }
    val phase = state.phase
    LaunchedEffect(phase) {
        if (phase is InstallPhase.AwaitingConfirmation) {
            try { context.startActivity(phase.intent) } catch (_: ActivityNotFoundException) {}
            controller.confirmationLaunched()
        }
    }
    // Back from "Install unknown apps": carry on if the user allowed it.
    LifecycleResumeEffect(phase) {
        if (phase == InstallPhase.NeedsPermission && context.packageManager.canRequestPackageInstalls()) controller.startUpdate()
        onPauseOrDispose {}
    }
    CompositionLocalProvider(LocalUpdate provides UpdateHandle(controller, state) { sheet = true }) {
        if (state.required) UpdateRequiredScreen(controller, state) else content()
        if (sheet && state.offered != null && !state.required) UpdateSheet(controller, state) { sheet = false }
    }
}

// ─── Home pill ─────────────────────────────────────────────────────────────────────────────────────────

@Composable internal fun UpdatePill() {
    val handle = LocalUpdate.current ?: return
    val pending = handle.state.pending
    AnimatedVisibility(pending != null, enter = fadeIn(smooth()) + expandVertically(smooth()), exit = fadeOut(smooth()) + shrinkVertically(smooth())) {
        val version = pending?.versionName ?: return@AnimatedVisibility
        Box(Modifier.fillMaxWidth().padding(bottom = S3), contentAlignment = Alignment.Center) {
            Pressable(handle.openSheet, Modifier.clip(CircleShape).background(Teal.copy(alpha = .12f)).border(1.dp, Teal.copy(alpha = .32f), CircleShape)) {
                Row(Modifier.heightIn(min = 36.dp).padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Teal), contentAlignment = Alignment.Center) { Glyph(GlyphKind.Download, Bg, Modifier.size(14.dp)) }
                    Spacer(Modifier.width(S1))
                    Text("Update available", color = Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(" · $version", color = Muted, fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    Glyph(GlyphKind.Back, Muted, Modifier.size(11.dp).rotate(180f))
                }
            }
        }
    }
}

// ─── Settings → About ──────────────────────────────────────────────────────────────────────────────────

@Composable internal fun AboutSection(now: Long) {
    val handle = LocalUpdate.current
    SectionLabel("ABOUT")
    Card(padding = 0.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = S3), verticalAlignment = Alignment.CenterVertically) {
            Text("Version", Modifier.weight(1f), color = Text, fontSize = 16.sp)
            Text(BuildConfig.VERSION_NAME, color = Muted, fontFamily = PlexMono, fontSize = 14.sp)
        }
        if (handle == null) {
            Divider()
            Text("Updates arrive through Google Play.", Modifier.padding(S3), color = Muted, fontSize = 13.sp)
            return@Card
        }
        val s = handle.state
        Divider()
        CheckRow(s, now, onCheck = handle.controller::checkNow, onOpen = handle.openSheet)
        Divider()
        Toggle("Check automatically", s.autoCheck) { handle.controller.setAutoCheck(it) }
    }
}

@Composable private fun CheckRow(s: UpdateUiState, now: Long, onCheck: () -> Unit, onOpen: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val offered = s.offered
    val checking = s.check == CheckState.Checking
    Pressable({ if (offered != null) onOpen() else if (!checking) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onCheck() } }, Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = S3, vertical = S2), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (offered != null) "Version ${offered.versionName} available" else "Check for updates",
                    color = if (offered != null) Teal else Text, fontSize = 16.sp, fontWeight = if (offered != null) FontWeight.SemiBold else FontWeight.Normal)
                val detail = when (val c = s.check) {
                    is CheckState.Failed -> c.message
                    CheckState.Checking -> "Checking…"
                    else -> when {
                        offered != null -> "${formatBytes(offered.sizeBytes)} · tap to see what’s new"
                        s.lastCheckMs > 0 -> "Up to date · checked ${ago(now - s.lastCheckMs, s.lastCheckMs)}"
                        else -> null
                    }
                }
                AnimatedContent(detail, transitionSpec = { fadeIn(smooth()) togetherWith fadeOut(smooth()) }, label = "checkDetail") { text ->
                    if (text != null) Text(text, color = if (s.check is CheckState.Failed) Coral else Muted, fontSize = 13.sp)
                }
            }
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(when { checking -> 0; offered != null -> 1; s.check == CheckState.Done -> 2; else -> 3 }, transitionSpec = { (fadeIn(smooth()) + scaleIn(ringSpring(), initialScale = .6f)) togetherWith fadeOut(smooth()) }, label = "checkIcon") { icon ->
                    when (icon) {
                        0 -> CircularProgressIndicator(Modifier.size(18.dp), color = Teal, strokeWidth = 2.dp, trackColor = Teal.copy(alpha = .15f))
                        1 -> Glyph(GlyphKind.Back, Teal, Modifier.size(14.dp).rotate(180f))
                        2 -> Glyph(GlyphKind.Check, Teal, Modifier.size(20.dp))
                        else -> Glyph(GlyphKind.Download, Muted, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

// ─── Update sheet ──────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun UpdateSheet(controller: UpdateController, s: UpdateUiState, dismiss: () -> Unit) {
    val m = s.offered ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { controller.dismissError(); dismiss() }, sheetState = sheetState, containerColor = Surface, contentColor = Text, scrimColor = Color.Black.copy(alpha = .55f)) {
        Column(Modifier.padding(horizontal = Gutter).padding(bottom = S5)) {
            UpdateHeader(m)
            Spacer(Modifier.height(S4))
            if (m.notes.isNotBlank()) {
                Label("WHAT’S NEW")
                Spacer(Modifier.height(S1))
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) { ReleaseNotes(m.notes) }
                Spacer(Modifier.height(S4))
            }
            UpdateActions(controller, s, required = false, onLater = dismiss, onSkip = { controller.skip(m.versionCode); dismiss() })
        }
    }
}

@Composable private fun UpdateHeader(m: UpdateManifest, centered: Boolean = false) {
    val icon = @Composable {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Teal, lerp(Teal, Blue, .6f)))), contentAlignment = Alignment.Center) {
            Glyph(GlyphKind.Download, Bg, Modifier.size(32.dp))
        }
    }
    val published = runCatching { Instant.parse(m.publishedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d")) }.getOrNull()
    val detail = listOfNotNull(formatBytes(m.sizeBytes), published).joinToString(" · ")
    if (centered) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        icon(); Spacer(Modifier.height(S3))
        Text("SessionSense ${m.versionName}", color = Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, color = Muted, fontSize = 13.sp)
    } else Row(verticalAlignment = Alignment.CenterVertically) {
        icon(); Spacer(Modifier.width(S3))
        Column {
            Text("SessionSense ${m.versionName}", color = Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, fontSize = 13.sp)
        }
    }
}

/** Short markdown: "# heading", "- bullet" / "* bullet", blank lines and plain lines; **bold** markers are dropped. */
@Composable private fun ReleaseNotes(notes: String) = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    notes.lines().map { it.trimEnd().replace("**", "").replace("`", "") }.dropWhile { it.isBlank() }.forEach { line ->
        val t = line.trim()
        when {
            t.isEmpty() -> Spacer(Modifier.height(2.dp))
            t.startsWith("#") -> Text(t.trimStart('#').trim(), color = Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") -> Row {
                Box(Modifier.padding(top = 8.dp, end = S2).size(5.dp).clip(CircleShape).background(Teal))
                Text(t.drop(2).trim(), color = Muted, fontSize = 15.sp, lineHeight = 21.sp)
            }
            else -> Text(t, color = Muted, fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
}

/** The part of the sheet (and the blocking screen) that follows the download → verify → install flow. */
@Composable private fun UpdateActions(controller: UpdateController, s: UpdateUiState, required: Boolean, onLater: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    // Keyed by phase type, so download progress updates in place and only a change of step animates.
    AnimatedContent(s.phase, contentKey = { it::class }, transitionSpec = { (fadeIn(smooth()) + expandVertically(smooth())) togetherWith (fadeOut(smooth()) + shrinkVertically(smooth())) }, label = "updatePhase") { phase ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S2)) {
            when (phase) {
                InstallPhase.Idle -> {
                    CapsuleButton("Update now", controller::startUpdate, haptic = HapticFeedbackType.Confirm)
                    if (!required) {
                        CapsuleButton("Later", onLater, style = CapsuleStyle.Secondary)
                        TextAction("Skip this version", Faint, onSkip)
                    }
                }
                is InstallPhase.Downloading -> {
                    Progress(phase.fraction, "Downloading", "${formatBytes(phase.bytes)} of ${formatBytes(phase.total)}")
                    TextAction("Cancel", Muted, controller::cancel)
                }
                InstallPhase.Verifying -> Progress(null, "Verifying", "Checking the fingerprint and signature…")
                InstallPhase.Installing, is InstallPhase.AwaitingConfirmation -> Progress(null, "Installing", "Confirm in the Android dialog. SessionSense restarts when it’s done.")
                InstallPhase.NeedsPermission -> {
                    Notice(Blue, "Allow updates from SessionSense", "Android asks once before an app can install its own updates. Turn on “Allow from this source”, then come back — the update continues on its own.")
                    CapsuleButton("Open Settings", { runCatching { context.startActivity(controller.openInstallPermissionSettings()) } })
                    TextAction("Not now", Muted, controller::dismissError)
                }
                is InstallPhase.Failed -> {
                    Notice(Coral, phase.title, phase.message)
                    CapsuleButton("Try again", controller::startUpdate)
                    if (!required) TextAction("Close", Muted) { controller.dismissError(); onLater() }
                }
            }
        }
    }
}

@Composable private fun TextAction(label: String, color: Color, onClick: () -> Unit) =
    Pressable(onClick, Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) { Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium) }

@Composable private fun Notice(color: Color, title: String, body: String) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(color.copy(alpha = .10f)).border(1.dp, color.copy(alpha = .30f), shape).padding(S3)) {
        Text(title, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(body, color = Text.copy(alpha = .86f), fontSize = 14.sp, lineHeight = 20.sp)
    }
}

/** A capsule progress track; [fraction] null draws an indeterminate shimmer. */
@Composable private fun Progress(fraction: Float?, title: String, detail: String) = Column(Modifier.fillMaxWidth().padding(vertical = S1)) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, Modifier.weight(1f), color = Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (fraction != null) Text("${(fraction * 100).toInt()}%", color = Teal, fontFamily = PlexMono, fontSize = 14.sp)
    }
    Spacer(Modifier.height(S1))
    val animated by animateFloatAsState(fraction ?: 0f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow), label = "download")
    val shimmer by rememberInfiniteTransition(label = "shimmer").animateFloat(-.4f, 1.4f, infiniteRepeatable(tween(1_300, easing = FastOutSlowInEasing)), label = "shimmerX")
    BoxWithConstraints(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Color.White.copy(alpha = .08f))) {
        if (fraction != null) Box(Modifier.fillMaxHeight().fillMaxWidth(animated).clip(CircleShape).background(Brush.horizontalGradient(listOf(Teal, lerp(Teal, Blue, .5f)))))
        else Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Transparent, Teal.copy(alpha = .8f), Color.Transparent),
            startX = constraints.maxWidth * (shimmer - .3f), endX = constraints.maxWidth * (shimmer + .3f))))
    }
    Spacer(Modifier.height(S1))
    Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
}

// ─── Blocking screen ───────────────────────────────────────────────────────────────────────────────────

/** Shown instead of the app when the installed build is below minSupportedVersionCode. It has no way past it but updating. */
@Composable internal fun UpdateRequiredScreen(controller: UpdateController, s: UpdateUiState) {
    val m = s.offered ?: return
    Box(Modifier.fillMaxSize().background(Bg)) {
        Backdrop(Modifier.matchParentSize(), Amber, .7f) { Offset(it.width / 2f, it.height * .22f) }
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(S5 + S4))
                UpdateHeader(m, centered = true)
                Spacer(Modifier.height(S5))
                Text("Update required", color = Text, fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.6).sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(S2))
                Text("This version of SessionSense (${s.installedName}) is no longer supported. Update to keep tracking — your accounts and history stay as they are.",
                    color = Muted, fontSize = 16.sp, lineHeight = 23.sp, textAlign = TextAlign.Center)
                if (m.notes.isNotBlank()) {
                    Spacer(Modifier.height(S4))
                    Card { Label("WHAT’S NEW"); Spacer(Modifier.height(S1)); ReleaseNotes(m.notes) }
                }
                Spacer(Modifier.height(S4))
            }
            UpdateActions(controller, s, required = true, onLater = {}, onSkip = {})
            Spacer(Modifier.height(S4))
        }
    }
}

// ─── Formatting ────────────────────────────────────────────────────────────────────────────────────────

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

private fun ago(elapsedMs: Long, atMs: Long): String {
    val m = elapsedMs / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        else -> Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d"))
    }
}
