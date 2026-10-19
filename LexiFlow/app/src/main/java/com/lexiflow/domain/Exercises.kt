package com.lexiflow.domain

import com.lexiflow.core.active.HintBuilder
import com.lexiflow.core.active.Skill
import com.lexiflow.core.exercise.AnswerChecker
import com.lexiflow.core.exercise.ClozeBuilder
import com.lexiflow.core.exercise.Distractors
import com.lexiflow.core.exercise.ExampleRotation
import com.lexiflow.core.exercise.ExampleUse
import com.lexiflow.core.exercise.Verdict
import com.lexiflow.core.language.Evaluation
import com.lexiflow.core.language.EvaluationTask
import com.lexiflow.core.language.GrammarDrill
import com.lexiflow.core.language.GrammarRules
import com.lexiflow.core.language.Morphology
import com.lexiflow.core.language.SentenceEvaluator
import com.lexiflow.core.language.TargetWord
import com.lexiflow.core.pronunciation.PronunciationScorer
import com.lexiflow.data.db.CardDetail
import com.lexiflow.data.db.ExampleEntity
import com.lexiflow.data.repo.WordRepository
import kotlin.random.Random

enum class ExerciseType(val labelTr: String, val skill: Skill) {
    TR_TO_EN("Türkçeden İngilizceye", Skill.RECALL),
    EN_TO_TR("İngilizceden Türkçeye", Skill.MEANING_RECALL),
    CLOZE_CHOICE("Boşluk doldurma (seçenekli)", Skill.CONTEXT),
    CLOZE_HINT("Boşluk doldurma (ipuçlu)", Skill.CONTEXT),
    CLOZE_TYPED("Boşluk doldurma (ipucusuz)", Skill.CONTEXT),
    SENTENCE_ORDER("Cümle kurma (sıralama)", Skill.CONTEXT),
    PRODUCTION("Kendi cümleni yaz", Skill.PRODUCTION),
    TRANSLATION("Cümle çevirisi", Skill.PRODUCTION),
    CONTEXT_CHOICE("Duruma uygun kelime", Skill.CONTEXT),
    LISTENING_CHOICE("Dinle ve anla", Skill.MEANING_RECALL),
    DICTATION("Dikte", Skill.CONTEXT),
    ERROR_CORRECTION("Hatayı düzelt", Skill.PRODUCTION),
}

data class Exercise(
    val type: ExerciseType,
    val cardId: Long?,
    val lemma: String,
    val meaningTr: String,
    val instructionTr: String,
    val prompt: String,
    val secondary: String? = null,
    val options: List<String> = emptyList(),
    val correctIndex: Int? = null,
    val expected: String,
    val alternatives: List<String> = emptyList(),
    val hintLevel: Int = 0,
    val hints: List<String> = emptyList(),
    val exampleId: Long? = null,
    val reference: String? = null,
    val referenceTr: String? = null,
    val ttsText: String? = null,
    val tokens: List<String> = emptyList(),
    val explanationTr: String? = null,
    val expectedPatterns: List<String> = emptyList(),
) {
    val isChoice get() = options.isNotEmpty()
    val isFreeText get() = type == ExerciseType.PRODUCTION || type == ExerciseType.TRANSLATION || type == ExerciseType.ERROR_CORRECTION
}

data class GradeResult(
    val score: Double,
    val verdict: String,
    val correct: Boolean,
    val feedbackTr: String,
    val correctAnswer: String,
    val evaluation: Evaluation? = null,
)

/** Builds exercises from a card and its stored example sentences. */
object ExerciseBuilder {

    fun pickExample(examples: List<ExampleEntity>, seed: Long): ExampleEntity? {
        val use = ExampleRotation.pick(examples.map { ExampleUse(it.id, it.lastShownAt, it.timesShown) }, seed) ?: return null
        return examples.first { it.id == use.exampleId }
    }

    fun build(
        type: ExerciseType,
        card: CardDetail,
        example: ExampleEntity?,
        hintLevel: Int,
        meaningPool: List<String>,
        lemmaPool: List<String>,
        seed: Long,
    ): Exercise? {
        val lemma = card.lemma
        val meaning = card.meaningTr
        val cloze = example?.let { ClozeBuilder.build(it.en, it.answer) }
        val alternatives = example?.let { WordRepository.decodeAlternatives(it.alternativesJson) } ?: emptyList()
        val hints = HintBuilder.hint(hintLevel, lemma, meaning, cloze?.prompt)
        return when (type) {
            ExerciseType.TR_TO_EN -> Exercise(
                type, card.card.id, lemma, meaning,
                "Bu anlama gelen İngilizce kelimeyi yaz.", "\"$meaning\" — İngilizcesi nedir?",
                secondary = card.definitionEn?.let { "($it)" }.takeIf { hintLevel >= 2 },
                expected = lemma, hintLevel = hintLevel, hints = hints, exampleId = example?.id,
                reference = example?.en, referenceTr = example?.tr,
            )
            ExerciseType.EN_TO_TR -> {
                val (opts, idx) = Distractors.options(meaning, meaningPool, 4, seed)
                Exercise(type, card.card.id, lemma, meaning, "Kelimenin Türkçe anlamını seç.", lemma,
                    secondary = example?.en, options = opts, correctIndex = idx, expected = meaning, exampleId = example?.id,
                    reference = example?.en, referenceTr = example?.tr, ttsText = lemma)
            }
            ExerciseType.CLOZE_CHOICE, ExerciseType.CLOZE_HINT, ExerciseType.CLOZE_TYPED -> {
                if (cloze == null || example == null) return null
                val base = Exercise(type, card.card.id, lemma, meaning, "Boşluğa uygun kelimeyi yaz.", cloze.prompt,
                    secondary = example.tr.takeIf { type != ExerciseType.CLOZE_TYPED }, expected = cloze.answer, alternatives = alternatives,
                    exampleId = example.id, reference = example.en, referenceTr = example.tr, explanationTr = example.noteTr)
                when (type) {
                    ExerciseType.CLOZE_CHOICE -> {
                        val (opts, idx) = Distractors.options(cloze.answer, lemmaPool.map { inflectLike(it, cloze.answer, lemma) }, 4, seed)
                        base.copy(instructionTr = "Boşluğa uygun kelimeyi seç.", options = opts, correctIndex = idx)
                    }
                    ExerciseType.CLOZE_HINT -> base.copy(hintLevel = maxOf(1, hintLevel), hints = HintBuilder.hint(maxOf(1, hintLevel), cloze.answer, meaning, null))
                    else -> base
                }
            }
            ExerciseType.SENTENCE_ORDER -> {
                if (example == null) return null
                val words = example.en.trim().split(Regex("\\s+"))
                if (words.size < 4 || words.size > 16) return null
                var shuffled = words.shuffled(Random(seed))
                if (shuffled == words) shuffled = words.reversed()
                Exercise(type, card.card.id, lemma, meaning, "Kelimeleri doğru sıraya diz.", example.tr ?: "Cümleyi kur.",
                    expected = example.en, tokens = shuffled, exampleId = example.id, reference = example.en, referenceTr = example.tr,
                    explanationTr = example.noteTr)
            }
            ExerciseType.PRODUCTION -> Exercise(
                type, card.card.id, lemma, meaning,
                if (hintLevel == 0) "Kelimeyi ipucu olmadan kendi cümlende kullan." else "Kelimeyi kendi cümlende kullan.",
                "\"$lemma\" kelimesiyle kendi hayatından bir cümle kur.",
                secondary = if (hintLevel >= 2) "Anlam: $meaning" else null,
                expected = lemma, hintLevel = hintLevel,
                hints = if (hintLevel >= 3 && example != null) listOf("Örnek: ${example.en}") else emptyList(),
                exampleId = example?.id, reference = example?.en, referenceTr = example?.tr,
                expectedPatterns = patternsFor(card),
            )
            ExerciseType.TRANSLATION -> {
                if (example?.tr == null) return null
                Exercise(type, card.card.id, lemma, meaning, "Cümleyi İngilizceye çevir. \"$lemma\" kelimesini kullan.", example.tr,
                    expected = example.en, hintLevel = hintLevel, hints = hints.take(if (hintLevel > 0) 1 else 0),
                    exampleId = example.id, reference = example.en, referenceTr = example.tr, expectedPatterns = patternsFor(card))
            }
            ExerciseType.CONTEXT_CHOICE -> {
                val situation = card.definitionEn ?: return null
                val (opts, idx) = Distractors.options(lemma, lemmaPool, 4, seed)
                Exercise(type, card.card.id, lemma, meaning, "Bu duruma/tanıma en uygun kelimeyi seç.", situation,
                    secondary = if (hintLevel >= 2) meaning else null, options = opts, correctIndex = idx, expected = lemma,
                    exampleId = example?.id, reference = example?.en, referenceTr = example?.tr)
            }
            ExerciseType.LISTENING_CHOICE -> {
                if (example == null) return null
                val (opts, idx) = Distractors.options(lemma, lemmaPool, 4, seed)
                Exercise(type, card.card.id, lemma, meaning, "Cümleyi dinle. Hangi kelime kullanıldı?", "🔊 Dinlemek için dokun",
                    options = opts, correctIndex = idx, expected = lemma, exampleId = example.id, reference = example.en,
                    referenceTr = example.tr, ttsText = example.en)
            }
            ExerciseType.DICTATION -> {
                if (example == null) return null
                Exercise(type, card.card.id, lemma, meaning, "Duyduğun cümleyi aynen yaz.", "🔊 Dinlemek için dokun",
                    expected = example.en, exampleId = example.id, reference = example.en, referenceTr = example.tr, ttsText = example.en)
            }
            ExerciseType.ERROR_CORRECTION -> null
        }
    }

    fun grammarDrill(drill: GrammarDrill): Exercise = Exercise(
        ExerciseType.ERROR_CORRECTION, null, drill.focusTr, drill.focusTr, "Cümledeki hatayı bul ve doğru cümleyi yaz.", drill.wrong,
        secondary = "Odak: ${drill.focusTr}", expected = drill.correct, reference = drill.correct,
        explanationTr = com.lexiflow.core.language.GrammarDrills.explain(drill)?.textTr(),
    )

    private fun patternsFor(card: CardDetail): List<String> =
        com.lexiflow.core.competition.WordBank.patterns[card.lemma.lowercase()].orEmpty()

    /** Gives distractor lemmas the same suffix as the answer so the inflection does not give the answer away. */
    fun inflectLike(lemma: String, answer: String, targetLemma: String): String {
        val a = answer.lowercase(); val t = targetLemma.lowercase()
        if (a == t || lemma.contains(' ')) return lemma
        val forms = Morphology.forms(lemma)
        val suffix = when {
            a.endsWith("ing") -> "ing"; a.endsWith("ed") -> "ed"; a.endsWith("s") -> "s"; else -> ""
        }
        if (suffix.isEmpty()) return lemma
        return forms.firstOrNull { it.endsWith(suffix) && it.length <= lemma.length + 4 } ?: lemma
    }
}

object ExerciseGrader {
    fun gradeChoice(ex: Exercise, chosen: Int): GradeResult {
        val ok = chosen == ex.correctIndex
        return GradeResult(if (ok) 1.0 else 0.0, if (ok) "CORRECT" else "WRONG", ok,
            if (ok) "Doğru!" else "Doğru cevap: ${ex.expected}", ex.expected)
    }

    fun gradeTyped(ex: Exercise, input: String): GradeResult {
        val check = AnswerChecker.check(input, ex.expected, ex.alternatives, ex.lemma)
        val ok = check.verdict == Verdict.CORRECT || check.verdict == Verdict.ALTERNATIVE
        return GradeResult(check.verdict.score, check.verdict.name, ok, check.feedbackTr, ex.expected)
    }

    fun gradeOrder(ex: Exercise, arranged: List<String>): GradeResult {
        val expected = ex.expected.trim().split(Regex("\\s+"))
        val exact = arranged == expected
        val positional = expected.indices.count { it < arranged.size && arranged[it] == expected[it] }.toDouble() / expected.size
        val score = if (exact) 1.0 else positional * 0.6
        return GradeResult(score, if (exact) "CORRECT" else "WRONG", exact,
            if (exact) "Harika, cümle doğru!" else "Doğru sıralama: ${ex.expected}", ex.expected)
    }

    fun gradeDictation(ex: Exercise, input: String): GradeResult {
        val target = Morphology.tokens(ex.expected)
        val heard = Morphology.tokens(input)
        val aligned = PronunciationScorer.align(target, heard)
        val matched = aligned.count { (t, h) -> h != null && t == h }
        val score = if (target.isEmpty()) 0.0 else matched.toDouble() / target.size
        val missing = aligned.filter { (t, h) -> h != t }.map { it.first }
        val feedback = if (missing.isEmpty()) "Kusursuz dikte!" else "Kaçırılan/yanlış kelimeler: ${missing.joinToString(", ")}\nDoğrusu: ${ex.expected}"
        return GradeResult(score, if (score >= 0.95) "CORRECT" else "PARTIAL", score >= 0.95, feedback, ex.expected)
    }

    /**
     * Free text (own sentence / translation / error correction). The target word must appear; the
     * evaluator's grammar, usage and naturalness scores drive the result. Alternative correct phrasings
     * are accepted because we never require a match with one reference sentence.
     */
    fun gradeFreeText(ex: Exercise, input: String): GradeResult {
        val text = input.trim()
        if (ex.type == ExerciseType.ERROR_CORRECTION) {
            val eval = SentenceEvaluator.evaluate(text, EvaluationTask(minWords = 3))
            val certainIssues = eval.issues.filter { it.certain && it.start >= 0 && it.severity >= 2 }
            val wrongStillThere = GrammarRules.check(text).any { it.certain && it.severity >= 2 }
            val similarity = Morphology.similarity(AnswerChecker.normalize(text), AnswerChecker.normalize(ex.expected))
            val ok = !wrongStillThere && certainIssues.isEmpty() && similarity >= 0.6
            val score = if (ok) 1.0 else if (similarity >= 0.6) 0.4 else 0.1
            val fb = if (ok) "Doğru düzelttin!" else "Önerilen doğru cümle: ${ex.expected}"
            return GradeResult(score, if (ok) "CORRECT" else "WRONG", ok, fb, ex.expected, eval)
        }
        val task = EvaluationTask(listOf(TargetWord(ex.lemma, ex.expectedPatterns)), requireAllTargets = true, minWords = 4)
        val eval = SentenceEvaluator.evaluate(text, task)
        val used = eval.targetUsage.firstOrNull()?.used == true
        var score = if (!used) 0.15 else (eval.scores.grammar * 0.4 + eval.scores.wordUsage * 0.3 + eval.scores.naturalness * 0.2 + eval.scores.coherence * 0.1) / 100.0
        if (ex.type == ExerciseType.TRANSLATION && used) {
            val overlap = tokenOverlap(text, ex.expected)
            score = score * 0.7 + overlap * 0.3
        }
        val ok = used && score >= 0.7
        val fb = buildString {
            if (!used) append("\"${ex.lemma}\" kelimesini (veya bir çekimini) kullanmadın. ")
            if (ok) append("Güzel! Kelimeyi doğru bağlamda kullandın. ")
            if (eval.keyPoints.isNotEmpty()) append("Önemli noktalar aşağıda. ")
            if (ex.type == ExerciseType.TRANSLATION) append("Örnek çeviri: ${ex.expected}")
        }.trim()
        return GradeResult(score.coerceIn(0.0, 1.0), if (ok) "CORRECT" else if (used) "PARTIAL" else "MISSING_TARGET", ok, fb, ex.expected, eval)
    }

    private fun tokenOverlap(a: String, b: String): Double {
        val x = Morphology.tokens(a).toSet(); val y = Morphology.tokens(b).toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return (x intersect y).size.toDouble() / y.size
    }
}
