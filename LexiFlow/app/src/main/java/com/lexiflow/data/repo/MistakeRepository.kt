package com.lexiflow.data.repo

import com.lexiflow.core.language.GrammarDrills
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.db.MistakeEntity
import com.lexiflow.data.db.MistakeGroup
import com.lexiflow.domain.Exercise
import com.lexiflow.domain.GradeResult
import com.lexiflow.domain.MistakeClassifier
import com.lexiflow.domain.MistakeMode
import com.lexiflow.domain.MistakeRecord
import com.lexiflow.domain.MistakeType
import com.lexiflow.util.Clock

/** One of "Bu haftanın en sık 5 hatası", with where to practise it. */
data class FrequentMistake(
    val group: MistakeGroup,
    val titleTr: String,
    val typeTr: String,
    val modeTr: String,
    /** Grammar rule to drill, or null. */
    val ruleId: String?,
    /** Card to review, or null. */
    val cardId: Long?,
)

/** The error log: every wrong answer of every mode in one table, and the weekly top mistakes. */
class MistakeRepository(private val db: AppDatabase, private val clock: Clock) {
    private val dao = db.mistakeDao()

    /** Logs [ex] when the answer was wrong (or a right answer still had a certain grammar mistake). */
    suspend fun recordExercise(ex: Exercise, result: GradeResult, answer: String, voice: Boolean = false, source: String = "APP") {
        MistakeClassifier.classify(ex, result, answer, voice)?.let { record(it, source) }
    }

    suspend fun record(r: MistakeRecord, source: String = "APP") {
        val now = clock.now()
        dao.insert(
            MistakeEntity(cardId = r.cardId, lemma = r.lemma.take(80), mode = r.mode.name, errorType = r.type.name, ruleId = r.ruleId,
                userAnswer = r.userAnswer, correctAnswer = r.correctAnswer, at = now, localDate = clock.localDate(now), source = source),
        )
    }

    fun observeRecent(limit: Int = 100) = dao.observeRecent(limit)

    suspend fun topThisWeek(limit: Int = 5): List<FrequentMistake> = dao.topSince(clock.startOfWeek(), limit).map { g ->
        val type = runCatching { MistakeType.valueOf(g.errorType) }.getOrDefault(MistakeType.WRONG_WORD)
        val mode = runCatching { MistakeMode.valueOf(g.mode) }.getOrDefault(MistakeMode.RECALL)
        val title = g.ruleId?.let { grammarTopic(it) } ?: g.lemma
        FrequentMistake(g, title, type.labelTr, mode.labelTr, g.ruleId, g.cardId.takeIf { g.ruleId == null })
    }

    companion object {
        /** Name of the Gramer topic of a rule ("make a mistake"), falling back to the rule id. */
        fun grammarTopic(ruleId: String): String = GrammarDrills.all.firstOrNull { it.ruleId == ruleId }?.focusTr ?: ruleId.replace('_', ' ')
        fun hasDrill(ruleId: String): Boolean = GrammarDrills.all.any { it.ruleId == ruleId }
    }
}
