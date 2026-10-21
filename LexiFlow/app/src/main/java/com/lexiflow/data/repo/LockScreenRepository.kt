package com.lexiflow.data.repo

import com.lexiflow.core.active.Skill
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.exercise.ClozeBuilder
import com.lexiflow.core.exercise.Distractors
import com.lexiflow.core.exercise.QuestionRotation
import com.lexiflow.core.exercise.QuickQuestionType
import com.lexiflow.core.fsrs.Rating
import com.lexiflow.core.language.EvaluationTask
import com.lexiflow.core.language.GrammarDrill
import com.lexiflow.core.language.GrammarDrills
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

/**
 * Self-ratings after a correct answer. Notifications show only the first three (Android's action limit);
 * DONT_KNOW ("I guessed") is offered in the app.
 */
enum class SelfRating(val labelTr: String) {
    KNOW("Biliyorum"), HARD("Zorlandım"), CANT_USE("Tanıyorum ama kullanamıyorum"), DONT_KNOW("Bilmiyorum, tahmin ettim");

    companion object { val notificationEntries get() = entries.take(3) }
}

data class LockQuestion(
    val entity: LockScreenQuestionEntity,
    /** Null only for grammar questions, which are about a sentence rather than a word. */
    val card: CardDetail?,
    val example: ExampleEntity?,
    val options: List<String>,
    val showTurkish: Boolean,
    val grammar: GrammarDrill? = null,
)

data class LockAnswerResult(
    val correct: Boolean,
    val titleTr: String,
    val bodyTr: String,
    val canSelfRate: Boolean,
    /** The body quotes text the learner typed; it must never be shown on a lock screen. */
    val containsUserText: Boolean = false,
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
        // What the learner got wrong in today's autopilot session is asked first.
        val priority = db.priorityDao().nextOpen(clock.localDate(now))
        if (priority != null) {
            db.priorityDao().markServed(priority.id, now)
            if (priority.kind == "GRAMMAR") GrammarDrills.all.firstOrNull { it.ruleId == priority.ruleId }?.let { return createGrammarQuestion(it, s, now) }
        }
        val card = priority?.cardId?.let { db.cardDao().detail(it) } ?: pickCard(s) ?: return null
        val examples = db.wordDao().examplesForWord(card.card.wordId).filter { it.senseId == card.card.senseId }
        val example = ExerciseBuilder.pickExample(examples, now)
        val recall = active.state(card.card.id, Skill.RECALL).mastery
        val production = active.state(card.card.id, Skill.PRODUCTION).mastery
        var type = QuestionRotation.next(s.lockQuestionCounter, recall, production, example != null)
        if (!s.lockShowTurkish && (type == QuickQuestionType.EN_TO_TR || type == QuickQuestionType.TR_TO_EN)) {
            type = if (example != null) QuickQuestionType.CLOZE else QuickQuestionType.PRODUCTION
        }
        if (s.lockDifficulty == LockDifficulty.EASY && type == QuickQuestionType.PRODUCTION && example != null) type = QuickQuestionType.CLOZE
        // Examples whose answer is not a whole word in the sentence cannot become a cloze; use TR->EN instead.
        val cloze = example?.let { ClozeBuilder.build(it.en, it.answer) }
        if (type == QuickQuestionType.CLOZE && cloze == null) type = if (s.lockShowTurkish) QuickQuestionType.TR_TO_EN else QuickQuestionType.PRODUCTION
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
                val c = cloze!!
                prompt = c.prompt
                Distractors.options(c.answer, pool.map { ExerciseBuilder.inflectLike(it, c.answer, card.lemma) }, 3, now)
                    .let { options = it.first; correct = it.second }
            }
            QuickQuestionType.PRODUCTION -> prompt = "${card.lemma.replaceFirstChar { it.uppercase() }} kelimesiyle kendi cümleni kur."
            QuickQuestionType.GRAMMAR -> error("grammar questions are created from priority items")
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

    /** "Hangi cümle doğru?": the corrected sentence against the learner's typical mistake. */
    private suspend fun createGrammarQuestion(d: GrammarDrill, s: AppSettings, now: Long): LockQuestion {
        val options = listOf(d.correct, d.wrong).shuffled(kotlin.random.Random(now))
        val entity = LockScreenQuestionEntity(
            cardId = 0, type = QuickQuestionType.GRAMMAR.name, prompt = "Hangi cümle doğru? (${d.focusTr})",
            optionsJson = LexiJson.encodeToString(ListSerializer(String.serializer()), options), correctIndex = options.indexOf(d.correct), createdAt = now,
        )
        val id = dao.insert(entity)
        settings.update { it.copy(lockQuestionCounter = it.lockQuestionCounter + 1, lastLockQuestionAt = now) }
        return LockQuestion(entity.copy(id = id), null, null, options, s.lockShowTurkish, grammar = d)
    }

    suspend fun load(id: Long): LockQuestion? {
        val q = dao.get(id) ?: return null
        val options = runCatching { LexiJson.decodeFromString(ListSerializer(String.serializer()), q.optionsJson) }.getOrDefault(emptyList())
        if (q.type == QuickQuestionType.GRAMMAR.name) {
            val drill = GrammarDrills.all.firstOrNull { it.correct in options && it.wrong in options } ?: return null
            return LockQuestion(q, null, null, options, settings.current().lockShowTurkish, grammar = drill)
        }
        val card = db.cardDao().detail(q.cardId) ?: return null
        val ex = q.exampleId?.let { db.wordDao().example(it) }
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
        QuickQuestionType.PRODUCTION, QuickQuestionType.GRAMMAR -> Skill.PRODUCTION
    }

    /**
     * A hint that helps without giving the answer away: the English definition (the word itself masked), the word
     * type, and for English answers the first letter and length.
     */
    fun hint(q: LockQuestion): String {
        val type = QuickQuestionType.valueOf(q.entity.type)
        q.grammar?.let { return "Odak: ${it.focusTr}" }
        val card = q.card ?: return "Bu soru için ek ipucu yok."
        val lemma = card.lemma
        val lines = mutableListOf<String>()
        card.definitionEn?.takeIf { it.isNotBlank() }?.let { lines += "İngilizce anlamı: ${ClozeBuilder.maskLemma(it, lemma)}" }
        card.pos?.takeIf { it.isNotBlank() }?.let { lines += "Kelime türü: $it" }
        when (type) {
            QuickQuestionType.CLOZE, QuickQuestionType.TR_TO_EN -> {
                if (type == QuickQuestionType.CLOZE && q.showTurkish) lines += "Türkçesi: ${card.meaningTr}"
                val answer = q.entity.correctIndex?.let { q.options.getOrNull(it) } ?: lemma
                lines += "İlk harf: ${answer.first()}… (${answer.count { it.isLetter() }} harf)"
            }
            QuickQuestionType.EN_TO_TR -> q.example?.let { lines += "Örnek: ${it.en}" }
            QuickQuestionType.PRODUCTION -> {
                if (q.showTurkish) lines += "Anlamı: ${card.meaningTr}"
                q.example?.let { lines += "Örnek: ${it.en}" }
            }
            QuickQuestionType.GRAMMAR -> Unit
        }
        return lines.joinToString("\n").ifBlank { "Bu kelime için ek ipucu yok." }
    }

    /** "Bilmiyorum": counts as a wrong answer (Again when the card is due) and shows the answer with its explanation. */
    suspend fun answerDontKnow(id: Long, channel: String = "APP"): LockAnswerResult? {
        val q = load(id) ?: return null
        if (dao.markAnswered(id, clock.now(), null, "DONT_KNOW", channel) == 0) {
            val fresh = load(id) ?: return null
            return explain(fresh, false, alreadyAnswered = true)
        }
        val type = QuickQuestionType.valueOf(q.entity.type)
        q.card?.let { card ->
            active.record(card.card.id, skillFor(type), 0.0, hintUsed = 3, mode = "LOCKSCREEN_${type.name}",
                verdict = "WRONG", exampleId = q.example?.id, source = "LOCKSCREEN", awardXp = false)
            if (study.isDue(card.card.id)) study.answer(card.card.id, Rating.AGAIN, source = channel, mode = "LOCKSCREEN")
        }
        val r = explain(q, false)
        return r.copy(titleTr = "Sorun değil, öğrenelim. " + r.titleTr)
    }

    suspend fun answerChoice(id: Long, chosen: Int, channel: String = "NOTIFICATION", hintUsed: Boolean = false): LockAnswerResult? {
        val q = load(id) ?: return null
        val correct = chosen == q.entity.correctIndex
        // Atomic check-and-set: two quick taps (or notification + app) can never record the answer twice.
        if (dao.markAnswered(id, clock.now(), chosen, if (correct) "CORRECT" else "WRONG", channel) == 0) {
            val fresh = load(id) ?: return null
            return explain(fresh, fresh.entity.chosenIndex == fresh.entity.correctIndex, alreadyAnswered = true)
        }
        val type = QuickQuestionType.valueOf(q.entity.type)
        val card = q.card ?: return explain(q, correct) // grammar: nothing to schedule
        // When the card is due, the FSRS review (written on wrong answer / self-rating) carries the XP: no double reward.
        val due = study.isDue(card.card.id)
        active.record(card.card.id, skillFor(type), if (correct) 1.0 else 0.0, hintUsed = if (hintUsed) 3 else 2, mode = "LOCKSCREEN_${type.name}",
            verdict = if (correct) "CORRECT" else "WRONG", exampleId = q.example?.id, source = "LOCKSCREEN", awardXp = !due)
        if (!correct && due) study.answer(card.card.id, Rating.AGAIN, source = channel, mode = "LOCKSCREEN")
        return explain(q, correct)
    }

    suspend fun answerProduction(id: Long, text: String, channel: String = "NOTIFICATION"): LockAnswerResult? {
        val q = load(id) ?: return null
        val card = q.card ?: return null
        val eval = SentenceEvaluator.evaluate(text, EvaluationTask(listOf(TargetWord(card.lemma)), minWords = 4))
        val provisional = if (eval.targetUsage.first().used) "CORRECT" else "WRONG"
        if (dao.markAnswered(id, clock.now(), null, provisional, channel) == 0) return null
        val used = eval.targetUsage.first().used
        val score = if (!used) 0.1 else (eval.scores.grammar * 0.5 + eval.scores.naturalness * 0.3 + eval.scores.coherence * 0.2) / 100.0
        val ok = used && score >= 0.7
        dao.get(id)?.let { dao.update(it.copy(outcome = if (ok) "CORRECT" else "WRONG")) }
        active.record(card.card.id, Skill.PRODUCTION, score, 0, "LOCKSCREEN_PRODUCTION", if (ok) "CORRECT" else "PARTIAL",
            answerText = text, source = "LOCKSCREEN")
        val point = eval.keyPoints.firstOrNull()
        val body = buildString {
            if (!used) append("Cümlende \"${card.lemma}\" kelimesini bulamadım. ")
            if (point != null) append("${point.explanationTr} ")
            append("Önerilen: ${eval.corrected}")
            q.example?.let { append("\nÖrnek: ${it.en}") }
        }
        return LockAnswerResult(ok, if (ok) "Güzel cümle! ✓" else "Bir bakalım", body, canSelfRate = ok, containsUserText = true)
    }

    suspend fun selfRate(id: Long, rating: SelfRating, channel: String = "NOTIFICATION") {
        val q = dao.get(id) ?: return
        if (q.type == QuickQuestionType.GRAMMAR.name) return
        dao.update(q.copy(outcome = rating.name))
        if (rating == SelfRating.CANT_USE) active.flagCantUse(q.cardId, source = "LOCKSCREEN")
        if (rating == SelfRating.DONT_KNOW) {
            // A lucky guess is not knowledge: record a failed recall so the card comes back soon.
            val type = runCatching { QuickQuestionType.valueOf(q.type) }.getOrDefault(QuickQuestionType.EN_TO_TR)
            active.record(q.cardId, skillFor(type), 0.0, hintUsed = 3, mode = "LOCKSCREEN_${type.name}", verdict = "WRONG",
                exampleId = q.exampleId, source = "LOCKSCREEN", awardXp = false)
        }
        if (study.isDue(q.cardId)) {
            val fsrsRating = when (rating) { SelfRating.HARD -> Rating.HARD; SelfRating.DONT_KNOW -> Rating.AGAIN; else -> Rating.GOOD }
            study.answer(q.cardId, fsrsRating, source = channel, mode = "LOCKSCREEN")
        }
    }

    private fun explain(q: LockQuestion, correct: Boolean, alreadyAnswered: Boolean = false): LockAnswerResult {
        q.grammar?.let { d ->
            val why = GrammarDrills.explain(d)?.textTr() ?: d.focusTr
            val title = when { alreadyAnswered -> "Bu soru zaten cevaplandı"; correct -> "Doğru! ✓"; else -> "Doğrusu: ${d.correct}" }
            return LockAnswerResult(correct, title, why, canSelfRate = false)
        }
        val card = q.card!!
        val tr = if (q.showTurkish) " = ${card.meaningTr}" else ""
        val body = buildString {
            append("${card.lemma}$tr")
            card.definitionEn?.let { append("\n$it") }
            q.example?.let { ex ->
                append("\nÖrnek: ${ex.en}")
                if (q.showTurkish) ex.tr?.let { append("\n($it)") }
                ex.noteTr?.let { append("\n💡 $it") }
            }
        }
        val title = when {
            alreadyAnswered -> "Bu soru zaten cevaplandı"
            correct -> "Doğru! ✓"
            else -> "Doğru cevap: ${q.entity.correctIndex?.let { q.options.getOrNull(it) } ?: card.lemma}"
        }
        return LockAnswerResult(correct, title, body, canSelfRate = correct && !alreadyAnswered)
    }
}
