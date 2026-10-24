package com.lexiflow.core

import com.lexiflow.core.fsrs.CardState
import com.lexiflow.core.fsrs.Fsrs
import com.lexiflow.core.fsrs.FsrsScheduler
import com.lexiflow.core.fsrs.MemoryState
import com.lexiflow.core.fsrs.Rating
import com.lexiflow.core.fsrs.SchedulerConfig
import com.lexiflow.core.fsrs.SchedulingCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FSRS calculation only: difficulty, stability, retrievability and the next due date after Again / Hard / Good /
 * Easy. Expected numbers were computed independently from the FSRS-6 formulas with the default weights.
 */
class FsrsCalculationTest {
    private val fsrs = Fsrs()
    private val day = FsrsScheduler.DAY_MS
    private val t0 = 1_700_000_000_000L
    private val eps = 1e-6

    // ---- Difficulty ----

    @Test fun firstReviewDifficultyPerRating() {
        assertEquals(6.4133, fsrs.initDifficulty(Rating.AGAIN), eps)
        assertEquals(5.1121707, fsrs.initDifficulty(Rating.HARD), eps)
        assertEquals(2.1181040, fsrs.initDifficulty(Rating.GOOD), eps)
        // The raw value for Easy is below 1, so it is clamped to the minimum.
        assertEquals(Fsrs.D_MIN, fsrs.initDifficulty(Rating.EASY), eps)
    }

    @Test fun difficultyAfterEachRatingFromFive() {
        assertEquals(8.3417624, fsrs.nextDifficulty(5.0, Rating.AGAIN), eps)
        assertEquals(6.6659954, fsrs.nextDifficulty(5.0, Rating.HARD), eps)
        assertEquals(4.9902284, fsrs.nextDifficulty(5.0, Rating.GOOD), eps)
        assertEquals(3.3144614, fsrs.nextDifficulty(5.0, Rating.EASY), eps)
    }

    @Test fun againMakesHarderEasyMakesEasierAndGoodBarelyMoves() {
        val d = 5.0
        val again = fsrs.nextDifficulty(d, Rating.AGAIN)
        val hard = fsrs.nextDifficulty(d, Rating.HARD)
        val good = fsrs.nextDifficulty(d, Rating.GOOD)
        val easy = fsrs.nextDifficulty(d, Rating.EASY)
        assertTrue(again > hard && hard > good && good > easy)
        assertTrue(again > d && easy < d)
        // Good only applies the small mean reversion towards the "easy" difficulty.
        assertTrue(kotlin.math.abs(good - d) < 0.05)
    }

    @Test fun difficultyStaysBetweenOneAndTen() {
        assertEquals(9.9852284, fsrs.nextDifficulty(10.0, Rating.AGAIN), eps)
        assertEquals(Fsrs.D_MIN, fsrs.nextDifficulty(1.0, Rating.EASY), eps)
        var d = 5.0
        repeat(50) { d = fsrs.nextDifficulty(d, Rating.AGAIN) }
        assertTrue(d <= Fsrs.D_MAX)
        repeat(50) { d = fsrs.nextDifficulty(d, Rating.EASY) }
        assertTrue(d >= Fsrs.D_MIN)
    }

    // ---- Stability ----

    @Test fun firstReviewStabilityIsTheRatingWeight() {
        assertEquals(0.212, fsrs.initStability(Rating.AGAIN), eps)
        assertEquals(1.2931, fsrs.initStability(Rating.HARD), eps)
        assertEquals(2.3065, fsrs.initStability(Rating.GOOD), eps)
        assertEquals(8.2956, fsrs.initStability(Rating.EASY), eps)
        val first = fsrs.nextMemoryState(null, 0.0, Rating.GOOD)
        assertEquals(MemoryState(2.3065, fsrs.initDifficulty(Rating.GOOD)), first)
    }

    @Test fun stabilityAfterReviewOnTimeForEachRating() {
        // S = 10, D = 5, reviewed after 10 days (R = 0.9).
        val state = MemoryState(10.0, 5.0)
        val again = fsrs.nextMemoryState(state, 10.0, Rating.AGAIN)
        val hard = fsrs.nextMemoryState(state, 10.0, Rating.HARD)
        val good = fsrs.nextMemoryState(state, 10.0, Rating.GOOD)
        val easy = fsrs.nextMemoryState(state, 10.0, Rating.EASY)
        assertEquals(1.3919870, again.stability, 1e-5)
        assertEquals(23.246875, hard.stability, 1e-5)
        assertEquals(32.026729, good.stability, 1e-5)
        assertEquals(51.253862, easy.stability, 1e-5)
        assertEquals(8.3417624, again.difficulty, eps)
        assertEquals(4.9902284, good.difficulty, eps)
    }

    @Test fun successRaisesStabilityAndFailureLowersIt() {
        val s = 10.0
        val r = fsrs.retrievability(10.0, s)
        assertTrue(fsrs.stabilityAfterFailure(s, r, 5.0) < s)
        listOf(Rating.HARD, Rating.GOOD, Rating.EASY).forEach { assertTrue("$it", fsrs.stabilityAfterSuccess(s, r, 5.0, it) > s) }
        // A later (harder) successful recall raises stability more than an early one.
        val early = fsrs.stabilityAfterSuccess(s, fsrs.retrievability(3.0, s), 5.0, Rating.GOOD)
        val late = fsrs.stabilityAfterSuccess(s, fsrs.retrievability(20.0, s), 5.0, Rating.GOOD)
        assertTrue(late > early)
        // A harder card gains less stability than an easier one.
        assertTrue(fsrs.stabilityAfterSuccess(s, r, 9.0, Rating.GOOD) < fsrs.stabilityAfterSuccess(s, r, 2.0, Rating.GOOD))
    }

    @Test fun sameDayReviewNeverLowersStabilityExceptAgain() {
        val state = MemoryState(5.0, 5.0)
        listOf(Rating.HARD, Rating.GOOD, Rating.EASY).forEach { assertTrue("$it", fsrs.nextMemoryState(state, 0.0, it).stability >= 5.0) }
        assertTrue(fsrs.nextMemoryState(state, 0.0, Rating.AGAIN).stability < 5.0)
    }

    // ---- Retrievability ----

    @Test fun retrievabilityCurve() {
        assertEquals(0.9, fsrs.retrievability(10.0, 10.0), eps)
        assertEquals(0.9403443, fsrs.retrievability(5.0, 10.0), eps)
        assertEquals(0.8458846, fsrs.retrievability(20.0, 10.0), eps)
        assertEquals(1.0, fsrs.retrievability(0.0, 10.0), eps)
    }

    // ---- Next review date ----

    @Test fun intervalDependsOnDesiredRetention() {
        assertEquals(10.0, fsrs.nextIntervalRaw(10.0, 0.9), 1e-6)
        assertEquals(33.159598, fsrs.nextIntervalRaw(10.0, 0.8), 1e-5)
        assertEquals(4.0255869, fsrs.nextIntervalRaw(10.0, 0.95), 1e-5)
    }

    @Test fun nextDueDateAfterEachRatingOfAReviewCard() {
        val scheduler = FsrsScheduler(config = SchedulerConfig(enableFuzz = false))
        val card = SchedulingCard(CardState.REVIEW, null, 10.0, 5.0, dueAt = t0 + 10 * day, lastReviewAt = t0, reps = 5, scheduledDays = 10)
        val now = t0 + 10 * day
        val out = scheduler.preview(card, now)
        // Again: back to relearning with the 10-minute step and one more lapse.
        val again = out.getValue(Rating.AGAIN)
        assertEquals(CardState.RELEARNING, again.card.state)
        assertEquals(now + 10 * FsrsScheduler.MINUTE_MS, again.card.dueAt)
        assertEquals(1, again.card.lapses)
        // Hard / Good / Easy: due after round(interval(S')) days at 90% retention (interval == stability at 0.9).
        assertEquals(23, out.getValue(Rating.HARD).scheduledDays)
        assertEquals(32, out.getValue(Rating.GOOD).scheduledDays)
        assertEquals(51, out.getValue(Rating.EASY).scheduledDays)
        listOf(Rating.HARD, Rating.GOOD, Rating.EASY).forEach { r ->
            val o = out.getValue(r)
            assertEquals(CardState.REVIEW, o.card.state)
            assertEquals(now + o.scheduledDays * day, o.card.dueAt)
            assertEquals(now, o.card.lastReviewAt)
            assertEquals(6, o.card.reps)
        }
        assertEquals(0.9, out.getValue(Rating.GOOD).retrievability!!, eps)
        assertEquals(10, out.getValue(Rating.GOOD).elapsedDays)
    }

    @Test fun newCardDueTimesForEachRating() {
        val scheduler = FsrsScheduler(config = SchedulerConfig(enableFuzz = false))
        val out = scheduler.preview(SchedulingCard(), t0)
        assertEquals(t0 + 1 * FsrsScheduler.MINUTE_MS, out.getValue(Rating.AGAIN).card.dueAt)
        // Hard on the first step waits halfway between the 1- and 10-minute steps.
        assertEquals(t0 + (5.5 * FsrsScheduler.MINUTE_MS).toLong(), out.getValue(Rating.HARD).card.dueAt)
        assertEquals(t0 + 10 * FsrsScheduler.MINUTE_MS, out.getValue(Rating.GOOD).card.dueAt)
        // Easy graduates at once: interval = round(8.2956) = 8 days.
        val easy = out.getValue(Rating.EASY)
        assertEquals(CardState.REVIEW, easy.card.state)
        assertEquals(8, easy.scheduledDays)
        assertEquals(t0 + 8 * day, easy.card.dueAt)
    }

    @Test fun higherRetentionGivesEarlierDueDate() {
        val card = SchedulingCard(CardState.REVIEW, null, 10.0, 5.0, dueAt = t0 + 10 * day, lastReviewAt = t0, reps = 5, scheduledDays = 10)
        val now = t0 + 10 * day
        fun due(retention: Double) = FsrsScheduler(config = SchedulerConfig(desiredRetention = retention, enableFuzz = false)).review(card, Rating.GOOD, now).card.dueAt
        assertTrue(due(0.95) < due(0.9))
        assertTrue(due(0.9) < due(0.8))
    }

    @Test fun lateReviewCountsElapsedDaysAndLowersRetrievability() {
        val scheduler = FsrsScheduler(config = SchedulerConfig(enableFuzz = false))
        val card = SchedulingCard(CardState.REVIEW, null, 10.0, 5.0, dueAt = t0 + 10 * day, lastReviewAt = t0, reps = 5, scheduledDays = 10)
        val late = scheduler.review(card, Rating.GOOD, t0 + 20 * day)
        assertEquals(20, late.elapsedDays)
        assertEquals(0.8458846, late.retrievability!!, eps)
        val onTime = scheduler.review(card, Rating.GOOD, t0 + 10 * day)
        assertTrue(late.card.stability > onTime.card.stability)
    }
}
