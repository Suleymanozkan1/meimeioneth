package com.lexiflow.data.repo

import com.lexiflow.core.active.Skill
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.ai.QuestionType
import com.lexiflow.core.ai.StoryQuestion
import com.lexiflow.core.ai.StoryRequest
import com.lexiflow.core.ai.StoryResponse
import com.lexiflow.core.ai.StoryTarget
import com.lexiflow.core.gamification.XpReason
import com.lexiflow.core.importer.DuplicateDetector
import com.lexiflow.core.story.ComposerTarget
import com.lexiflow.core.story.OfflineStoryComposer
import com.lexiflow.core.story.StoryCandidate
import com.lexiflow.core.story.StoryPlanner
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.db.StoryAttemptEntity
import com.lexiflow.data.db.StoryEntity
import com.lexiflow.data.db.StoryWordEntity
import com.lexiflow.data.remote.BackendClient
import com.lexiflow.data.remote.RemoteError
import com.lexiflow.data.settings.SettingsRepository
import com.lexiflow.domain.ExerciseBuilder
import com.lexiflow.util.Clock

data class StoryOptions(
    val theme: String = "Daily life",
    val targetCount: Int = 5,
    val wordCount: Int = 180,
    val cefr: String = "B1",
    val grammarFocus: String? = null,
    val difficulty: String = "normal",
    val forgettingCurve: Boolean = true,
    val chosenCardIds: List<Long> = emptyList(),
)

data class GeneratedStory(val id: Long, val noticeTr: String?)

data class LoadedStory(val entity: StoryEntity, val story: StoryResponse, val words: List<StoryWordEntity>)

/**
 * AI stories and forgetting-curve stories. Reading a story does not create FSRS reviews (no double counting).
 * Story questions update active-use skills only; see StoryPlanner for how targets are chosen.
 */
class StoryRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val backend: BackendClient,
    private val study: StudyRepository,
    private val active: ActiveRecallRepository,
    private val gamification: GamificationRepository,
    private val clock: Clock,
) {
    private val dao = db.storyDao()

    fun observeStories() = dao.observeAll()

    suspend fun candidates(): List<StoryCandidate> {
        val now = clock.now()
        return db.cardDao().allStudied().map { c ->
            val ex = db.wordDao().examplesForWord(c.card.wordId).filter { it.senseId == c.card.senseId }
            val pick = ExerciseBuilder.pickExample(ex, now)
            val prod = active.state(c.card.id, Skill.PRODUCTION)
            StoryCandidate(c.card.id, c.lemma, c.meaningTr, study.retrievability(c.card), c.card.dueAt, c.card.lastReviewAt,
                c.card.lapses, c.card.difficulty, prod.mastery, prod.flaggedCantUse, pick?.en, pick?.answer)
        }
    }

    suspend fun shouldRecommend(): Boolean = StoryPlanner.shouldRecommend(candidates(), clock.now())

    suspend fun generate(options: StoryOptions): GeneratedStory {
        val now = clock.now()
        val all = candidates()
        val targets = if (options.chosenCardIds.isNotEmpty()) all.filter { it.cardId in options.chosenCardIds }
        else if (options.forgettingCurve) StoryPlanner.select(all, options.targetCount, now)
        else all.shuffled().take(options.targetCount)
        if (targets.isEmpty()) throw IllegalStateException("Hikâye için önce birkaç kelimeyi tekrar etmelisin (en az 1 tekrar edilmiş kart gerekir).")

        val s = settings.current()
        var notice: String? = null
        var origin = "AI"
        val story: StoryResponse = if (s.aiAvailable) {
            try {
                backend.story(
                    StoryRequest(
                        targets = targets.map { StoryTarget(it.lemma, it.meaningTr) },
                        knownWords = all.filter { (it.retrievability ?: 0.0) > 0.9 }.map { it.lemma }.take(30),
                        cefr = options.cefr, wordCount = options.wordCount, theme = options.theme,
                        grammarFocus = options.grammarFocus, difficulty = options.difficulty,
                    ),
                )
            } catch (e: RemoteError) {
                notice = e.messageTr + " Şablon hikâye oluşturuldu."
                origin = "OFFLINE_TEMPLATE"
                offline(options, targets)
            }
        } else {
            origin = "OFFLINE_TEMPLATE"
            notice = "AI bağlantısı yok: kartlarındaki insan yazımı örnek cümlelerden şablon hikâye oluşturuldu."
            offline(options, targets)
        }
        val id = dao.insert(
            StoryEntity(title = story.title, theme = options.theme, cefr = options.cefr, bodyJson = LexiJson.encodeToString(StoryResponse.serializer(), story),
                origin = origin, forgettingCurve = options.forgettingCurve, createdAt = now),
        )
        val body = story.paragraphs.joinToString("\n")
        dao.insertWords(targets.map { t ->
            StoryWordEntity(id, t.cardId, t.lemma, OfflineStoryComposer.countUses(body, t.lemma),
                story.targetOccurrences.firstOrNull { it.lemma.equals(t.lemma, true) }?.senseHint.orEmpty())
        })
        return GeneratedStory(id, notice)
    }

    private fun offline(options: StoryOptions, targets: List<StoryCandidate>): StoryResponse {
        val usable = targets.filter { it.example != null && it.exampleAnswer != null }
            .map { ComposerTarget(it.lemma, it.meaningTr, it.example!!, it.exampleAnswer!!) }
        if (usable.isEmpty()) throw IllegalStateException("Seçilen kelimelerin örnek cümlesi yok; şablon hikâye kurulamadı.")
        return OfflineStoryComposer.compose(options.theme, usable, clock.now())
    }

    suspend fun load(id: Long): LoadedStory? {
        val e = dao.get(id) ?: return null
        val story = runCatching { LexiJson.decodeFromString(StoryResponse.serializer(), e.bodyJson) }.getOrNull() ?: return null
        return LoadedStory(e, story, dao.words(id))
    }

    /** Records a story question against the matching card's active skill. */
    suspend fun recordQuestion(story: LoadedStory, q: StoryQuestion, score: Double, answer: String?) {
        val lemma = q.targetLemma ?: return
        val word = story.words.firstOrNull { DuplicateDetector.normalizeWord(it.lemma) == DuplicateDetector.normalizeWord(lemma) } ?: return
        val skill = when (q.type) {
            QuestionType.MEANING_IN_CONTEXT, QuestionType.MULTIPLE_CHOICE -> Skill.MEANING_RECALL
            QuestionType.CLOZE -> Skill.CONTEXT
            QuestionType.OPEN_SENTENCE, QuestionType.RETELL -> Skill.PRODUCTION
            QuestionType.GRAMMAR -> Skill.CONTEXT
        }
        active.record(word.cardId, skill, score, hintUsed = 0, mode = "STORY_${q.type.name}", verdict = if (score >= 0.7) "CORRECT" else "WRONG",
            answerText = answer, source = "STORY")
    }

    suspend fun finish(id: Long, correct: Int, total: Int) {
        val e = dao.get(id) ?: return
        dao.insertAttempt(StoryAttemptEntity(storyId = id, at = clock.now(), correct = correct, total = total))
        val score = if (total == 0) 0 else correct * 100 / total
        val first = e.completedAt == null
        dao.update(e.copy(completedAt = e.completedAt ?: clock.now(), bestScore = maxOf(e.bestScore ?: 0, score)))
        if (first) gamification.award(XpReason.STORY_COMPLETED, id)
    }

    suspend fun delete(id: Long) = dao.delete(id)

    val themes: List<String> get() = OfflineStoryComposer.themes
}
