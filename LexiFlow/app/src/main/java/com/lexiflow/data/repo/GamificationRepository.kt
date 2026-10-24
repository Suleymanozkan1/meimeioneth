package com.lexiflow.data.repo

import android.util.Log
import com.lexiflow.core.active.Skill
import com.lexiflow.core.gamification.Achievements
import com.lexiflow.core.gamification.LearningTotals
import com.lexiflow.core.gamification.LevelInfo
import com.lexiflow.core.gamification.Levels
import com.lexiflow.core.gamification.MissionProgress
import com.lexiflow.core.gamification.StreakCalculator
import com.lexiflow.core.gamification.StreakInfo
import com.lexiflow.core.gamification.WeeklyMissions
import com.lexiflow.core.gamification.XpReason
import com.lexiflow.data.db.AchievementEntity
import com.lexiflow.data.db.ActivityDayEntity
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.db.XpTransactionEntity
import com.lexiflow.data.settings.SettingsRepository
import com.lexiflow.util.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate

data class ActivityDelta(val reviews: Int = 0, val newCards: Int = 0, val productions: Int = 0, val speakingMs: Long = 0)

data class ProgressSnapshot(
    val totalXp: Int,
    val level: LevelInfo,
    val streak: StreakInfo,
    val todayXp: Int,
    val goalXp: Int,
)

/**
 * XP, levels, streaks, achievements and weekly missions. XP is only granted for learning behaviour
 * (see [XpReason]); opening the app earns nothing. Every grant is an append-only XpTransaction.
 */
class GamificationRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    private val dao = db.gamificationDao()

    suspend fun award(reason: XpReason, refId: Long? = null, delta: ActivityDelta = ActivityDelta(), multiplier: Double = 1.0): Int {
        val now = clock.now()
        val date = clock.localDate(now)
        val xp = (reason.xp * multiplier).toInt().coerceAtLeast(0)
        if (xp > 0) dao.insertXp(XpTransactionEntity(at = now, localDate = date, reason = reason.name, xp = xp, refId = refId))
        recordActivity(delta, xp)
        return xp
    }

    suspend fun recordActivity(delta: ActivityDelta, xp: Int = 0) {
        val date = clock.localDate(clock.now())
        val goal = settings.current().dailyGoalXp
        val day = dao.day(date) ?: ActivityDayEntity(localDate = date, goalXp = goal, tzId = clock.zone().id)
        val newXp = day.xp + xp
        dao.upsertDay(
            day.copy(
                xp = newXp,
                reviews = day.reviews + delta.reviews,
                newCards = day.newCards + delta.newCards,
                productions = day.productions + delta.productions,
                speakingMs = day.speakingMs + delta.speakingMs,
                goalXp = goal,
                goalMet = day.goalMet || newXp >= goal,
            ),
        )
        runCatching { checkAchievements() }.onFailure { Log.w(TAG, "achievement check failed", it) }
    }

    /**
     * The day's minimum task (a finished autopilot session, e.g. "Bugün sadece 5 dk var") counts as meeting the
     * daily goal for the streak, even when the XP goal was not reached.
     */
    suspend fun markMinimumTaskDone() {
        val date = clock.localDate(clock.now())
        val goal = settings.current().dailyGoalXp
        val day = dao.day(date) ?: ActivityDayEntity(localDate = date, goalXp = goal, tzId = clock.zone().id)
        if (!day.goalMet) dao.upsertDay(day.copy(goalMet = true))
    }

    /** Streak counts days where the daily goal was met. A day is never retroactively changed. */
    fun observeProgress(): Flow<ProgressSnapshot> = combine(
        dao.observeTotalXp(),
        dao.observeDays(),
        settings.settings,
    ) { total, days, s ->
        val today = clock.today()
        val goalDays = days.filter { it.goalMet }.mapNotNull { runCatching { LocalDate.parse(it.localDate) }.getOrNull() }.toSet()
        val todayXp = days.firstOrNull { it.localDate == today.toString() }?.xp ?: 0
        ProgressSnapshot(total, Levels.fromXp(total), StreakCalculator.compute(goalDays, today), todayXp, s.dailyGoalXp)
    }

    fun observeAchievements() = dao.observeAchievements().map { unlocked ->
        val ids = unlocked.associateBy { it.id }
        Achievements.all.map { it to ids[it.id]?.unlockedAt }
    }

    suspend fun totals(since: Long? = null): LearningTotals {
        val s = since ?: 0L
        val days = dao.days()
        val goalDays = days.filter { it.goalMet }.mapNotNull { runCatching { LocalDate.parse(it.localDate) }.getOrNull() }.toSet()
        return LearningTotals(
            reviews = db.reviewLogDao().countSince(s),
            wordsLearned = db.reviewLogDao().newLearnedSince(s),
            productionSuccesses = db.practiceDao().successesSince(s, Skill.PRODUCTION.name) + db.practiceDao().successesSince(s, Skill.SPEAKING.name),
            speakingTurns = db.speakingDao().learnerTurnsSince(s),
            pronunciationAttempts = db.pronunciationDao().countSince(s),
            storiesCompleted = db.storyDao().completedSince(s),
            competitionsPlayed = db.competitionDao().countSince(s),
            longestStreak = StreakCalculator.compute(goalDays, clock.today()).longest,
            overdueCleared = db.reviewLogDao().overdueClearedSince(s),
            importedWords = db.wordDao().importedCount(),
        )
    }

    suspend fun weeklyMissions(): List<MissionProgress> {
        val weekStart = WeeklyMissions.weekStart(clock.today())
        val totals = totals(clock.startOfWeek())
        return WeeklyMissions.progress(WeeklyMissions.forWeek(weekStart), totals)
    }

    private suspend fun checkAchievements() {
        val totals = totals()
        val now = clock.now()
        Achievements.all.filter { it.isUnlocked(totals) }.forEach { dao.unlock(AchievementEntity(it.id, now)) }
    }

    companion object { private const val TAG = "Gamification" }
}
