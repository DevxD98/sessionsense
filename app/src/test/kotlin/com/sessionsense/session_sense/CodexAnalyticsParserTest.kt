package com.sessionsense.session_sense

import org.junit.Assert.*
import org.junit.Test

class CodexAnalyticsParserTest {
    private fun fixture(name: String) = javaClass.getResource("/codex/$name.json")!!.readText()
    private val fetchedAt = 1_790_600_000_000L
    private fun parse() = CodexAnalyticsParser.parse(fixture("analytics_breakdown"), fixture("analytics_counts"), fetchedAt)!!

    @Test fun modelSharesMergeSpeedsAndSortByShare() {
        val models = parse().models
        assertEquals(listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-6-astra", "gpt-5.6-luna", "gpt-image-2", "codex-auto-review"), models.map { it.id })
        assertEquals(67.6, models[0].sharePct, 0.1)
        assertEquals(25.0, models[1].sharePct, 0.1)
        assertTrue("terra ran in fast mode", models[1].fast); assertFalse(models[0].fast)
        assertEquals(100.0, models.sumOf { it.sharePct }, 0.01)
    }

    @Test fun messagesByModelComeFromTurns() {
        val a = parse()
        assertEquals(104, a.totalMessages)
        assertEquals(mapOf("gpt-5.6-sol" to 53, "gpt-5.6-terra" to 27, "gpt-5.6-luna" to 23, "gpt-6-astra" to 1), a.models.filter { it.messages > 0 }.associate { it.id to it.messages })
        assertEquals(listOf("2026-09-24" to 45, "2026-09-25" to 59, "2026-09-27" to 0), a.days.map { it.date to it.messages })
        assertEquals(29, a.days[0].byModel["gpt-5.6-sol"])
    }

    @Test fun surfacesSkipZeroesAndSortByShare() {
        val s = parse().surfaces
        assertEquals(listOf("vscode", "desktop_app", "work_desktop"), s.map { it.id })
        assertEquals(83.4, s[0].sharePct, 0.1); assertEquals(16.4, s[1].sharePct, 0.1)
    }

    @Test fun tokenTotals() {
        val a = parse()
        assertEquals(119_412_047L, a.totalTokens); assertEquals(112_716_800L, a.cachedTokens); assertEquals(502_917L, a.outputTokens)
        assertEquals(94.4, a.cacheHitPct, 0.1)
    }

    @Test fun roundTripsThroughStorage() {
        val a = parse()
        assertEquals(a, CodexAnalytics.fromJson(a.toJson()))
    }

    @Test fun friendlyNames() {
        assertEquals("GPT‑5.6 Sol", codexModelName("gpt-5.6-sol"))
        assertEquals("GPT‑6 Astra", codexModelName("gpt-6-astra"))
        assertEquals("Codex auto review", codexModelName("codex-auto-review"))
        assertEquals("GPT image 2", codexModelName("gpt-image-2"))
        assertEquals("VS Code", codexSurfaceName("vscode")); assertEquals("Desktop app", codexSurfaceName("desktop_app"))
        assertEquals("Some new thing", codexSurfaceName("some_new_thing"))
    }

    @Test fun degradesInsteadOfFailing() {
        assertNull(CodexAnalyticsParser.parse("<html>", "{}", fetchedAt))
        assertNull(CodexAnalyticsParser.parse("""{"data":[]}""", """{"data":[]}""", fetchedAt))
        // One endpoint changing shape still leaves the other's data.
        val onlyCounts = CodexAnalyticsParser.parse("garbage", fixture("analytics_counts"), fetchedAt)!!
        assertEquals(104, onlyCounts.totalMessages); assertTrue(onlyCounts.surfaces.isEmpty())
        val onlyBreakdown = CodexAnalyticsParser.parse(fixture("analytics_breakdown"), "garbage", fetchedAt)!!
        assertEquals(0, onlyBreakdown.totalMessages); assertEquals("gpt-5.6-sol", onlyBreakdown.models[0].id)
    }
}
