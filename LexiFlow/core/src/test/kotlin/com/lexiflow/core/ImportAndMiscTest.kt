package com.lexiflow.core

import com.lexiflow.core.ai.AiValidation
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.ai.StoryRequest
import com.lexiflow.core.ai.StoryResponse
import com.lexiflow.core.ai.StoryTarget
import com.lexiflow.core.competition.ClientMessage
import com.lexiflow.core.competition.ServerMessage
import com.lexiflow.core.exercise.AnswerChecker
import com.lexiflow.core.exercise.ClozeBuilder
import com.lexiflow.core.exercise.ExampleRotation
import com.lexiflow.core.exercise.ExampleUse
import com.lexiflow.core.exercise.QuestionRotation
import com.lexiflow.core.exercise.QuickQuestionType
import com.lexiflow.core.exercise.Verdict
import com.lexiflow.core.gamification.Levels
import com.lexiflow.core.gamification.StreakCalculator
import com.lexiflow.core.gamification.WeeklyMissions
import com.lexiflow.core.importer.DelimitedParser
import com.lexiflow.core.importer.DuplicateDetector
import com.lexiflow.core.importer.DuplicateKind
import com.lexiflow.core.importer.TargetField
import com.lexiflow.core.pronunciation.AsrHypothesis
import com.lexiflow.core.pronunciation.AudioSignals
import com.lexiflow.core.pronunciation.PronunciationScorer
import com.lexiflow.core.pronunciation.Reliability
import com.lexiflow.core.pronunciation.WordStatus
import com.lexiflow.core.story.ComposerTarget
import com.lexiflow.core.story.OfflineStoryComposer
import com.lexiflow.core.story.StoryCandidate
import com.lexiflow.core.story.StoryPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ImportAndMiscTest {
    @Test fun parsesQuotedCsvWithBomAndHeader() {
        val csv = "\uFEFFword,meaning,example\n\"run\",\"koşmak, işletmek\",\"She runs a \"\"small\"\" café.\"\nreluctant,isteksiz,\n,boş,\n"
        val t = DelimitedParser.parse(csv)
        assertEquals(',', t.delimiter)
        assertEquals(listOf("word", "meaning", "example"), t.header)
        assertEquals("koşmak, işletmek", t.rows[0][1])
        assertEquals("She runs a \"small\" café.", t.rows[0][2])
        val mapping = DelimitedParser.suggestMapping(t.header, 3)
        assertEquals(listOf(TargetField.WORD, TargetField.MEANING_TR, TargetField.EXAMPLE), mapping)
        val (cands, errors) = DelimitedParser.toCandidates(t, mapping)
        assertEquals(2, cands.size)
        assertEquals(1, errors.size)
        assertEquals(4, errors[0].lineNumber)
    }

    @Test fun parsesAnkiPlainTextExport() {
        val txt = "#separator:tab\n#html:true\npostpone\tertelemek<br>ileri atmak\tlesson1\nrely\t<b>güvenmek</b>\t\n"
        val t = DelimitedParser.parse(txt)
        assertEquals('\t', t.delimiter)
        assertNull(t.header)
        assertEquals("ertelemek\nileri atmak", t.rows[0][1])
        assertEquals("güvenmek", t.rows[1][1])
    }

    @Test fun duplicatesNeverMergeSenses() {
        val existing = mapOf("run" to listOf("koşmak"))
        assertEquals(DuplicateKind.EXACT, DuplicateDetector.classify("Run", "Koşmak.", existing))
        assertEquals(DuplicateKind.SAME_WORD_NEW_SENSE, DuplicateDetector.classify("run", "işletmek, yönetmek", existing))
        assertEquals(DuplicateKind.NONE, DuplicateDetector.classify("walk", "yürümek", existing))
    }

    @Test fun streakIsTimezoneSafeAndNotBrokenBeforeDayEnds() {
        val today = LocalDate.of(2026, 10, 18)
        val days = setOf(today.minusDays(1), today.minusDays(2), today.minusDays(3), today.minusDays(10))
        val s = StreakCalculator.compute(days, today)
        assertEquals(3, s.current)
        assertFalse(s.activeToday)
        assertEquals(4, StreakCalculator.compute(days + today, today).current)
        assertEquals(0, StreakCalculator.compute(setOf(today.minusDays(2)), today).current)
    }

    @Test fun levels() {
        assertEquals(1, Levels.fromXp(0).level)
        assertEquals(2, Levels.fromXp(50).level)
        assertEquals(3, Levels.fromXp(200).level)
        assertEquals(3, WeeklyMissions.forWeek(LocalDate.of(2026, 10, 12)).map { it.id }.toSet().size)
    }

    @Test fun answerChecking() {
        assertEquals(Verdict.CORRECT, AnswerChecker.check(" Postpone ", "postpone").verdict)
        assertEquals(Verdict.ALTERNATIVE, AnswerChecker.check("delay", "postpone", listOf("delay", "put off")).verdict)
        assertEquals(Verdict.TYPO, AnswerChecker.check("postpon", "postpone").verdict)
        assertEquals(Verdict.WRONG_FORM, AnswerChecker.check("postpone", "postponed", lemma = "postpone").verdict)
        assertEquals(Verdict.WRONG, AnswerChecker.check("cancel", "postpone").verdict)
    }

    @Test fun clozeAndRotation() {
        val c = ClozeBuilder.build("I had to postpone the meeting until Friday.", "postpone")!!
        assertEquals("I had to ______ the meeting until Friday.", c.prompt)
        assertNull(ClozeBuilder.build("No target here.", "postpone"))
        val pick = ExampleRotation.pick(listOf(ExampleUse(1, 100, 3), ExampleUse(2, null, 0), ExampleUse(3, 50, 1)), 1)
        assertEquals(2L, pick!!.exampleId)
        val types = (0L until 6L).map { QuestionRotation.next(it, 0.2, 0.2, true) }.toSet()
        assertTrue(types.containsAll(QuickQuestionType.entries))
    }

    @Test fun pronunciationIsHonestAboutUncertainty() {
        val good = PronunciationScorer.score("I live in a small flat", listOf(AsrHypothesis("I leave in a small flat", 0.82f), AsrHypothesis("I live in a small flat", 0.6f)), AudioSignals(2000, 8f))
        val live = good.words.first { it.target == "live" }
        assertEquals(WordStatus.UNCERTAIN, live.status)
        assertTrue(live.tipTr!!.contains("/ɪ/"))
        assertEquals(Reliability.GOOD, good.reliability)
        val noConf = PronunciationScorer.score("think", listOf(AsrHypothesis("sink", null)), AudioSignals(800))
        assertEquals(Reliability.LOW, noConf.reliability)
        assertEquals(WordStatus.MISHEARD, noConf.words[0].status)
        val silent = PronunciationScorer.score("hello", emptyList(), AudioSignals(100, speechDetected = false))
        assertEquals(Reliability.UNUSABLE, silent.reliability)
        assertNull(silent.intelligibility)
    }

    @Test fun storyPlannerPrefersAtRiskWords() {
        val now = 1_700_000_000_000L
        fun c(id: Long, r: Double, prod: Double, last: Long? = now - 86_400_000L) = StoryCandidate(id, "w$id", "m", r, now, last, 0, 5.0, prod, false, null, null)
        val picked = StoryPlanner.select(listOf(c(1, 0.95, 0.9), c(2, 0.6, 0.2), c(3, 0.7, 0.5), c(4, 0.5, 0.1, now - 1000)), 2, now)
        assertEquals(listOf(2L, 3L), picked.map { it.cardId })
    }

    @Test fun offlineStoryIsValid() {
        val targets = listOf(
            ComposerTarget("postpone", "ertelemek", "We had to postpone the meeting until Friday.", "postpone"),
            ComposerTarget("reluctant", "isteksiz", "He was reluctant to ask for help.", "reluctant"),
        )
        val story = OfflineStoryComposer.compose("Work", targets, 3)
        val req = StoryRequest(targets.map { StoryTarget(it.lemma) }, wordCount = 120)
        assertEquals(emptyList<String>(), AiValidation.validateStory(story, req))
        val json = LexiJson.encodeToString(StoryResponse.serializer(), story)
        assertEquals(story, LexiJson.decodeFromString(StoryResponse.serializer(), json))
    }

    @Test fun extractsJsonFromProse() {
        assertEquals("{\"a\":\"}\"}", AiValidation.extractJsonObject("Sure! {\"a\":\"}\"} done"))
        assertNull(AiValidation.extractJsonObject("no json"))
    }

    @Test fun protocolRoundTrip() {
        val msg: ClientMessage = ClientMessage.Submit("r1", "hello")
        val s = LexiJson.encodeToString(ClientMessage.serializer(), msg)
        assertTrue(s.contains("\"type\":\"submit\""))
        assertEquals(msg, LexiJson.decodeFromString(ClientMessage.serializer(), s))
        val pong = LexiJson.encodeToString(ServerMessage.serializer(), ServerMessage.Pong)
        assertNotNull(LexiJson.decodeFromString(ServerMessage.serializer(), pong))
    }
}
