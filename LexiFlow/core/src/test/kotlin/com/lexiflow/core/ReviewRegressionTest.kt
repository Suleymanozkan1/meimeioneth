package com.lexiflow.core

import com.lexiflow.core.fsrs.Fsrs
import com.lexiflow.core.fsrs.FsrsScheduler
import com.lexiflow.core.fsrs.MemoryState
import com.lexiflow.core.fsrs.Rating
import com.lexiflow.core.fsrs.SchedulingCard
import com.lexiflow.core.importer.DelimitedParser
import com.lexiflow.core.importer.TargetField
import com.lexiflow.core.language.GrammarRules
import com.lexiflow.core.language.SentenceEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the issues found in code review (docs/CODERABBIT_REPORT.md). */
class ReviewRegressionTest {

    @Test fun naturalEnglishIsNeverCorrected() {
        listOf(
            "He said her name softly.", "Read the words and say them aloud.", "He explained her decision.", "Can you explain them?",
            "Can I borrow her car?", "I borrowed them from the library.", "She is married with two children.",
            "We arrived to find the door locked.", "Results are entered in the database.", "She has the will to win.",
            "We stay there from May to September.", "She researches climate change.", "Many work from home now.",
            "Do married couples pay less tax?", "Do embedded systems need updates?", "Did Ahmed call?",
            "Why do mistakes happen?", "This makes the exercise more fun.", "The grant made research possible.",
            "I finally made it work.", "Is it love?", "It was an unimportant detail.", "He was an uninvited guest.",
            "I bought a used car.", "It was a unanimous decision.", "She is a Ukrainian writer.", "He made a U-turn.",
            "I need an X-ray.", "Plan A is better.", "Take vitamin A and C.", "I get up at 7 a.m. and have breakfast.",
            "Fruit, e.g. apples, is healthy.", "A union leader spoke.", "It is a unique chance.",
        ).forEach { s ->
            val certain = GrammarRules.check(s).filter { it.certain && it.severity >= 1 && it.ruleId != "sentence_capital" || it.ruleId == "sentence_capital" && it.certain && it.start > 0 }
            assertTrue("$s -> ${certain.map { it.ruleId + ":" + it.original + "->" + it.suggestion }}", certain.isEmpty())
            assertEquals(s, SentenceEvaluator.evaluate(s).corrected)
        }
    }

    @Test fun realErrorsStillCaught() {
        assertTrue(GrammarRules.check("an unique chance").any { it.ruleId == "article_a_an" })
        assertTrue(GrammarRules.check("a unimportant thing").any { it.ruleId == "article_a_an" })
        assertTrue(GrammarRules.check("I didn't played well.").any { it.ruleId == "past_after_did" })
        assertTrue(GrammarRules.check("He go to school.").any { it.ruleId == "agreement_s" })
    }

    @Test fun hardOnSameDayNeverLowersStability() {
        val fsrs = Fsrs()
        val s = MemoryState(1.2931, 5.112170705601055)
        assertEquals(1.2931, fsrs.nextMemoryState(s, 0.0, Rating.HARD).stability, 1e-9)
        assertTrue(fsrs.nextMemoryState(s, 0.0, Rating.AGAIN).stability < 1.2931)
    }

    @Test fun previewWithSeedEqualsAppliedInterval() {
        val sched = FsrsScheduler()
        val now = 1_700_000_000_000L
        val card = SchedulingCard(com.lexiflow.core.fsrs.CardState.REVIEW, null, 30.0, 5.0, now, now - 30 * FsrsScheduler.DAY_MS, 6, 0, 30)
        val p = sched.preview(card, now, fuzzSeed = 42)
        Rating.entries.forEach { r -> assertEquals(p.getValue(r).intervalMillis, sched.review(card, r, now, 42).intervalMillis) }
    }

    @Test fun singleVocabularyWordIsNotTakenAsHeader() {
        val t = DelimitedParser.parse("back;geri;He came back.\ndeck;güverte;We sat on the deck.\n")
        assertNull(t.header)
        assertEquals(2, t.rows.size)
        assertEquals(listOf(1, 2), t.lineNumbers)
    }

    @Test fun ankiDirectivesAndRealLineNumbers() {
        val txt = "#separator:tab\n#html:true\n#guid column:1\n#tags column:4\nabc123\tpostpone\tertelemek\tb1\n\nxyz\t\tboş\t\n"
        val t = DelimitedParser.parse(txt)
        val mapping = DelimitedParser.suggestMapping(t.header, 4, t.columnHints)
        assertEquals(listOf(TargetField.IGNORE, TargetField.WORD, TargetField.MEANING_TR, TargetField.TAGS), mapping)
        val (cands, errors) = DelimitedParser.toCandidates(t, mapping)
        assertEquals("postpone", cands.single().word)
        assertEquals(5, cands.single().lineNumber)
        assertEquals(7, errors.single().lineNumber)
        val withColumns = DelimitedParser.parse("#separator:tab\n#columns:Front\tBack\n run\tkoşmak\n")
        assertEquals(listOf("Front", "Back"), withColumns.header)
        assertEquals(1, withColumns.rows.size)
    }
}
