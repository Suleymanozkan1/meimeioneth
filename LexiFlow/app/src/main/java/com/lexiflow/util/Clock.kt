package com.lexiflow.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Injectable time source so scheduling, streaks and quiet hours are testable. */
interface Clock {
    fun now(): Long
    fun zone(): ZoneId = ZoneId.systemDefault()
    fun today(): LocalDate = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate()
    fun localDate(at: Long): String = Instant.ofEpochMilli(at).atZone(zone()).toLocalDate().toString()
    fun hourOfDay(): Int = Instant.ofEpochMilli(now()).atZone(zone()).hour
    fun startOfToday(): Long = today().atStartOfDay(zone()).toInstant().toEpochMilli()
    fun startOfWeek(): Long = com.lexiflow.core.gamification.WeeklyMissions.weekStart(today()).atStartOfDay(zone()).toInstant().toEpochMilli()

    object System : Clock {
        override fun now(): Long = java.lang.System.currentTimeMillis()
    }
}

/** Emits immediately and then every [periodMs], so "now"/"today"-dependent queries do not go stale. */
fun ticks(periodMs: Long = 60_000L): kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.flow {
    while (true) { emit(Unit); kotlinx.coroutines.delay(periodMs) }
}

/** True when [hour] falls inside the quiet window [start, end) which may wrap past midnight. */
fun isQuietHour(hour: Int, start: Int, end: Int): Boolean = when {
    start == end -> false
    start < end -> hour in start until end
    else -> hour >= start || hour < end
}
