package com.lexiflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lexiflow.core.active.Skill
import com.lexiflow.core.fsrs.CardState
import com.lexiflow.core.fsrs.Rating
import com.lexiflow.data.repo.StudyMode
import com.lexiflow.domain.ExerciseGrader
import com.lexiflow.domain.ExerciseType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end data flow: seed -> review -> log -> next due -> statistics. */
@RunWith(AndroidJUnit4::class)
class StudyFlowTest {

    @Test fun builtinContentHasTenExamplesPerWord() = runBlocking {
        val c = testContainer()
        c.contentLoader.ensureSeeded()
        val words = c.db.wordDao().count()
        assertEquals(40, words)
        val decks = c.words.observeDecks().first()
        val wordIds = c.db.wordDao().observeInDeck(decks.first().deck.id).first().map { it.id }
        wordIds.forEach { id -> assertTrue("word $id", c.db.wordDao().exampleCount(id) >= 10) }
        assertTrue(c.db.contentDao().builtinCount() >= 20)
        c.contentLoader.ensureSeeded() // idempotent
        assertEquals(40, c.db.wordDao().count())
    }

    @Test fun reviewWritesLogSchedulesAndAwardsXp() = runBlocking {
        val clock = FakeClock()
        val c = testContainer(clock)
        c.contentLoader.ensureSeeded()
        val queue = c.study.reviewQueue()
        assertEquals(10, queue.size) // default new cards per day
        val card = queue.first().card
        val preview = c.study.preview(card)
        assertTrue(preview.getValue(Rating.AGAIN).intervalMillis < preview.getValue(Rating.EASY).intervalMillis)
        c.study.answer(card.id, Rating.GOOD)
        var updated = c.db.cardDao().get(card.id)!!
        assertEquals(CardState.LEARNING.value, updated.state)
        clock.advanceMinutes(11)
        c.study.answer(card.id, Rating.GOOD)
        updated = c.db.cardDao().get(card.id)!!
        assertEquals(CardState.REVIEW.value, updated.state)
        assertTrue(updated.dueAt > clock.now())
        val logs = c.db.reviewLogDao().forCard(card.id)
        assertEquals(2, logs.size)
        assertEquals(listOf(0, 1), logs.map { it.stateBefore })
        assertTrue(c.db.gamificationDao().totalXp() > 0)
        // Queue after: the card is not due any more and new-card budget is reduced.
        assertFalse(c.study.reviewQueue().any { it.card.id == card.id })
        assertEquals(9, c.study.reviewQueue().size)
        clock.advanceDays(30)
        assertTrue(c.study.reviewQueue().any { it.card.id == card.id })
        assertTrue(c.study.retrievability(c.db.cardDao().get(card.id)!!)!! < 0.9)
    }

    @Test fun cantUseFlagFeedsActiveProductionQueue() = runBlocking {
        val clock = FakeClock()
        val c = testContainer(clock)
        c.contentLoader.ensureSeeded()
        val card = c.study.reviewQueue().first().card
        c.study.answer(card.id, Rating.EASY)
        c.active.flagCantUse(card.id)
        val states = c.active.states(card.id)
        assertTrue(states.getValue(Skill.PRODUCTION).flaggedCantUse)
        val q = c.active.productionQueue(5)
        assertEquals(card.id, q.first().card.card.id)
        val session = c.sessions.build(StudyMode.ACTIVE_PATH)
        assertEquals(ExerciseType.TR_TO_EN, session.exercises.first().type)
        // FSRS state is untouched by the active-use flag.
        assertEquals(CardState.REVIEW.value, c.db.cardDao().get(card.id)!!.state)
    }

    @Test fun everyDrillModeBuildsRealExercises() = runBlocking {
        val clock = FakeClock()
        val c = testContainer(clock)
        c.contentLoader.ensureSeeded()
        c.study.reviewQueue().forEach { c.study.answer(it.card.id, Rating.EASY) }
        c.active.flagCantUse(c.db.cardDao().allStudied().first().card.id)
        clock.advanceDays(2)
        StudyMode.entries.forEach { mode ->
            val s = c.sessions.build(mode)
            assertTrue("$mode empty: ${s.emptyReasonTr}", s.exercises.isNotEmpty())
        }
        val exam = c.sessions.build(StudyMode.EXAM)
        assertNotNull(exam.timeLimitSec)
        assertTrue(exam.exercises.all { it.hintLevel == 0 })
    }

    @Test fun productionAnswerIsEvaluatedAndRecorded() = runBlocking {
        val c = testContainer()
        c.contentLoader.ensureSeeded()
        val s = c.sessions.build(StudyMode.WORD_PRODUCTION)
        val ex = s.exercises.first()
        val good = ExerciseGrader.gradeFreeText(ex, "I think my best friend will ${ex.lemma} it because it is important to her.")
        assertTrue(good.evaluation!!.targetUsage.first().used)
        val missing = ExerciseGrader.gradeFreeText(ex, "This sentence does not contain the target at all.")
        assertFalse(missing.correct)
        c.active.record(ex.cardId!!, Skill.PRODUCTION, good.score, 0, "TEST", good.verdict)
        assertEquals(1, c.active.state(ex.cardId!!, Skill.PRODUCTION).attempts)
    }

    @Test fun dailyChallengeIsStablePerDayAndCompletesOnce() = runBlocking {
        val c = testContainer()
        c.contentLoader.ensureSeeded()
        val a = c.sessions.dailyChallengeCards().map { it.card.id }
        val b = c.sessions.dailyChallengeCards().map { it.card.id }
        assertEquals(a, b)
        val before = c.db.gamificationDao().totalXp()
        c.sessions.completeDailyChallenge(80)
        c.sessions.completeDailyChallenge(80)
        assertEquals(before + 30, c.db.gamificationDao().totalXp())
    }

    @Test fun streakCountsGoalDays() = runBlocking {
        val clock = FakeClock()
        val c = testContainer(clock)
        c.settings.update { it.copy(dailyGoalXp = 10) }
        repeat(3) { day ->
            repeat(5) { c.gamification.award(com.lexiflow.core.gamification.XpReason.REVIEW) }
            if (day < 2) clock.advanceDays(1)
        }
        val p = c.gamification.observeProgress().first()
        assertEquals(3, p.streak.current)
        assertTrue(p.streak.activeToday)
    }
}
