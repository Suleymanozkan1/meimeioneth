package com.lexiflow.core

import com.lexiflow.core.language.EvaluationTask
import com.lexiflow.core.language.GrammarRules
import com.lexiflow.core.language.IssueCategory
import com.lexiflow.core.language.Morphology
import com.lexiflow.core.language.SentenceEvaluator
import com.lexiflow.core.language.TargetWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageTest {
    @Test fun madeAPartyIsCorrected() {
        val e = SentenceEvaluator.evaluate("I made a party yesterday.")
        val issue = e.issues.first { it.ruleId == "party_make" }
        assertEquals(IssueCategory.COLLOCATION, issue.category)
        assertEquals("I had a party yesterday.", e.corrected)
        assertTrue(issue.explanationTr.contains("throw a party"))
    }

    @Test fun naturalSentencesAreNotFlagged() {
        listOf(
            "I had a party yesterday.",
            "She was reluctant to leave, but eventually she agreed.",
            "We threw a surprise party for my brother.",
            "Does she have a car?",
            "Let him go home.",
            "It depends on the weather.",
            "An hour later, a university student called me.",
        ).forEach { s ->
            val issues = GrammarRules.check(s).filter { it.certain }
            assertTrue("$s -> $issues", issues.isEmpty())
        }
    }

    @Test fun commonLearnerErrors() {
        fun fix(s: String) = SentenceEvaluator.evaluate(s).corrected
        assertEquals("I didn't go to work.", fix("I didn't went to work."))
        assertEquals("She doesn't like coffee.", fix("She don't like coffee."))
        assertEquals("He works in a bank.", fix("He work in a bank."))
        assertEquals("I agree with you.", fix("I am agree with you."))
        assertEquals("You should try it.", fix("You should to try it."))
        assertEquals("I ate an apple.", fix("I ate a apple."))
        assertEquals("We discussed the plan.", fix("We discussed about the plan."))
        assertEquals("I have lived here for five years.", fix("I have lived here since five years."))
        assertEquals("Can you explain to me?", fix("Can you explain me?"))
        assertEquals("I listen to music every day.", fix("i listen music everyday."))
    }

    @Test fun inflectionsAreRecognised() {
        assertTrue(Morphology.usesWord("She ran to the station.", "run"))
        assertTrue(Morphology.usesWord("They postponed the trip.", "postpone"))
        assertTrue(Morphology.usesWord("We ran out of milk.", "run out of"))
        assertTrue(Morphology.usesWord("He is relying on you.", "rely"))
        assertFalse(Morphology.usesWord("He is ready.", "rely"))
    }

    @Test fun competitionScoringIsTransparent() {
        val words = listOf("reluctant", "efficient", "overlook", "eventually", "approach")
        val task = EvaluationTask(words.map { TargetWord(it) }, minWords = 8)
        val good = SentenceEvaluator.evaluate(
            "Although Ali was reluctant to change his approach, he eventually admitted that the new system was more efficient and that he had overlooked a few problems.", task)
        val list = SentenceEvaluator.evaluate("reluctant efficient overlook eventually approach", task)
        assertEquals(100, good.scores.taskCompletion)
        assertTrue(good.scores.overall > list.scores.overall + 20)
        assertTrue(list.issues.any { it.ruleId == "word_list" })
        val partial = SentenceEvaluator.evaluate("I was reluctant to go out because it was raining.", task)
        assertEquals(listOf("efficient", "overlook", "eventually", "approach"), partial.targetUsage.filter { !it.used }.map { it.lemma })
        assertTrue(partial.keyPoints.size <= 3)
    }

    @Test fun sanitizeRemovesControlCharacters() {
        assertEquals("hello world", SentenceEvaluator.sanitize("hello\u0000   world\u0007"))
    }
}

class GrammarDrillTest {
    @Test fun everyDrillIsDetectedAndItsCorrectionIsClean() {
        com.lexiflow.core.language.GrammarDrills.all.forEach { d ->
            val wrong = GrammarRules.check(d.wrong)
            assertTrue("${d.ruleId} not detected in '${d.wrong}': $wrong", wrong.any { it.ruleId == d.ruleId })
            val right = GrammarRules.check(d.correct).filter { it.certain && it.severity >= 2 }
            assertTrue("false positive on '${d.correct}': $right", right.isEmpty())
        }
    }

    @Test fun gibberishGetsNoGrammarOrStylePoints() {
        val e = SentenceEvaluator.evaluate("bdnd", EvaluationTask(listOf(TargetWord("reluctant")), minWords = 6))
        assertTrue(e.issues.any { it.ruleId == "not_a_word" })
        assertEquals("not_a_word", e.keyPoints.first().ruleId)
        assertTrue("grammar ${e.scores.grammar}", e.scores.grammar <= 10)
        assertTrue("naturalness ${e.scores.naturalness}", e.scores.naturalness <= 10)
        assertTrue("diversity ${e.scores.diversity}", e.scores.diversity <= 10)
        assertTrue("overall ${e.scores.overall}", e.scores.overall <= 15)
        listOf("asdf ghjkl qwrt", "sdfgh", "aaaaa", "bu cümle türkçe").forEach { text ->
            assertTrue(text, SentenceEvaluator.evaluate(text, EvaluationTask(minWords = 3)).scores.overall < 40)
        }
        // Real words, acronyms and Turkish names are not flagged.
        listOf("rhythm", "strengths", "queue", "Iraq", "BBC", "I", "Ayşe", "Kadıköy", "twelfth", "gym", "hmm", "aaah")
            .forEach { assertFalse(it, SentenceEvaluator.looksLikeNonWord(it)) }
        val good = SentenceEvaluator.evaluate("She was reluctant to leave the party early.", EvaluationTask(listOf(TargetWord("reluctant")), minWords = 6))
        assertTrue(good.issues.none { it.ruleId == "not_a_word" })
        assertTrue("overall ${good.scores.overall}", good.scores.overall >= 80)
    }

    @Test fun shortAnswersCannotReachFullStyleScores() {
        val e = SentenceEvaluator.evaluate("Reluctant.", EvaluationTask(listOf(TargetWord("reluctant")), minWords = 6))
        assertTrue("grammar ${e.scores.grammar}", e.scores.grammar <= 20)
        assertTrue("diversity ${e.scores.diversity}", e.scores.diversity <= 20)
    }

    @Test fun turkishVerbLastWordOrderIsFlagged() {
        val e = SentenceEvaluator.evaluate("reluctant man he is", EvaluationTask(listOf(TargetWord("reluctant")), minWords = 4))
        val issue = e.issues.single { it.ruleId == GrammarRules.WORD_ORDER }
        assertEquals("He is a reluctant man", issue.suggestion)
        assertEquals("He is a reluctant man.", e.corrected)
        assertTrue("overall ${e.scores.overall}", e.scores.overall < 85)
        assertTrue(e.scores.grammar <= 76)
        assertEquals("She is happy", GrammarRules.check("Happy she is.").single { it.ruleId == GrammarRules.WORD_ORDER }.suggestion)
        listOf("I know who he is.", "Yes, it is.", "I think she is.", "Here it is.", "She is happy.", "He is as tall as I am.",
            "No matter how tired he is, he works.", "That is what it is.", "Of course it is.", "I wonder where she was.")
            .forEach { t -> assertTrue(t, GrammarRules.check(t).none { it.ruleId == GrammarRules.WORD_ORDER }) }
    }

    @Test fun everyGrammarDrillExplainsItsError() {
        com.lexiflow.core.language.GrammarDrills.all.forEach { d ->
            val ex = com.lexiflow.core.language.GrammarDrills.explain(d)
            assertTrue("no explanation for ${d.ruleId}", ex != null && ex.whyTr.isNotBlank())
        }
        val mistake = com.lexiflow.core.language.GrammarDrills.all.first { it.ruleId == "mistake_do" }
        val text = com.lexiflow.core.language.GrammarDrills.explain(mistake)!!.textTr()
        assertTrue(text, text.contains("does mistakes") && text.contains("makes mistakes") && text.contains("make a mistake"))
    }
}
