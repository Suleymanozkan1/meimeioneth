package com.lexiflow.data.repo

import com.lexiflow.core.active.Skill
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.exercise.ClozeBuilder
import com.lexiflow.core.exercise.Distractors
import com.lexiflow.core.exercise.QuestionRotation
import com.lexiflow.core.exercise.QuickQuestionType
import com.lexiflow.core.fsrs.Rating
import com.lexiflow.core.language.EvaluationTask
import com.lexiflow.core.language.SentenceEvaluator
import com.lexiflow.core.language.TargetWord
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.db.CardDetail
import com.lexiflow.data.db.ExampleEntity
import com.lexiflow.data.db.LockScreenQuestionEntity
import com.lexiflow.data.settings.AppSettings
import com.lexiflow.data.settings.LockDifficulty
import com.lexiflow.data.settings.LockPool
import com.lexiflow.data.settings.SettingsRepository
import com.lexiflow.domain.ExerciseBuilder
import com.lexiflow.util.Clock
import com.lexiflow.util.isQuietHour
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

enum class SelfRating(val labelTr: String) { KNOW("Biliyorum"), HARD("Zorlandım"), CANT_USE("Tanıyorum ama kullanamıyorum") }

data class LockQuestion(
    val entity: LockScreenQuestionEntity,
    val card: CardDetail,
    val example: ExampleEntity?,
    val options: List<String>,
    val showTurkish: Boolean,
)

data class LockAnswerResult(
    val correct: Boolean,
    val titleTr: String,
    val bodyTr: String,
    val canSelfRate: Boolean,
)

/**
 * Lock-screen questions. Answers are written to the same histories as in-app study:
 * - The quick question itself is an active-recall attempt (practice_logs, source LOCKSCREEN).
 * - An FSRS review is written only when the card is actually due, so lock-screen use never causes early
 *   reviews: a wrong answer = Again; "Biliyorum" = Good; "Zorlandım" = Hard; "Tanıyorum ama kullanamıyorum"
 *   = Good for recognition + the card enters the active-production queue.
 */
class LockScreenRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val study: StudyRepository,
    private val active: ActiveRecallRepository,
    private val clock: Clock,
) {
    private val dao = db.lockScreenDao()

    fun observeRecent() = dao.observeRecent()

    /** Anti-spam gate: enabled, outside quiet hours, under the daily cap and spaced by the interval. */
    suspend fun shouldPostNow(s: AppSettings, force: Boolean = false): Boolean {
        if (force) return true
        if (!s.lockScreenEnabled) return false
        if (isQuietHour(clock.hourOfDay(), s.quietStartHour, s.quietEndHour)) return false
        if (dao.countSince(clock.startOfToday()) >= s.lockQuestionsPerDay) return false
        return clock.now() - s.lastLockQuestionAt >= s.lockIntervalMinutes * 60_000L
    }

    suspend fun createQuestion(): LockQuestion? {
        val s = settings.current()
        val now = clock.now()
        val card = pickCard(s) ?: return null
        val examples = db.wordDao().examplesForWord(card.card.wordId).filter { it.senseId == card.card.senseId }
        val example = ExerciseBuilder.pickExample(examples, now)
        val recall = active.state(card.card.id, Skill.RECALL).mastery
        val production = active.state(card.card.id, Skill.PRODUCTION).mastery
        var type = QuestionRotation.next(s.lockQuestionCounter, recall, production, example != null)
        if (!s.lockShowTurkish && (type == QuickQuestionType.EN_TO_TR || type == QuickQuestionType.TR_TO_EN)) {
            type = if (example != null) QuickQuestionType.CLOZE else QuickQuestionType.PRODUCTION
        }
        if (s.lockDifficulty == LockDifficulty.EASY && type == QuickQuestionType.PRODUCTION && example != null) type = QuickQuestionType.CLOZE
        val pool = when (type) {
            QuickQuestionType.EN_TO_TR -> db.wordDao().randomMeanings(10)
            else -> db.wordDao().randomLemmas(10)
        }
        val prompt: String
        var options: List<String> = emptyList()
        var correct: Int? = null
        when (type) {
            QuickQuestionType.EN_TO_TR -> {
                prompt = "${card.lemma} — Türkçesi nedir?"
                Distractors.options(card.meaningTr, pool, 3, now).let { options = it.first; correct = it.second }
            }
            QuickQuestionType.TR_TO_EN -> {
                prompt = "${card.meaningTr.replaceFirstChar { it.uppercase() }} — İngilizcesi nedir?"
                Distractors.options(card.lemma, pool, 3, now).let { options = it.first; correct = it.second }
            }
            QuickQuestionType.CLOZE -> {
                val cloze = ClozeBuilder.build(example!!.en, example.answer)!!
                prompt = cloze.prompt
                Distractors.options(cloze.answer, pool.map { ExerciseBuilder.inflectLike(it, cloze.answer, card.lemma) }, 3, now)
                    .let { options = it.first; correct = it.second }
            }
            QuickQuestionType.PRODUCTION -> prompt = "${card.lemma.replaceFirstChar { it.uppercase() }} kelimesiyle kendi cümleni kur."
        }
        val entity = LockScreenQuestionEntity(
            cardId = card.card.id, exampleId = example?.id, type = type.name, prompt = prompt,
            optionsJson = LexiJson.encodeToString(ListSerializer(String.serializer()), options), correctIndex = correct, createdAt = now,
        )
        val id = dao.insert(entity)
        example?.let { db.wordDao().markShown(it.id, now) }
        settings.update { it.copy(lockQuestionCounter = it.lockQuestionCounter + 1, lastLockQuestionAt = now) }
        return LockQuestion(entity.copy(id = id), card, example, options, s.lockShowTurkish)
    }

    suspend fun load(id: Long): LockQuestion? {
        val q = dao.get(id) ?: return null
        val card = db.cardDao().detail(q.cardId) ?: return null
        val ex = q.exampleId?.let { db.wordDao().example(it) }
        val options = runCatching { LexiJson.decodeFromString(ListSerializer(String.serializer()), q.optionsJson) }.getOrDefault(emptyList())
        return LockQuestion(q, card, ex, options, settings.current().lockShowTurkish)
    }

    private suspend fun pickCard(s: AppSettings): CardDetail? {
        val now = clock.now()
        val due = db.cardDao().dueReviews(now, 20)
        val candidates = when {
            due.isNotEmpty() -> due
            s.lockPool == LockPool.MIXED -> db.cardDao().randomAny(20)
            else -> db.cardDao().randomStudied(20).ifEmpty { db.cardDao().newCards(5) }
        }
        val lastCard = dao.latest()?.cardId
        val filtered = candidates.filter { it.card.id != lastCard }.ifEmpty { candidates }
        return when (s.lockDifficulty) {
            LockDifficulty.HARD -> filtered.maxByOrNull { it.card.difficulty }
            LockDifficulty.EASY -> filtered.minByOrNull { it.card.difficulty }
            LockDifficulty.NORMAL -> filtered.firstOrNull()
        }
    }

    private fun skillFor(type: QuickQuestionType) = when (type) {
        QuickQuestionType.EN_TO_TR -> Skill.MEANING_RECALL
        QuickQuestionType.TR_TO_EN -> Skill.RECALL
        QuickQuestionType.CLOZE -> Skill.CONTEXT
        QuickQuestionType.PRODUCTION -> Skill.PRODUCTION
    }

    suspend fun answerChoice(id: Long, chosen: Int, channel: String = "NOTIFICATION"): LockAnswerResult? {
        val q = load(id) ?: return null
        if (q.entity.answeredAt != null) return explain(q, q.entity.chosenIndex == q.entity.correctIndex, alreadyAnswered = true)
        val correct = chosen == q.entity.correctIndex
        val type = QuickQuestionType.valueOf(q.entity.type)
        dao.update(q.entity.copy(answeredAt = clock.now(), chosenIndex = chosen, outcome = if (correct) "CORRECT" else "WRONG", channel = channel))
        active.record(q.card.card.id, skillFor(type), if (correct) 1.0 else 0.0, hintUsed = 2, mode = "LOCKSCREEN_${type.name}",
            verdict = if (correct) "CORRECT" else "WRONG", exampleId = q.example?.id, source = "LOCKSCREEN")
        if (!correct && study.isDue(q.card.card.id)) study.answer(q.card.card.id, Rating.AGAIN, source = channel, mode = "LOCKSCREEN")
        return explain(q, correct)
    }

    suspend fun answerProduction(id: Long, text: String, channel: String = "NOTIFICATION"): LockAnswerResult? {
        val q = load(id) ?: return null
        val eval = SentenceEvaluator.evaluate(text, EvaluationTask(listOf(TargetWord(q.card.lemma)), minWords = 4))
        val used = eval.targetUsage.first().used
        val score = if (!used) 0.1 else (eval.scores.grammar * 0.5 + eval.scores.naturalness * 0.3 + eval.scores.coherence * 0.2) / 100.0
        val ok = used && score >= 0.7
        dao.update(q.entity.copy(answeredAt = clock.now(), outcome = if (ok) "CORRECT" else "WRONG", channel = channel))
        active.record(q.card.card.id, Skill.PRODUCTION, score, 0, "LOCKSCREEN_PRODUCTION", if (ok) "CORRECT" else "PARTIAL",
            answerText = text, source = "LOCKSCREEN")
        val point = eval.keyPoints.firstOrNull()
        val body = buildString {
            if (!used) append("Cümlende \"${q.card.lemma}\" kelimesini bulamadım. ")
            if (point != null) append("${point.explanationTr} ")
            append("Önerilen: ${eval.corrected}")
            q.example?.let { append("\nÖrnek: ${it.en}") }
        }
        return LockAnswerResult(ok, if (ok) "Güzel cümle! ✓" else "Bir bakalım", body, canSelfRate = ok)
    }

    suspend fun selfRate(id: Long, rating: SelfRating, channel: String = "NOTIFICATION") {
        val q = dao.get(id) ?: return
        dao.update(q.copy(outcome = rating.name))
        if (rating == SelfRating.CANT_USE) active.flagCantUse(q.cardId, source = "LOCKSCREEN")
        if (study.isDue(q.cardId)) {
            val fsrsRating = if (rating == SelfRating.HARD) Rating.HARD else Rating.GOOD
            study.answer(q.cardId, fsrsRating, source = channel, mode = "LOCKSCREEN")
        }
    }

    private fun explain(q: LockQuestion, correct: Boolean, alreadyAnswered: Boolean = false): LockAnswerResult {
        val tr = if (q.showTurkish) " = ${q.card.meaningTr}" else ""
        val body = buildString {
            append("${q.card.lemma}$tr")
            q.card.definitionEn?.let { append("\n$it") }
            q.example?.let { ex ->
                append("\nÖrnek: ${ex.en}")
                if (q.showTurkish) ex.tr?.let { append("\n($it)") }
                ex.noteTr?.let { append("\n💡 $it") }
            }
        }
        val title = when {
            alreadyAnswered -> "Bu soru zaten cevaplandı"
            correct -> "Doğru! ✓"
            else -> "Doğru cevap: ${q.entity.correctIndex?.let { q.options.getOrNull(it) } ?: q.card.lemma}"
        }
        return LockAnswerResult(correct, title, body, canSelfRate = correct && !alreadyAnswered)
    }
}
