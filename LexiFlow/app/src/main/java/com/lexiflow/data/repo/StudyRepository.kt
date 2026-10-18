package com.lexiflow.data.repo

import androidx.room.withTransaction
import com.lexiflow.core.fsrs.CardState
import com.lexiflow.core.fsrs.Fsrs
import com.lexiflow.core.fsrs.FsrsScheduler
import com.lexiflow.core.fsrs.Rating
import com.lexiflow.core.fsrs.ReviewOutcome
import com.lexiflow.core.fsrs.SchedulerConfig
import com.lexiflow.core.fsrs.SchedulingCard
import com.lexiflow.core.gamification.XpReason
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.db.CardDetail
import com.lexiflow.data.db.CardEntity
import com.lexiflow.data.db.ReviewLogEntity
import com.lexiflow.data.settings.SettingsRepository
import com.lexiflow.util.Clock

fun CardEntity.toScheduling() = SchedulingCard(
    state = CardState.of(state), step = step, stability = stability, difficulty = difficulty, dueAt = dueAt,
    lastReviewAt = lastReviewAt, reps = reps, lapses = lapses, scheduledDays = scheduledDays,
)

fun CardEntity.withScheduling(s: SchedulingCard) = copy(
    state = s.state.value, step = s.step, stability = s.stability, difficulty = s.difficulty, dueAt = s.dueAt,
    lastReviewAt = s.lastReviewAt, reps = s.reps, lapses = s.lapses, scheduledDays = s.scheduledDays,
)

/** FSRS review flow: queue building, answering, interval previews and review logs. */
class StudyRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val gamification: GamificationRepository,
    private val clock: Clock,
) {
    private val cards = db.cardDao()
    private val logs = db.reviewLogDao()

    suspend fun scheduler(): FsrsScheduler {
        val s = settings.current()
        return FsrsScheduler(Fsrs(), SchedulerConfig(desiredRetention = s.desiredRetention))
    }

    /** Due reviews first (oldest due first), then today's remaining new cards. */
    suspend fun reviewQueue(deckId: Long? = null, includeNew: Boolean = true): List<CardDetail> {
        val now = clock.now()
        val due = cards.dueReviews(now, 500, deckId)
        if (!includeNew) return due
        val newToday = logs.newLearnedSince(clock.startOfToday())
        val remaining = (settings.current().newCardsPerDay - newToday).coerceAtLeast(0)
        val fresh = if (remaining > 0) cards.newCards(remaining, deckId) else emptyList()
        return due + fresh
    }

    suspend fun preview(card: CardEntity): Map<Rating, ReviewOutcome> = scheduler().preview(card.toScheduling(), clock.now())

    /**
     * Applies an FSRS review and writes the review log in one transaction.
     * [source] records where the answer came from (APP, NOTIFICATION, WIDGET) so lock-screen answers share the same history.
     */
    suspend fun answer(cardId: Long, rating: Rating, durationMs: Long = 0, source: String = "APP", mode: String = "FSRS_REVIEW"): ReviewOutcome? {
        val now = clock.now()
        val sched = scheduler()
        val result = db.withTransaction {
            val card = cards.get(cardId) ?: return@withTransaction null
            val outcome = sched.review(card.toScheduling(), rating, now, fuzzSeed = card.id)
            cards.update(card.withScheduling(outcome.card))
            logs.insert(
                ReviewLogEntity(
                    cardId = card.id,
                    reviewedAt = now,
                    localDate = clock.localDate(now),
                    rating = rating.value,
                    stateBefore = card.state,
                    elapsedDays = outcome.elapsedDays,
                    scheduledDays = outcome.scheduledDays,
                    intervalMillis = outcome.intervalMillis,
                    stabilityAfter = outcome.card.stability,
                    difficultyAfter = outcome.card.difficulty,
                    retrievabilityBefore = outcome.retrievability,
                    durationMs = durationMs,
                    source = source,
                    mode = mode,
                ),
            )
            card to outcome
        } ?: return null
        val (before, outcome) = result
        val overdue = before.state == CardState.REVIEW.value && now - before.dueAt > FsrsScheduler.DAY_MS
        val reason = when {
            before.state == CardState.NEW.value -> XpReason.NEW_CARD
            overdue -> XpReason.OVERDUE_REVIEW
            else -> XpReason.REVIEW
        }
        gamification.award(reason, cardId, ActivityDelta(reviews = 1, newCards = if (before.state == 0) 1 else 0))
        return outcome
    }

    /** True when the card is due for FSRS right now (used by lock-screen answers to avoid early reviews). */
    suspend fun isDue(cardId: Long): Boolean {
        val c = cards.get(cardId) ?: return false
        return c.state != CardState.NEW.value && c.dueAt <= clock.now() && !c.suspended
    }

    suspend fun retrievability(card: CardEntity): Double? = scheduler().currentRetrievability(card.toScheduling(), clock.now())

    suspend fun setSuspended(cardId: Long, suspended: Boolean) {
        cards.get(cardId)?.let { cards.update(it.copy(suspended = suspended)) }
    }

    /** Resets a card to NEW without deleting its history (history stays for statistics). */
    suspend fun resetCard(cardId: Long) {
        cards.get(cardId)?.let {
            cards.update(it.copy(state = 0, step = null, stability = 0.0, difficulty = 0.0, dueAt = clock.now(), lastReviewAt = null, reps = 0, lapses = 0, scheduledDays = 0))
        }
    }

    companion object {
        fun formatInterval(millis: Long): String = when {
            millis < 60 * 60_000L -> "${(millis / 60_000L).coerceAtLeast(1)} dk"
            millis < 86_400_000L -> "${millis / 3_600_000L} sa"
            millis < 30 * 86_400_000L -> "${millis / 86_400_000L} g"
            millis < 365 * 86_400_000L -> String.format(java.util.Locale.US, "%.1f ay", millis / (30.0 * 86_400_000L))
            else -> String.format(java.util.Locale.US, "%.1f yıl", millis / (365.0 * 86_400_000L))
        }
    }
}
