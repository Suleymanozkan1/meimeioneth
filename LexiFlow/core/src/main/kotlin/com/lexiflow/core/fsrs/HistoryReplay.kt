package com.lexiflow.core.fsrs

/** One historical answer, e.g. a row of Anki's `revlog` table. */
data class HistoricalReview(val reviewedAt: Long, val rating: Rating)

/**
 * Rebuilds a card's FSRS state from its real review history (e.g. imported from Anki's revlog).
 * No history is invented: an empty list yields a NEW card, and only the reviews that exist are replayed.
 * Fuzz is disabled during replay so the result is deterministic.
 */
object HistoryReplay {
    fun replay(history: List<HistoricalReview>, scheduler: FsrsScheduler): SchedulingCard {
        val replayScheduler = FsrsScheduler(scheduler.fsrs, scheduler.config.copy(enableFuzz = false))
        var card = SchedulingCard()
        history.sortedBy { it.reviewedAt }.forEach { review ->
            card = replayScheduler.review(card, review.rating, review.reviewedAt).card
        }
        return card
    }
}
