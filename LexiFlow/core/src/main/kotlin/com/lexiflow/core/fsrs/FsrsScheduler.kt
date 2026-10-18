package com.lexiflow.core.fsrs

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

/** Scheduling-relevant state of one card. Times are epoch milliseconds (UTC). */
data class SchedulingCard(
    val state: CardState = CardState.NEW,
    /** Index into learning/relearning steps; null when not in a step-based state. */
    val step: Int? = null,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val dueAt: Long = 0L,
    val lastReviewAt: Long? = null,
    val reps: Int = 0,
    val lapses: Int = 0,
    val scheduledDays: Int = 0,
) {
    val memory: MemoryState? get() = if (stability > 0.0) MemoryState(stability, difficulty) else null
}

data class ReviewOutcome(
    val card: SchedulingCard,
    val rating: Rating,
    val reviewedAt: Long,
    /** Whole days since previous review (0 for first review). */
    val elapsedDays: Int,
    /** Interval chosen by the scheduler in days (0 when the next step is intra-day). */
    val scheduledDays: Int,
    /** Interval in milliseconds until the next due time. */
    val intervalMillis: Long,
    val previousState: CardState,
    /** Retrievability at review time, null for new cards. */
    val retrievability: Double?,
)

data class SchedulerConfig(
    val desiredRetention: Double = 0.9,
    val learningStepsMinutes: List<Int> = listOf(1, 10),
    val relearningStepsMinutes: List<Int> = listOf(10),
    val maximumIntervalDays: Int = 36500,
    val enableFuzz: Boolean = true,
)

/**
 * Card-level scheduler built on [Fsrs]. State machine follows the open-spaced-repetition reference
 * schedulers (py-fsrs / Anki): New -> Learning (steps) -> Review <-> Relearning (steps).
 * Fuzz follows Anki's published fuzz ranges so cards reviewed together do not stay clumped.
 */
class FsrsScheduler(
    val fsrs: Fsrs = Fsrs(),
    val config: SchedulerConfig = SchedulerConfig(),
) {

    fun review(card: SchedulingCard, rating: Rating, now: Long, fuzzSeed: Long = 0L): ReviewOutcome {
        val elapsedDays = card.lastReviewAt?.let { daysBetween(it, now) } ?: 0
        val retrievability = card.memory?.let { fsrs.retrievability(elapsedDays.toDouble(), it.stability) }
        val memory = fsrs.nextMemoryState(card.memory, elapsedDays.toDouble(), rating)

        var state: CardState
        var step: Int?
        var intervalMillis: Long
        var scheduledDays = 0
        var lapses = card.lapses

        when (card.state) {
            CardState.NEW, CardState.LEARNING, CardState.RELEARNING -> {
                val relearning = card.state == CardState.RELEARNING
                val steps = if (relearning) config.relearningStepsMinutes else config.learningStepsMinutes
                val current = card.step ?: 0
                val graduate: Boolean
                when {
                    steps.isEmpty() -> { graduate = true; step = null }
                    current >= steps.size && rating != Rating.AGAIN -> { graduate = true; step = null }
                    else -> when (rating) {
                        Rating.AGAIN -> { graduate = false; step = 0 }
                        Rating.HARD -> { graduate = false; step = current }
                        Rating.GOOD -> if (current + 1 >= steps.size) {
                            graduate = true; step = null
                        } else {
                            graduate = false; step = current + 1
                        }
                        Rating.EASY -> { graduate = true; step = null }
                    }
                }
                if (graduate) {
                    state = CardState.REVIEW
                    scheduledDays = intervalDays(memory.stability, fuzzSeed, card.reps)
                    intervalMillis = scheduledDays * DAY_MS
                } else {
                    state = if (relearning) CardState.RELEARNING else CardState.LEARNING
                    val s = step!!
                    val minutes: Double = if (rating == Rating.HARD && s == 0) {
                        if (steps.size == 1) steps[0] * 1.5 else (steps[0] + steps[1]) / 2.0
                    } else {
                        steps[min(s, steps.lastIndex)].toDouble()
                    }
                    intervalMillis = (minutes * MINUTE_MS).roundToLong()
                }
            }

            CardState.REVIEW -> {
                if (rating == Rating.AGAIN) {
                    lapses += 1
                    if (config.relearningStepsMinutes.isEmpty()) {
                        state = CardState.REVIEW
                        step = null
                        scheduledDays = intervalDays(memory.stability, fuzzSeed, card.reps)
                        intervalMillis = scheduledDays * DAY_MS
                    } else {
                        state = CardState.RELEARNING
                        step = 0
                        intervalMillis = config.relearningStepsMinutes[0] * MINUTE_MS
                    }
                } else {
                    state = CardState.REVIEW
                    step = null
                    scheduledDays = intervalDays(memory.stability, fuzzSeed, card.reps)
                    intervalMillis = scheduledDays * DAY_MS
                }
            }
        }

        val next = SchedulingCard(
            state = state,
            step = step,
            stability = memory.stability,
            difficulty = memory.difficulty,
            dueAt = now + intervalMillis,
            lastReviewAt = now,
            reps = card.reps + 1,
            lapses = lapses,
            scheduledDays = scheduledDays,
        )
        return ReviewOutcome(next, rating, now, elapsedDays, scheduledDays, intervalMillis, card.state, retrievability)
    }

    /** Preview of all four answers, used for the interval labels on the answer buttons. */
    fun preview(card: SchedulingCard, now: Long): Map<Rating, ReviewOutcome> =
        Rating.entries.associateWith { review(card, it, now) }

    fun currentRetrievability(card: SchedulingCard, now: Long): Double? {
        val memory = card.memory ?: return null
        val last = card.lastReviewAt ?: return null
        val days = (now - last).toDouble() / DAY_MS
        return fsrs.retrievabilityContinuous(days, memory.stability)
    }

    internal fun intervalDays(stability: Double, fuzzSeed: Long, reps: Int): Int {
        val raw = fsrs.nextIntervalRaw(stability, config.desiredRetention)
        var ivl = max(1L, Math.round(raw)).toInt().coerceAtMost(config.maximumIntervalDays)
        if (config.enableFuzz && fuzzSeed != 0L) ivl = applyFuzz(ivl, Random(fuzzSeed * 31 + reps))
        return ivl.coerceIn(1, config.maximumIntervalDays)
    }

    private fun applyFuzz(interval: Int, random: Random): Int {
        if (interval < 2.5) return interval
        var delta = 1.0
        for ((start, end, factor) in FUZZ_RANGES) {
            delta += factor * max(min(interval.toDouble(), end) - start, 0.0)
        }
        val minIvl = max(2, Math.round(interval - delta).toInt())
        val maxIvl = Math.round(interval + delta).toInt().coerceAtMost(config.maximumIntervalDays)
        if (minIvl >= maxIvl) return min(minIvl, maxIvl)
        return random.nextInt(minIvl, maxIvl + 1)
    }

    companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 86_400_000L
        private val FUZZ_RANGES = listOf(
            Triple(2.5, 7.0, 0.15),
            Triple(7.0, 20.0, 0.1),
            Triple(20.0, Double.POSITIVE_INFINITY, 0.05),
        )

        fun daysBetween(from: Long, to: Long): Int = max(0L, Math.round((to - from).toDouble() / DAY_MS)).toInt()
    }
}
