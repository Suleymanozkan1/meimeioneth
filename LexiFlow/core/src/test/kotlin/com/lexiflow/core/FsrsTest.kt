package com.lexiflow.core

import com.lexiflow.core.fsrs.CardState
import com.lexiflow.core.fsrs.Fsrs
import com.lexiflow.core.fsrs.FsrsScheduler
import com.lexiflow.core.fsrs.HistoricalReview
import com.lexiflow.core.fsrs.HistoryReplay
import com.lexiflow.core.fsrs.MemoryState
import com.lexiflow.core.fsrs.Rating
import com.lexiflow.core.fsrs.SchedulerConfig
import com.lexiflow.core.fsrs.SchedulingCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reference values were computed independently from the FSRS-6 formulas in fsrs-rs `model_v6.rs`
 * (see docs/TEST_REPORT.md for the script).
 */
class FsrsTest {
    private val fsrs = Fsrs()
    private val eps = 1e-6

    @Test fun retrievabilityIsNinetyPercentAfterStabilityDays() {
        assertEquals(0.9, fsrs.retrievability(10.0, 10.0), eps)
        assertEquals(1.0, fsrs.retrievability(0.0, 10.0), eps)
    }

    @Test fun nextIntervalEqualsStabilityAtNinetyPercent() {
        assertEquals(121.01552, fsrs.nextIntervalRaw(121.01552, 0.9), 1e-4) // fsrs-rs test next_interval_remains_fractional
        assertEquals(7.64826124694176, fsrs.nextIntervalRaw(2.3065, 0.8), 1e-6)
    }

    @Test fun initialStateMatchesReference() {
        assertEquals(0.212, fsrs.initStability(Rating.AGAIN), eps)
        assertEquals(8.2956, fsrs.initStability(Rating.EASY), eps)
        assertEquals(6.4133, fsrs.initDifficulty(Rating.AGAIN), eps)
        assertEquals(5.112170705601055, fsrs.initDifficulty(Rating.HARD), eps)
        assertEquals(2.118103970459015, fsrs.initDifficulty(Rating.GOOD), eps)
        assertEquals(1.0, fsrs.initDifficulty(Rating.EASY), eps)
    }

    @Test fun longTermUpdatesMatchReference() {
        val s1 = MemoryState(2.3065, 2.118103970459015)
        val afterGood = fsrs.nextMemoryState(s1, 2.0, Rating.GOOD)
        assertEquals(10.964332335820703, afterGood.stability, 1e-6)
        assertEquals(2.1112142357853942, afterGood.difficulty, 1e-6)
        val afterFail = fsrs.nextMemoryState(afterGood, 10.0, Rating.AGAIN)
        assertEquals(1.522138154964894, afterFail.stability, 1e-6)
        assertEquals(7.392238132342694, afterFail.difficulty, 1e-6)
        assertEquals(22.492508117499327, fsrs.nextMemoryState(afterGood, 5.0, Rating.HARD).stability, 1e-6)
        assertEquals(46.86576303336958, fsrs.nextMemoryState(afterGood, 5.0, Rating.EASY).stability, 1e-6)
    }

    @Test fun shortTermUpdatesMatchReference() {
        val s = MemoryState(2.3065, 2.118103970459015)
        assertEquals(0.7750839828558983, fsrs.nextMemoryState(s, 0.0, Rating.AGAIN).stability, 1e-6)
        assertEquals(2.3065, fsrs.nextMemoryState(s, 0.0, Rating.GOOD).stability, 1e-6)
    }

    @Test fun newCardGoesThroughLearningSteps() {
        val sched = FsrsScheduler()
        val now = 1_700_000_000_000L
        val first = sched.review(SchedulingCard(), Rating.GOOD, now)
        assertEquals(CardState.LEARNING, first.card.state)
        assertEquals(1, first.card.step)
        assertEquals(10 * FsrsScheduler.MINUTE_MS, first.intervalMillis)
        val second = sched.review(first.card, Rating.GOOD, now + first.intervalMillis)
        assertEquals(CardState.REVIEW, second.card.state)
        assertTrue(second.scheduledDays >= 1)
    }

    @Test fun againResetsLearningAndLapsesInReview() {
        val sched = FsrsScheduler(config = SchedulerConfig(enableFuzz = false))
        val now = 1_700_000_000_000L
        val easy = sched.review(SchedulingCard(), Rating.EASY, now)
        assertEquals(CardState.REVIEW, easy.card.state)
        assertEquals(8, easy.scheduledDays) // round(8.2956)
        val lapse = sched.review(easy.card, Rating.AGAIN, now + 8 * FsrsScheduler.DAY_MS)
        assertEquals(CardState.RELEARNING, lapse.card.state)
        assertEquals(1, lapse.card.lapses)
        assertEquals(10 * FsrsScheduler.MINUTE_MS, lapse.intervalMillis)
    }

    @Test fun answerButtonsAreOrdered() {
        val sched = FsrsScheduler(config = SchedulerConfig(enableFuzz = false))
        val now = 1_700_000_000_000L
        val card = SchedulingCard(CardState.REVIEW, null, 10.0, 5.0, now, now - 10 * FsrsScheduler.DAY_MS, 5, 0, 10)
        val p = sched.preview(card, now)
        assertTrue(p.getValue(Rating.HARD).scheduledDays < p.getValue(Rating.GOOD).scheduledDays)
        assertTrue(p.getValue(Rating.GOOD).scheduledDays < p.getValue(Rating.EASY).scheduledDays)
    }

    @Test fun fuzzStaysWithinAnkiRange() {
        val sched = FsrsScheduler()
        repeat(50) { seed ->
            val d = sched.intervalDays(30.0, seed.toLong() + 1, 3)
            assertTrue("$d", d in 27..33)
        }
    }

    @Test fun replayWithoutHistoryIsNew() {
        val c = HistoryReplay.replay(emptyList(), FsrsScheduler())
        assertEquals(CardState.NEW, c.state)
        assertNull(c.memory)
    }

    @Test fun replayBuildsReviewState() {
        val t0 = 1_600_000_000_000L
        val c = HistoryReplay.replay(listOf(
            HistoricalReview(t0, Rating.GOOD), HistoricalReview(t0 + 600_000, Rating.GOOD),
            HistoricalReview(t0 + 3 * FsrsScheduler.DAY_MS, Rating.GOOD),
        ), FsrsScheduler())
        assertEquals(CardState.REVIEW, c.state)
        assertEquals(3, c.reps)
        assertTrue(c.stability > 2.3)
    }
}
