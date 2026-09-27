package com.sessionsense.session_sense

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** When the polling service should persist a [UsageSample]: on any change, or as a heartbeat so curves stay continuous. */
object SamplePolicy {
    const val HEARTBEAT_MS = 10 * 60_000L
    const val RETENTION_MS = 90 * 86_400_000L

    fun shouldRecord(last: UsageSample?, next: UsageSample): Boolean =
        last == null || next.ts - last.ts >= HEARTBEAT_MS ||
            last.sessionPct != next.sessionPct || last.weeklyPct != next.weeklyPct ||
            last.opusPct != next.opusPct || last.sonnetPct != next.sonnetPct
}

/** Usage history derived from stored samples and completed sessions; everything here comes from real polls. */
data class UsageHistory(
    val samples: List<UsageSample> = emptyList(),
    /** Peak 5-hour session % per day, oldest first, today last (includes the in-progress window). */
    val dailyPeaks: List<Int> = List(7) { 0 },
    /** Days in the last 7 with any session usage. */
    val activeDays: Int = 0,
) {
    /** Samples in [fromMs, toMs], ascending. */
    fun between(fromMs: Long, toMs: Long): List<UsageSample> {
        var lo = 0; var hi = samples.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (samples[mid].ts < fromMs) lo = mid + 1 else hi = mid }
        val out = ArrayList<UsageSample>()
        for (i in lo until samples.size) { if (samples[i].ts > toMs) break; out += samples[i] }
        return out
    }

    companion object {
        fun build(samples: List<UsageSample>, sessions: List<SessionRecord>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): UsageHistory {
            fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            val first = today.minusDays(6)
            val peaks = IntArray(7)
            fun offer(date: LocalDate, pct: Int) { val i = (date.toEpochDay() - first.toEpochDay()).toInt(); if (i in 0..6 && pct > peaks[i]) peaks[i] = pct }
            samples.forEach { offer(day(it.ts), it.sessionPct) }
            // Sessions recorded before sampling existed still count toward their start day.
            sessions.forEach { offer(day(it.startMs), it.pctUsed) }
            val sorted = if (samples.zipWithNext().all { (a, b) -> a.ts <= b.ts }) samples else samples.sortedBy { it.ts }
            return UsageHistory(sorted, peaks.toList(), peaks.count { it > 0 })
        }
    }
}

internal fun startOfDayMs(date: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()) = date.atStartOfDay(zone).toInstant().toEpochMilli()
