package com.lexiflow

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.di.AppContainer
import com.lexiflow.util.Clock
import java.time.ZoneId

/** Controllable clock for deterministic scheduling tests. */
class FakeClock(var nowMs: Long = 1_760_000_000_000L, private val tz: ZoneId = ZoneId.of("Europe/Istanbul")) : Clock {
    override fun now(): Long = nowMs
    override fun zone(): ZoneId = tz
    fun advanceMinutes(m: Long) { nowMs += m * 60_000L }
    fun advanceDays(d: Long) { nowMs += d * 86_400_000L }
}

fun testContainer(clock: FakeClock = FakeClock()): AppContainer {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
    val c = AppContainer(ctx, db, clock)
    // DataStore is process-wide; reset it so tests never see each other's settings.
    kotlinx.coroutines.runBlocking { c.settings.update { com.lexiflow.data.settings.AppSettings(onboardingDone = true) } }
    return c
}
