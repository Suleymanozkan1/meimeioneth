package com.lexiflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lexiflow.core.autopilot.Phase
import com.lexiflow.core.autopilot.StepKind
import com.lexiflow.core.exercise.QuickQuestionType
import com.lexiflow.core.fsrs.Rating
import com.lexiflow.data.repo.SessionStep
import com.lexiflow.domain.GradeResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutopilotFlowTest {
    private fun wrong(expected: String) = GradeResult(0.0, "WRONG", false, "Doğru cevap: $expected", expected)
    private fun right(expected: String) = GradeResult(1.0, "CORRECT", true, "Doğru!", expected)

    @Test fun planFromDatabaseGradesImplicitlyAndFeedsLockScreen() = runBlocking {
        val clock = FakeClock()
        val c = testContainer(clock)
        c.contentLoader.ensureSeeded()
        c.study.reviewQueue().take(15).forEach { c.study.answer(it.card.id, Rating.GOOD) }
        clock.advanceDays(30)

        val estimate = c.autopilot.estimate()
        assertTrue(estimate.calculatedMinutes in 5..90)
        assertTrue(estimate.reviewWords > 0)
        assertFalse(estimate.customBudget)

        val session = c.autopilot.startOrResume()
        assertEquals(Phase.WARMUP, session.steps.first().phase)
        assertEquals(session, c.autopilot.startOrResume()) // resumes, does not re-plan
        val step = session.current!!
        val exercises = c.autopilot.exercises(step, silent = false)
        val ex = exercises.first { it.cardId != null }
        val cardId = ex.cardId!!
        val logsBefore = c.db.reviewLogDao().forCard(cardId).size

        // Wrong answer: Again in FSRS (card is due), weak word, first lock-screen question.
        assertEquals(Rating.AGAIN, c.autopilot.recordAnswer(step.kind, ex, wrong(ex.expected), hintsUsed = 0, durationMs = 4_000))
        val logs = c.db.reviewLogDao().forCard(cardId)
        assertEquals(logsBefore + 1, logs.size)
        assertEquals(Rating.AGAIN.value, logs.last().rating)
        assertEquals("AUTOPILOT", logs.last().source)
        assertTrue(c.active.weakWords(50).any { it.card.card.id == cardId })
        // A second answer for the same card in the same session does not review it again.
        c.autopilot.recordAnswer(step.kind, ex, right(ex.expected), hintsUsed = 0, durationMs = 2_000)
        assertEquals(logsBefore + 1, c.db.reviewLogDao().forCard(cardId).size)
        assertEquals(2, c.autopilot.todaySession()!!.answered)

        val q = c.lockScreen.createQuestion()!!
        assertEquals(cardId, q.card!!.card.id)
    }

    @Test fun hintOrSlowAnswerIsHardFastIsEasy() = runBlocking {
        val c = testContainer()
        c.contentLoader.ensureSeeded()
        val session = c.autopilot.startOrResume()
        val step = session.steps.first { it.kind == StepKind.LISTENING || it.kind == StepKind.CONTEXT_CHALLENGE || it.kind == StepKind.CLOZE }
        val exs = c.autopilot.exercises(step, silent = false).filter { it.cardId != null }
        assertEquals(Rating.HARD, c.autopilot.recordAnswer(step.kind, exs[0], right(exs[0].expected), hintsUsed = 1, durationMs = 5_000))
        assertEquals(Rating.EASY, c.autopilot.recordAnswer(step.kind, exs[1], right(exs[1].expected), hintsUsed = 0, durationMs = 3_000))
        assertEquals(Rating.HARD, c.autopilot.recordAnswer(step.kind, exs[2], right(exs[2].expected), hintsUsed = 0, durationMs = 60_000))
    }

    @Test fun missedGrammarComesBackOnTheLockScreen() = runBlocking {
        val c = testContainer()
        c.contentLoader.ensureSeeded()
        c.autopilot.startOrResume()
        val step = SessionStep(Phase.ACTIVE, StepKind.GRAMMAR, listOf(-1L, -1L), 80)
        val ex = c.autopilot.exercises(step, silent = false).first()
        c.autopilot.recordAnswer(StepKind.GRAMMAR, ex, wrong(ex.expected), 0, 20_000)
        val q = c.lockScreen.createQuestion()!!
        assertEquals(QuickQuestionType.GRAMMAR.name, q.entity.type)
        assertNotNull(q.grammar)
        assertTrue(ex.expected in q.options && ex.prompt in q.options)
        val r = c.lockScreen.answerChoice(q.entity.id, q.entity.correctIndex!!, "APP")!!
        assertTrue(r.correct)
        assertTrue(r.bodyTr.isNotBlank())
        // Reloading (notification tap) still works without a card.
        assertNotNull(c.lockScreen.load(q.entity.id))
    }

    @Test fun budgetAndSilentSettingsShapeTheSession() = runBlocking {
        val c = testContainer()
        c.contentLoader.ensureSeeded()
        c.settings.update { it.copy(autopilotMinutes = 5, autopilotSilent = true) }
        val e = c.autopilot.estimate()
        assertTrue(e.customBudget)
        assertEquals(5, e.budgetMinutes)
        val s = c.autopilot.startOrResume()
        assertTrue(s.silent)
        assertTrue(s.steps.none { it.kind.usesVoice })
        assertTrue(s.steps.sumOf { it.seconds } <= 5 * 60)
        // Finishing every step finishes the session; a new start plans again.
        var cur = s
        while (!cur.finished) cur = c.autopilot.completeStep(skipped = true)!!
        assertNotNull(cur.finishedAt)
        assertFalse(c.autopilot.startOrResume().finished)
    }
}
