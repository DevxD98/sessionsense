package com.sessionsense.session_sense

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Codex usage by model, surface and day, from the two chatgpt.com endpoints behind Settings → Usage → Analytics:
 * `wham/analytics/daily-token-usage-breakdown` (share of plan usage per model and surface) and
 * `wham/analytics/daily-workspace-usage-counts` (messages, threads and tokens). Both are unofficial.
 */
data class CodexAnalytics(
    val fetchedAtMs: Long,
    /** Every model seen, by share of usage (descending); models with messages but no usage share are kept. */
    val models: List<ModelUsage>,
    val surfaces: List<SurfaceUsage>,
    val days: List<DayUsage>,
    val totalMessages: Int,
    val totalTokens: Long,
    val cachedTokens: Long,
    val outputTokens: Long,
) {
    data class ModelUsage(val id: String, val sharePct: Double, val messages: Int, val fast: Boolean)
    data class SurfaceUsage(val id: String, val sharePct: Double)
    data class DayUsage(val date: String, val messages: Int, val byModel: Map<String, Int>)

    val cacheHitPct get() = if (totalTokens > 0) cachedTokens * 100.0 / totalTokens else 0.0
    val isEmpty get() = models.isEmpty() && totalMessages == 0 && totalTokens == 0L

    fun toJson(): String = JSONObject()
        .put("fetchedAtMs", fetchedAtMs).put("totalMessages", totalMessages)
        .put("totalTokens", totalTokens).put("cachedTokens", cachedTokens).put("outputTokens", outputTokens)
        .put("models", JSONArray(models.map { JSONObject().put("id", it.id).put("share", it.sharePct).put("messages", it.messages).put("fast", it.fast) }))
        .put("surfaces", JSONArray(surfaces.map { JSONObject().put("id", it.id).put("share", it.sharePct) }))
        .put("days", JSONArray(days.map { d -> JSONObject().put("date", d.date).put("messages", d.messages).put("byModel", JSONObject(d.byModel as Map<*, *>)) }))
        .toString()

    companion object {
        fun fromJson(json: String): CodexAnalytics? = runCatching {
            val o = JSONObject(json)
            fun <T> list(name: String, map: (JSONObject) -> T) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { map(a.getJSONObject(it)) } }.orEmpty()
            CodexAnalytics(o.getLong("fetchedAtMs"),
                list("models") { ModelUsage(it.getString("id"), it.getDouble("share"), it.getInt("messages"), it.getBoolean("fast")) },
                list("surfaces") { SurfaceUsage(it.getString("id"), it.getDouble("share")) },
                list("days") { d -> DayUsage(d.getString("date"), d.getInt("messages"), d.getJSONObject("byModel").let { m -> m.keys().asSequence().associateWith { m.getInt(it) } }) },
                o.getInt("totalMessages"), o.getLong("totalTokens"), o.getLong("cachedTokens"), o.getLong("outputTokens"))
        }.getOrNull()
    }
}

object CodexAnalyticsParser {
    /** Either body may be unusable (the other's data is still returned); null only when neither yields anything. */
    fun parse(breakdownBody: String, countsBody: String, fetchedAtMs: Long): CodexAnalytics? {
        val breakdown = rows(breakdownBody)
        val counts = rows(countsBody)

        val credits = linkedMapOf<String, Double>(); val fast = mutableSetOf<String>(); val surfaces = linkedMapOf<String, Double>()
        breakdown.forEach { day ->
            day.optJSONArray("models")?.objects()?.forEach { m ->
                val id = m.optString("model").takeIf { it.isNotBlank() } ?: return@forEach
                val c = m.optDouble("credits", 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0
                credits[id] = (credits[id] ?: 0.0) + c
                if (c > 0 && m.optString("speed") == "fast") fast += id
            }
            day.optJSONObject("product_surface_usage_values")?.let { s ->
                s.keys().forEach { k -> val v = s.optDouble(k, 0.0); if (v.isFinite() && v > 0) surfaces[k] = (surfaces[k] ?: 0.0) + v }
            }
        }

        val messages = linkedMapOf<String, Int>(); val days = mutableListOf<CodexAnalytics.DayUsage>()
        var totalMessages = 0; var tokens = 0L; var cached = 0L; var output = 0L
        counts.forEach { day ->
            val totals = day.optJSONObject("totals")
            val turns = totals?.optInt("turns", 0)?.coerceAtLeast(0) ?: 0
            totalMessages += turns
            tokens += totals?.optLong("text_total_tokens", 0L)?.coerceAtLeast(0) ?: 0
            cached += totals?.optLong("cached_text_input_tokens", 0L)?.coerceAtLeast(0) ?: 0
            output += totals?.optLong("text_output_tokens", 0L)?.coerceAtLeast(0) ?: 0
            val byModel = linkedMapOf<String, Int>()
            day.optJSONArray("models")?.objects()?.forEach { m ->
                val id = m.optString("model").takeIf { it.isNotBlank() } ?: return@forEach
                val t = m.optInt("turns", 0).coerceAtLeast(0)
                if (t > 0) { byModel[id] = (byModel[id] ?: 0) + t; messages[id] = (messages[id] ?: 0) + t }
            }
            day.optString("date").takeIf { it.isNotBlank() }?.let { days += CodexAnalytics.DayUsage(it, turns, byModel) }
        }

        val totalCredits = credits.values.sum()
        val models = (credits.keys + messages.keys).distinct()
            .map { CodexAnalytics.ModelUsage(it, if (totalCredits > 0) (credits[it] ?: 0.0) * 100 / totalCredits else 0.0, messages[it] ?: 0, it in fast) }
            .filter { it.sharePct > 0 || it.messages > 0 }
            .sortedWith(compareByDescending<CodexAnalytics.ModelUsage> { it.sharePct }.thenByDescending { it.messages })
        val surfaceTotal = surfaces.values.sum()
        val surfaceShares = surfaces.filterKeys { it != "unknown" }.map { (k, v) -> CodexAnalytics.SurfaceUsage(k, v * 100 / surfaceTotal) }.sortedByDescending { it.sharePct }

        return CodexAnalytics(fetchedAtMs, models, surfaceShares, days.sortedBy { it.date }, totalMessages, tokens, cached, output).takeUnless { it.isEmpty }
    }

    private fun rows(body: String): List<JSONObject> =
        (runCatching { JSONTokener(body).nextValue() as? JSONObject }.getOrNull()?.optJSONArray("data"))?.objects().orEmpty()

    private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
}

/** "gpt-5.6-sol" → "GPT‑5.6 Sol"; anything unrecognised reads as words ("codex-auto-review" → "Codex auto review"). */
fun codexModelName(id: String): String {
    Regex("^gpt-(\\d+(?:\\.\\d+)?)-([a-z]+)$").matchEntire(id)?.destructured?.let { (v, name) -> return "GPT‑$v ${name.replaceFirstChar(Char::uppercaseChar)}" }
    return id.split('-', '_').joinToString(" ") { if (it == "gpt") "GPT" else it }.replaceFirstChar(Char::uppercaseChar)
}

fun codexSurfaceName(id: String) = when (id) {
    "vscode" -> "VS Code"; "desktop_app" -> "Desktop app"; "work_desktop" -> "Work desktop"; "cli" -> "CLI"; "web" -> "Web"
    "work_web" -> "Work web"; "mobile" -> "Mobile"; "work_mobile" -> "Work mobile"; "jetbrains" -> "JetBrains"; "github" -> "GitHub"
    "github_code_review" -> "GitHub review"; "sdk" -> "SDK"; "exec" -> "Exec"; "slack" -> "Slack"; "linear" -> "Linear"
    else -> id.split('_').joinToString(" ").replaceFirstChar(Char::uppercaseChar)
}
