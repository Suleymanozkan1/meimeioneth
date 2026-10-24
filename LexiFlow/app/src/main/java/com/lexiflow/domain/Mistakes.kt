package com.lexiflow.domain

import com.lexiflow.core.exercise.Verdict
import com.lexiflow.core.language.Evaluation
import com.lexiflow.core.language.GrammarDrills
import com.lexiflow.core.language.IssueCategory

/** Kinds of mistakes in the error log (stored by name in mistakes.errorType). */
enum class MistakeType(val labelTr: String) {
    WRONG_WORD("Yanlış kelime"),
    SPELLING("Yazım hatası"),
    WRONG_FORM("Yanlış biçim (ek/zaman)"),
    COLLOCATION("Yanlış edat/kalıp"),
    WORD_ORDER("Söz dizimi"),
    MISSING_TARGET("Hedef kelime kullanılmadı"),
    GRAMMAR("Gramer"),
    USAGE("Kelime kullanımı"),
    LISTENING("Dinleme/dikte"),
    MEANING("Anlam karıştırma"),
}

/** Where a mistake was made, grouped the way the learner sees the modes. */
enum class MistakeMode(val labelTr: String) {
    CLOZE("Boşluk Doldurma"),
    SENTENCE("Cümle Oluşturma"),
    PRODUCTION("Kelime Üretimi"),
    TRANSLATION("Çeviri"),
    SPEAKING("Speaking"),
    DICTATION("Dikte"),
    GRAMMAR("Gramer"),
    RECALL("Kelime Hatırlama"),
    LISTENING("Dinleme"),
}

/** One classified mistake, ready to be stored. */
data class MistakeRecord(
    val cardId: Long?,
    val lemma: String,
    val mode: MistakeMode,
    val type: MistakeType,
    val ruleId: String?,
    val userAnswer: String,
    val correctAnswer: String,
)

/** Turns a graded answer into an error-log entry (null when the answer was right). */
object MistakeClassifier {

    fun modeOf(type: ExerciseType): MistakeMode = when (type) {
        ExerciseType.CLOZE_CHOICE, ExerciseType.CLOZE_HINT, ExerciseType.CLOZE_TYPED, ExerciseType.CONTEXT_CHOICE -> MistakeMode.CLOZE
        ExerciseType.SENTENCE_ORDER -> MistakeMode.SENTENCE
        ExerciseType.PRODUCTION -> MistakeMode.PRODUCTION
        ExerciseType.TRANSLATION -> MistakeMode.TRANSLATION
        ExerciseType.DICTATION -> MistakeMode.DICTATION
        ExerciseType.ERROR_CORRECTION -> MistakeMode.GRAMMAR
        ExerciseType.LISTENING_CHOICE -> MistakeMode.LISTENING
        ExerciseType.TR_TO_EN, ExerciseType.EN_TO_TR -> MistakeMode.RECALL
    }

    /**
     * [answer] is what the learner typed, arranged or chose. Voice answers in word production count as speaking.
     * Free-text answers that were accepted can still carry a certain grammar mistake; that one is logged too.
     */
    fun classify(ex: Exercise, result: GradeResult, answer: String, voice: Boolean = false): MistakeRecord? {
        val mode = if (voice && ex.type == ExerciseType.PRODUCTION) MistakeMode.SPEAKING else modeOf(ex.type)
        val shown = answer.trim().ifEmpty { "(boş / Bilmiyorum)" }.take(300)
        fun record(type: MistakeType, ruleId: String? = null, correct: String = result.correctAnswer) =
            MistakeRecord(ex.cardId, ex.lemma, mode, type, ruleId, shown, correct.take(300))
        if (ex.type == ExerciseType.ERROR_CORRECTION) {
            if (result.correct) return null
            val rule = GrammarDrills.all.firstOrNull { it.wrong == ex.prompt }?.ruleId
            return record(MistakeType.GRAMMAR, rule)
        }
        if (ex.isFreeText) {
            val grammar = grammarIssue(result.evaluation)
            return when {
                result.verdict == "MISSING_TARGET" -> record(MistakeType.MISSING_TARGET, correct = ex.reference ?: ex.lemma)
                grammar != null -> record(MistakeType.GRAMMAR, grammar.first, grammar.second ?: ex.reference ?: result.correctAnswer)
                !result.correct -> record(MistakeType.USAGE, correct = ex.reference ?: result.correctAnswer)
                else -> null
            }
        }
        if (result.correct) return null
        return when (ex.type) {
            ExerciseType.SENTENCE_ORDER -> record(MistakeType.WORD_ORDER)
            ExerciseType.DICTATION -> record(MistakeType.LISTENING)
            ExerciseType.EN_TO_TR, ExerciseType.LISTENING_CHOICE -> record(MistakeType.MEANING)
            else -> when (result.verdict) {
                Verdict.TYPO.name -> record(MistakeType.SPELLING)
                Verdict.WRONG_FORM.name -> record(MistakeType.WRONG_FORM)
                else -> when {
                    sameWordWrongParticle(answer, result.correctAnswer) -> record(MistakeType.COLLOCATION)
                    otherFormOfPattern(answer, result.correctAnswer, ex.lemma) -> record(MistakeType.WRONG_FORM)
                    else -> record(MistakeType.WRONG_WORD)
                }
            }
        }
    }

    /** Mistakes in a speaking turn: certain grammar points the evaluator found (one entry per rule). */
    fun speakingTurn(text: String, eval: Evaluation, voice: Boolean): List<MistakeRecord> =
        eval.issues.filter { it.certain && it.severity >= 2 && it.category in GRAMMAR_CATEGORIES }
            .distinctBy { it.ruleId }.take(3)
            .map { MistakeRecord(null, it.original.ifBlank { it.ruleId }, MistakeMode.SPEAKING,
                MistakeType.GRAMMAR, it.ruleId, text.take(300), it.suggestion ?: eval.corrected.take(300)) }

    private val GRAMMAR_CATEGORIES = setOf(IssueCategory.GRAMMAR, IssueCategory.COLLOCATION, IssueCategory.WORD_CHOICE)

    /** The most important certain grammar finding: rule id and the corrected sentence. */
    private fun grammarIssue(eval: Evaluation?): Pair<String, String?>? {
        val e = eval ?: return null
        val issue = e.issues.filter { it.certain && it.severity >= 2 && it.category in GRAMMAR_CATEGORIES }.maxByOrNull { it.severity } ?: return null
        return issue.ruleId to e.corrected.takeIf { it.isNotBlank() && it != e.text }
    }

    /** "relies on" for "rely on": the right pattern with the wrong inflection of the word. */
    private fun otherFormOfPattern(answer: String, expected: String, lemma: String): Boolean {
        val a = answer.trim().lowercase().split(Regex("\\s+"))
        val b = expected.trim().lowercase().split(Regex("\\s+"))
        return a.size == b.size && a.size >= 2 && a.drop(1) == b.drop(1) && a[0] != b[0] && a[0] in com.lexiflow.core.language.Morphology.forms(lemma.lowercase())
    }

    private fun sameWordWrongParticle(answer: String, expected: String): Boolean {
        val a = answer.trim().lowercase().split(Regex("\\s+"))
        val b = expected.trim().lowercase().split(Regex("\\s+"))
        return a.size == 2 && b.size == 2 && a[0] == b[0] && a[1] != b[1]
    }
}
