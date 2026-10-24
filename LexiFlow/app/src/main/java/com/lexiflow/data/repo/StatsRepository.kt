package com.lexiflow.data.repo

import com.lexiflow.core.active.Skill
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.db.CardDetail
import com.lexiflow.util.Clock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest

data class WeeklyStats(
    val newWords: Int,
    val reviews: Int,
    val distinctReviewed: Int,
    val activeSuccesses: Int,
    val activeAttempts: Int,
    val speakingMinutes: Double,
    val pronunciationAttempts: Int,
    val lockScreenAnswers: Int,
    val weakNow: Int,
    val weakImproved: Int,
    val retention30d: Double?,
    val bestDayXp: Int,
    val bestBattle: Int?,
)

data class RiskItem(val card: CardDetail, val retrievability: Double)

/** One day of the active/passive chart (counts at the end of that day). */
data class VocabularyPoint(val localDate: String, val active: Int, val passive: Int)

/**
 * Passive = words the learner reviewed/recognises; active = words used correctly without hints in word production,
 * sentence building or speaking (see ACTIVE_USE_WHERE). A word counts in one of the two, never both.
 */
data class ActiveUsage(val active: Int, val passive: Int, val series: List<VocabularyPoint>, val activatedThisWeek: Int)

class StatsRepository(
    private val db: AppDatabase,
    private val study: StudyRepository,
    private val active: ActiveRecallRepository,
    private val clock: Clock,
) {
    fun reviewsPerDay() = db.reviewLogDao().observePerDay(fromDate())
    fun xpPerDay() = db.gamificationDao().observeXpPerDay(fromDate())
    fun activePerDay() = db.practiceDao().observeSuccessPerDay(fromDate())
    fun skillAverages() = db.skillDao().observeAverages()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun dueCount() = com.lexiflow.util.ticks().flatMapLatest { db.cardDao().observeDueCount(clock.now()) }.distinctUntilChanged()
    fun newCount() = db.cardDao().observeNewCount()
    fun matureCount() = db.cardDao().observeMatureCount()

    private fun fromDate(): String = clock.today().minusDays(13).toString()

    suspend fun weekly(): WeeklyStats {
        val since = clock.startOfWeek()
        val month = clock.now() - 30 * 86_400_000L
        val logs = db.reviewLogDao()
        val practice = db.practiceDao()
        val activeSucc = Skill.ACTIVE.sumOf { practice.successesSince(since, it.name) }
        val activeAtt = Skill.ACTIVE.sumOf { practice.attemptsSince(since, it.name) }
        val weak = active.weakWords(100)
        val improved = weak.count { item -> practice.forCard(item.card.card.id).any { it.at >= since && it.score >= 0.7 } }
        val mature = logs.matureReviewsSince(month)
        return WeeklyStats(
            newWords = logs.newLearnedSince(since),
            reviews = logs.countSince(since),
            distinctReviewed = logs.distinctReviewedSince(since),
            activeSuccesses = activeSucc,
            activeAttempts = activeAtt,
            speakingMinutes = db.speakingDao().speakingMsSince(since) / 60000.0,
            pronunciationAttempts = db.pronunciationDao().countSince(since),
            lockScreenAnswers = practice.countBySourceSince(since, "LOCKSCREEN"),
            weakNow = weak.size,
            weakImproved = improved,
            retention30d = if (mature >= 10) logs.matureSuccessSince(month).toDouble() / mature else null,
            bestDayXp = db.gamificationDao().bestDayXp() ?: 0,
            bestBattle = db.competitionDao().bestScore(),
        )
    }

    /** Active vs passive vocabulary now and for each of the last [days] days. */
    suspend fun activeUsage(days: Int = 30): ActiveUsage {
        val activeFirst = db.practiceDao().activeFirstDays().associate { it.cardId to it.localDate }
        val knownFirst = HashMap<Long, String>()
        db.reviewLogDao().firstReviewDays().forEach { knownFirst[it.cardId] = it.localDate }
        // Cards studied without a review log (e.g. imported state) count as known from today on.
        val today = clock.today().toString()
        db.cardDao().allStudied().forEach { knownFirst.putIfAbsent(it.card.id, today) }
        activeFirst.forEach { (id, d) -> knownFirst.merge(id, d) { a, b -> minOf(a, b) } }
        val dates = (days - 1 downTo 0).map { clock.today().minusDays(it.toLong()).toString() }
        val series = dates.map { d ->
            val known = knownFirst.values.count { it <= d }
            val active = activeFirst.values.count { it <= d }
            VocabularyPoint(d, active, (known - active).coerceAtLeast(0))
        }
        val weekStart = clock.localDate(clock.startOfWeek())
        return ActiveUsage(activeFirst.size, (knownFirst.size - activeFirst.size).coerceAtLeast(0), series, activeFirst.values.count { it >= weekStart })
    }

    /** Studied cards with the lowest current retrievability (highest forgetting risk). */
    suspend fun forgettingRisk(limit: Int = 10): List<RiskItem> =
        db.cardDao().allStudied().mapNotNull { c -> study.retrievability(c.card)?.let { RiskItem(c, it) } }
            .sortedBy { it.retrievability }.take(limit)
}
