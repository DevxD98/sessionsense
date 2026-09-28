package com.sessionsense.session_sense

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

// Model colours in share order; everything past these is grouped as "Other".
private val ModelColors = listOf(Teal, Blue, Color(0xFFB79BFF), Amber, Coral)
private val OtherColor = Faint

/** The top models by share, with the tail folded into one "Other" slice. */
private fun slices(a: CodexAnalytics): List<Triple<String, Double, Color>> {
    val shared = a.models.filter { it.sharePct > 0 }
    val top = shared.take(ModelColors.size - 1).mapIndexed { i, m -> Triple(codexModelName(m.id), m.sharePct, ModelColors[i]) }
    val rest = shared.drop(ModelColors.size - 1).sumOf { it.sharePct }
    return if (rest >= .5) top + Triple("Other", rest, OtherColor) else top
}

/** Home, Codex accounts: which models the week's usage went to, like chatgpt.com's Analytics page. */
@Composable internal fun CodexModelsCard(a: CodexAnalytics) {
    val parts = slices(a)
    if (parts.isEmpty()) return
    val grow = remember(a.fetchedAtMs) { Animatable(0f) }
    LaunchedEffect(a.fetchedAtMs) { grow.animateTo(1f, spring(dampingRatio = .8f, stiffness = Spring.StiffnessLow)) }
    Card {
        Row(verticalAlignment = Alignment.Bottom) {
            Label("THIS WEEK BY MODEL", Modifier.weight(1f), color = Teal)
            if (a.totalMessages > 0) Text("${a.totalMessages} messages", color = Faint, fontSize = 12.sp)
        }
        Spacer(Modifier.height(S2))
        // One capsule split into the models' shares.
        Canvas(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(Color.White.copy(alpha = .06f))) {
            val total = parts.sumOf { it.second }.toFloat().coerceAtLeast(1f)
            var x = 0f; val gap = 2.dp.toPx()
            parts.forEach { (_, share, color) ->
                val w = size.width * (share.toFloat() / total) * grow.value
                if (w > gap) drawRoundRect(color, Offset(x, 0f), Size(w - gap, size.height), CornerRadius(size.height / 2))
                x += w
            }
        }
        Spacer(Modifier.height(S2))
        parts.forEach { (name, share, color) ->
            val fast = a.models.firstOrNull { codexModelName(it.id) == name }?.fast == true
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(S1 + 2.dp))
                Text(name, color = Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (fast) Text("FAST", Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(Amber.copy(alpha = .14f)).padding(horizontal = 5.dp, vertical = 1.dp),
                    color = Amber, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
                Spacer(Modifier.weight(1f))
                Text(pct(share), color = Muted, fontFamily = PlexMono, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(4.dp)); Text("Share of plan usage · last 7 days, from chatgpt.com", color = Faint, fontSize = 12.sp)
    }
}

/** History, Codex accounts: messages per day by model, where Codex was used, and token totals. */
@Composable internal fun CodexHistorySection(a: CodexAnalytics, now: Long) {
    val models = a.models.filter { it.messages > 0 }.sortedByDescending { it.messages }
    val colorOf = models.mapIndexed { i, m -> m.id to (ModelColors.getOrNull(i) ?: OtherColor) }.toMap()

    SectionLabel("CODEX · LAST 7 DAYS")
    Row(horizontalArrangement = Arrangement.spacedBy(S2)) {
        MiniStat("Messages", "${a.totalMessages}", Modifier.weight(1f))
        MiniStat("Tokens", compact(a.totalTokens), Modifier.weight(1f))
        MiniStat("Cached", "${a.cacheHitPct.toInt()}%", Modifier.weight(1f))
    }

    if (models.isNotEmpty()) {
        Spacer(Modifier.height(S2))
        Card(padding = S3) {
            Label("MESSAGES BY MODEL"); Spacer(Modifier.height(S3))
            // A fixed 7-day axis ending today, so quiet days still show as gaps.
            val today = LocalDate.now()
            val axis = (6 downTo 0).map { today.minusDays(it.toLong()) }
            val byDate = a.days.associateBy { it.date }
            val maxDay = axis.maxOf { d -> byDate[d.toString()]?.byModel?.values?.sum() ?: 0 }.coerceAtLeast(1)
            val grow = remember(a.fetchedAtMs) { Animatable(0f) }
            LaunchedEffect(a.fetchedAtMs) { grow.animateTo(1f, spring(dampingRatio = .75f, stiffness = Spring.StiffnessLow)) }
            Canvas(Modifier.fillMaxWidth().height(132.dp)) {
                val gap = 10.dp.toPx(); val w = (size.width - gap * 6) / 7; val r = CornerRadius(w / 3)
                axis.forEachIndexed { i, d ->
                    val x = i * (w + gap)
                    drawRoundRect(Color.White.copy(alpha = .05f), Offset(x, 0f), Size(w, size.height), r)
                    var top = size.height
                    models.forEach { m ->
                        val count = byDate[d.toString()]?.byModel?.get(m.id) ?: 0
                        val h = size.height * count / maxDay * grow.value
                        if (h > 0f) { drawRoundRect(colorOf.getValue(m.id), Offset(x, top - h), Size(w, h), r); top -= h }
                    }
                }
            }
            Spacer(Modifier.height(S1))
            Row(Modifier.fillMaxWidth()) {
                axis.forEachIndexed { i, d -> Text(d.dayOfWeek.name.take(1), Modifier.weight(1f), color = if (i == 6) Text else Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
            }
            Spacer(Modifier.height(S2))
            models.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(colorOf.getValue(m.id)))
                    Spacer(Modifier.width(S1 + 2.dp))
                    Text(codexModelName(m.id), Modifier.weight(1f), color = Text, fontSize = 14.sp)
                    Text("${m.messages}", color = Muted, fontFamily = PlexMono, fontSize = 13.sp)
                }
            }
        }
    }

    if (a.surfaces.isNotEmpty()) {
        Spacer(Modifier.height(S2))
        Card(padding = S3) {
            Label("WHERE YOU USE CODEX"); Spacer(Modifier.height(S2))
            a.surfaces.filter { it.sharePct >= .5 }.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(codexSurfaceName(s.id), Modifier.width(112.dp), color = Text, fontSize = 14.sp, maxLines = 1)
                    Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(Color.White.copy(alpha = .06f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((s.sharePct / 100).toFloat().coerceIn(.02f, 1f)).clip(CircleShape).background(Blue))
                    }
                    Text(pct(s.sharePct), Modifier.width(48.dp), color = Muted, fontFamily = PlexMono, fontSize = 13.sp, textAlign = TextAlign.End)
                }
            }
        }
    }
    Spacer(Modifier.height(S1))
    Text("From chatgpt.com Analytics · updated ${updated(now - a.fetchedAtMs)}", color = Faint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable private fun MiniStat(label: String, value: String, modifier: Modifier) = Card(modifier) {
    Label(label.uppercase()); Spacer(Modifier.height(6.dp))
    Text(value, color = Text, fontFamily = PlexMono, fontSize = 22.sp, maxLines = 1)
}

private fun pct(v: Double) = if (v < 1) "<1%" else "${v.toInt()}%"
private fun compact(v: Long) = when { v >= 1_000_000_000 -> "%.1fB".format(v / 1e9); v >= 1_000_000 -> "${v / 1_000_000}M"; v >= 1_000 -> "${v / 1_000}K"; else -> "$v" }
private fun updated(ms: Long): String { val m = ms / 60_000; return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; else -> "${m / 60} h ago" } }
