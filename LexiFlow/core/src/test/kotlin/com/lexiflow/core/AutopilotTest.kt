package com.lexiflow.core

import com.lexiflow.core.autopilot.AutopilotPlanner
import com.lexiflow.core.autopilot.ImplicitGrader
import com.lexiflow.core.autopilot.Phase
import com.lexiflow.core.autopilot.PlanCard
import com.lexiflow.core.autopilot.PlannerInput
import com.lexiflow.core.autopilot.StepKind
import com.lexiflow.core.fsrs.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutopilotTest {
    private fun cards(from: Long, n: Int, stability: Double = 1.0) = (0 until n).map { PlanCard(from + it, stability) }

    private val typical = PlannerInput(
        due = cards(1, 30, 5.0) + cards(100, 10, 30.0),
        weak = cards(200, 8, 1.0),
        newWords = cards(300, 10, 0.0),
        activeQueue = cards(400, 6, 10.0),
        atRisk = cards(500, 4, 8.0),
    )

    @Test fun unitTimesFollowTheSpec() {
        assertEquals(10, StepKind.EN_TO_TR.unitSeconds); assertEquals(10, StepKind.TR_TO_EN.unitSeconds)
        listOf(StepKind.CLOZE, StepKind.CONTEXT_CHALLENGE, StepKind.LISTENING, StepKind.PRONUNCIATION).forEach { assertEquals(20, it.unitSeconds) }
        listOf(StepKind.SENTENCE_BUILDER, StepKind.TRANSLATION, StepKind.WORD_PRODUCTION, StepKind.GRAMMAR, StepKind.SHADOWING, StepKind.DICTATION)
            .forEach { assertEquals(40, it.unitSeconds) }
        listOf(StepKind.FORGETTING_STORY, StepKind.AI_STORY, StepKind.SCREEN_CONTEXT, StepKind.MUSIC_CONTEXT).forEach { assertEquals(90, it.unitSeconds) }
        assertEquals(150, StepKind.AI_SPEAKING.unitSeconds); assertEquals(150, StepKind.WRITTEN_SPEAKING.unitSeconds)
    }

    @Test fun calculatedTimeNeverExceedsNinetyMinutes() {
        val huge = PlannerInput(due = cards(1, 2_000, 5.0), weak = cards(5_000, 300), newWords = cards(9_000, 200, 0.0))
        val plan = AutopilotPlanner.plan(huge)
        assertEquals(90, plan.calculatedMinutes)
        assertEquals(90, plan.budgetMinutes)
        assertTrue("planned ${plan.plannedSeconds}s", plan.plannedSeconds <= 90 * 60)
    }

    @Test fun typicalDayUsesAllFiveStagesInOrderWithinBudget() {
        val plan = AutopilotPlanner.plan(typical)
        assertTrue(plan.calculatedMinutes in 5..90)
        assertEquals(Phase.entries.toList(), plan.steps.map { it.phase }.distinct())
        assertTrue(plan.plannedSeconds <= plan.budgetMinutes * 60)
        // Every due and weak word is warmed up when there is time for the whole day.
        val warm = plan.steps.filter { it.phase == Phase.WARMUP }.flatMap { it.cardIds }.toSet()
        assertTrue((typical.due + typical.weak).all { it.cardId in warm })
        // Young words are recognised (EN→TR), mature ones recalled (TR→EN).
        assertTrue(plan.steps.first { it.kind == StepKind.EN_TO_TR }.cardIds.containsAll(typical.weak.map { it.cardId }))
        assertTrue(plan.steps.first { it.kind == StepKind.TR_TO_EN }.cardIds.containsAll(cards(100, 10).map { it.cardId }))
        // New words: card, listening and pronunciation; then a story and the context questions.
        listOf(StepKind.WORD_INTRO, StepKind.LISTENING, StepKind.PRONUNCIATION, StepKind.FORGETTING_STORY, StepKind.CONTEXT_CHALLENGE, StepKind.CLOZE)
            .forEach { k -> assertTrue("missing $k", plan.steps.any { it.kind == k }) }
        assertEquals(10, plan.newWords)
        // Each stage takes roughly its share (±10 points) of what was planned.
        val total = plan.plannedSeconds.toDouble()
        assertTrue(plan.phaseSeconds(Phase.WARMUP) / total in 0.1..0.45)
        assertTrue(plan.phaseSeconds(Phase.ACTIVE) / total in 0.1..0.4)
    }

    @Test fun shorterBudgetKeepsReviewsCutsNewWordsAndUsesFastSteps() {
        val full = AutopilotPlanner.plan(typical)
        val short = AutopilotPlanner.plan(typical.copy(budgetMinutes = 15))
        assertTrue(full.calculatedMinutes > 15)
        assertTrue(short.constrained)
        assertEquals(15, short.budgetMinutes)
        assertTrue(short.plannedSeconds <= 15 * 60)
        // Reviews still dominate the start, new words shrink.
        assertTrue(short.reviewWords >= (typical.due + typical.weak).size * 0.8)
        assertTrue(short.newWords < full.newWords)
        // No long production steps or stories when time is short.
        val kinds = short.steps.map { it.kind }.toSet()
        assertFalse(StepKind.SENTENCE_BUILDER in kinds)
        assertFalse(StepKind.TRANSLATION in kinds)
        assertFalse(kinds.any { it.unitSeconds >= 90 })
    }

    @Test fun longerBudgetAddsWordbookActivePathStoriesAndSpeaking() {
        val input = typical.copy(budgetMinutes = 120, wordbookReview = cards(600, 20, 15.0), activeQueue = cards(400, 12, 10.0))
        val plan = AutopilotPlanner.plan(input)
        assertFalse(plan.constrained)
        assertEquals(120, plan.budgetMinutes)
        assertTrue(plan.plannedSeconds > AutopilotPlanner.plan(typical).plannedSeconds)
        assertTrue(plan.plannedSeconds <= 120 * 60)
        val ids = plan.steps.flatMap { it.cardIds }.toSet()
        assertTrue("wordbook used", input.wordbookReview.any { it.cardId in ids })
        assertTrue(plan.steps.any { it.kind == StepKind.WRITTEN_SPEAKING || it.kind == StepKind.AI_SPEAKING })
    }

    @Test fun budgetOverrideIsClampedToFiveToHundredTwentyMinutes() {
        assertEquals(5, AutopilotPlanner.plan(typical.copy(budgetMinutes = 1)).budgetMinutes)
        assertEquals(120, AutopilotPlanner.plan(typical.copy(budgetMinutes = 500)).budgetMinutes)
    }

    @Test fun silentModeNeverPlansSpeakingAloud() {
        val plan = AutopilotPlanner.plan(typical.copy(silent = true, aiAvailable = true, budgetMinutes = 120))
        val kinds = plan.steps.map { it.kind }
        assertTrue(kinds.none { it.usesVoice })
        assertTrue(StepKind.DICTATION in kinds)
        assertEquals(StepKind.WRITTEN_SPEAKING, StepKind.AI_SPEAKING.silentReplacement())
        assertEquals(StepKind.DICTATION, StepKind.PRONUNCIATION.silentReplacement())
        assertEquals(StepKind.WORD_PRODUCTION, StepKind.SHADOWING.silentReplacement())
    }

    @Test fun emptyDayStillGivesAShortSession() {
        val plan = AutopilotPlanner.plan(PlannerInput(due = emptyList(), weak = emptyList(), newWords = emptyList()))
        assertEquals(5, plan.calculatedMinutes)
        assertTrue(plan.plannedSeconds <= 5 * 60)
    }

    @Test fun maturityPicksPassiveOrActiveExercises() {
        assertEquals(StepKind.EN_TO_TR, AutopilotPlanner.kindForMaturity(0.5))
        assertEquals(StepKind.CLOZE, AutopilotPlanner.kindForMaturity(10.0))
        assertEquals(StepKind.SENTENCE_BUILDER, AutopilotPlanner.kindForMaturity(40.0))
        assertEquals(StepKind.WORD_PRODUCTION, AutopilotPlanner.kindForMaturity(40.0, silent = true))
    }

    @Test fun implicitGradingFromAnswerQuality() {
        assertEquals(Rating.AGAIN, ImplicitGrader.rate(correct = false, hintsUsed = 0, responseMs = 3_000, expectedMs = 10_000))
        assertEquals(Rating.HARD, ImplicitGrader.rate(true, hintsUsed = 1, responseMs = 3_000, expectedMs = 10_000))
        assertEquals(Rating.HARD, ImplicitGrader.rate(true, 0, responseMs = 25_000, expectedMs = 10_000))
        assertEquals(Rating.HARD, ImplicitGrader.rate(true, 0, 3_000, 10_000, firstAttempt = false))
        assertEquals(Rating.EASY, ImplicitGrader.rate(true, 0, responseMs = 4_000, expectedMs = 10_000))
        assertEquals(Rating.GOOD, ImplicitGrader.rate(true, 0, responseMs = 8_000, expectedMs = 10_000))
    }

    @Test fun clozeCoversTheCollocationParticle() {
        val particles = com.lexiflow.core.exercise.Collocations.particlesFor("rely", listOf("rely on someone", "rely heavily on"))
        assertEquals(listOf("on"), particles)
        val c = com.lexiflow.core.exercise.ClozeBuilder.buildWithPattern("You can always rely on your friends.", "rely", particles)!!
        assertEquals("rely on", c.answer)
        assertEquals("You can always ______ your friends.", c.prompt)
        // Without the particle after the word the normal blank is kept.
        assertEquals("rely", com.lexiflow.core.exercise.ClozeBuilder.buildWithPattern("I rely heavily on her.", "rely", particles)!!.answer)
        assertEquals(listOf("in"), com.lexiflow.core.exercise.Collocations.particlesFor("interested", listOf("interested in art")))
        assertTrue(com.lexiflow.core.exercise.Collocations.particlesFor("look after", listOf("look after a child")).isEmpty())
    }
}
