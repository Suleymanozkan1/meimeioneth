package com.lexiflow.core.exercise

import com.lexiflow.core.language.Morphology
import kotlin.random.Random

enum class Verdict(val labelTr: String, val score: Double) {
    CORRECT("Doğru", 1.0),
    ALTERNATIVE("Bu da doğru", 1.0),
    TYPO("Neredeyse — yazımı kontrol et", 0.6),
    WRONG_FORM("Doğru kelime, yanlış biçim", 0.5),
    WRONG("Yanlış", 0.0),
}

data class AnswerCheck(val verdict: Verdict, val expected: String, val feedbackTr: String)

/** Checks typed answers for fill-in-the-blank and recall exercises. Accepts listed alternatives. */
object AnswerChecker {
    fun normalize(s: String) = s.trim().lowercase().replace(Regex("[.!?,;:\"]"), "").replace(Regex("\\s+"), " ")

    fun check(input: String, expected: String, alternatives: List<String> = emptyList(), lemma: String? = null): AnswerCheck {
        val given = normalize(input)
        val exp = normalize(expected)
        if (given.isEmpty()) return AnswerCheck(Verdict.WRONG, expected, "Cevap boş bırakıldı. Doğru cevap: $expected")
        if (given == exp) return AnswerCheck(Verdict.CORRECT, expected, "Doğru!")
        val alt = alternatives.map(::normalize).firstOrNull { it == given }
        if (alt != null) return AnswerCheck(Verdict.ALTERNATIVE, expected, "\"$input\" da bu boşlukta doğru. Kartın hedef kelimesi: $expected")
        if (lemma != null && Morphology.forms(lemma).contains(given)) {
            return AnswerCheck(Verdict.WRONG_FORM, expected, "Kelime doğru ama bu cümlede \"$expected\" biçimi gerekiyor (zaman/çoğul/ek uyumu).")
        }
        val distance = Morphology.levenshtein(given, exp)
        val allowed = if (exp.length >= 7) 2 else if (exp.length >= 4) 1 else 0
        if (distance in 1..allowed) return AnswerCheck(Verdict.TYPO, expected, "Çok yakın! Doğru yazım: $expected")
        return AnswerCheck(Verdict.WRONG, expected, "Doğru cevap: $expected")
    }
}

data class Cloze(val prompt: String, val answer: String, val blankIndex: Int)

object ClozeBuilder {
    const val BLANK = "______"

    /** Replaces the first occurrence of [answer] (case-insensitive, whole word) with a blank. */
    fun build(sentence: String, answer: String): Cloze? {
        val regex = Regex("(?i)(?<![A-Za-z])" + Regex.escape(answer) + "(?![A-Za-z])")
        val m = regex.find(sentence) ?: return null
        return Cloze(sentence.replaceRange(m.range, BLANK), m.value, m.range.first)
    }

    /**
     * Like [build], but when the word is followed by one of its [particles] ("relied on", "interested in") the blank
     * covers the whole pattern, so the learner has to produce the preposition too.
     */
    fun buildWithPattern(sentence: String, answer: String, particles: Collection<String>): Cloze? {
        val base = build(sentence, answer) ?: return null
        if (particles.isEmpty() || answer.contains(' ')) return base
        val after = sentence.substring(base.blankIndex + base.answer.length)
        val m = Regex("^\\s+([A-Za-z]+)(?![A-Za-z])").find(after) ?: return base
        if (m.groupValues[1].lowercase() !in particles.map { it.lowercase() }) return base
        val phrase = base.answer + after.substring(0, m.range.last + 1)
        return Cloze(sentence.replaceRange(base.blankIndex, base.blankIndex + phrase.length, BLANK), phrase, base.blankIndex)
    }

    /** Masks every inflected form of the lemma, used for "guess from context" story reading. */
    fun maskLemma(text: String, lemma: String): String {
        var out = text
        Morphology.forms(lemma).sortedByDescending { it.length }.forEach { f ->
            out = out.replace(Regex("(?i)(?<![A-Za-z'])" + Regex.escape(f) + "(?![A-Za-z'])"), BLANK)
        }
        return out
    }
}

/** Input for selecting which example to show today. */
data class ExampleUse(val exampleId: Long, val lastShownAt: Long?, val timesShown: Int)

object ExampleRotation {
    /**
     * Picks the example shown least recently (never-shown first), so the same word is asked with a different
     * sentence on different days. Ties are broken by a seed so the order is not always identical.
     */
    fun pick(examples: List<ExampleUse>, seed: Long): ExampleUse? {
        if (examples.isEmpty()) return null
        val random = Random(seed)
        return examples.shuffled(random).minWithOrNull(compareBy<ExampleUse> { it.lastShownAt ?: Long.MIN_VALUE }.thenBy { it.timesShown })
    }
}

/** Multiple-choice options: correct answer plus distractors (deduplicated, shuffled deterministically). */
object Distractors {
    fun options(correct: String, pool: List<String>, count: Int = 3, seed: Long = 0L): Pair<List<String>, Int> {
        val norm = AnswerChecker.normalize(correct)
        val distinct = pool.filter { AnswerChecker.normalize(it) != norm && it.isNotBlank() }.distinctBy { AnswerChecker.normalize(it) }
        val chosen = distinct.shuffled(Random(seed)).take(count - 1)
        val all = (chosen + correct).shuffled(Random(seed + 7))
        return all to all.indexOf(correct)
    }
}

/** Question types used by lock-screen questions and quick drills. */
enum class QuickQuestionType(val labelTr: String) {
    EN_TO_TR("İngilizceden Türkçeye"),
    TR_TO_EN("Türkçeden İngilizceye"),
    CLOZE("Boşluk doldurma"),
    PRODUCTION("Kendi cümleni kur"),
    /** Which of two sentences is correct (grammar points missed in the autopilot). */
    GRAMMAR("Gramer"),
}

object QuestionRotation {
    /**
     * Rotates question types. Weak active recall (low recall/production mastery) biases toward TR->EN and
     * production; strong learners see more cloze and production. Recognition-only learners still get EN->TR.
     */
    fun next(counter: Long, recallMastery: Double, productionMastery: Double, hasExample: Boolean): QuickQuestionType {
        val cycle = buildList {
            add(QuickQuestionType.EN_TO_TR)
            add(QuickQuestionType.TR_TO_EN)
            if (hasExample) add(QuickQuestionType.CLOZE)
            if (recallMastery < 0.5) add(QuickQuestionType.TR_TO_EN)
            add(QuickQuestionType.PRODUCTION)
            if (productionMastery < 0.4 && hasExample) add(QuickQuestionType.CLOZE)
        }
        return cycle[Math.floorMod(counter, cycle.size.toLong()).toInt()]
    }
}

/** Prepositions and particles a word is used with ("rely on", "interested in"), from its collocation list. */
object Collocations {
    val PARTICLES = setOf("on", "in", "at", "for", "to", "with", "about", "of", "from", "into", "by", "up", "out", "off", "down", "over", "away", "back", "after", "through")

    fun particlesFor(lemma: String, collocations: List<String>, extra: List<String> = emptyList()): List<String> {
        val head = lemma.trim().lowercase().split(Regex("\\s+")).lastOrNull() ?: return extra
        if (lemma.trim().contains(' ')) return emptyList() // phrases already contain their particle
        val forms = Morphology.forms(head)
        val fromCollocations = collocations.mapNotNull { c ->
            val tokens = c.lowercase().split(Regex("[^a-z']+")).filter { it.isNotBlank() }
            val i = tokens.indexOfFirst { it in forms }
            tokens.getOrNull(i + 1)?.takeIf { i >= 0 && it in PARTICLES }
        }
        return (fromCollocations + extra.map { it.lowercase() }).filter { it in PARTICLES }.distinct()
    }
}
