package com.lexiflow.data.repo

import com.lexiflow.core.ai.AiFeedback
import com.lexiflow.core.ai.EvaluateRequest
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.data.db.AiContentFlagEntity
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.db.ExampleEntity
import com.lexiflow.data.remote.BackendClient
import com.lexiflow.data.remote.RemoteError
import com.lexiflow.data.settings.SettingsRepository
import com.lexiflow.util.Clock

/** Result of a "Yanlış/yetersiz" mark: what replaced the content, and a note for the learner. */
data class FlagOutcome(val noticeTr: String, val newStoryId: Long? = null, val feedback: AiFeedback? = null)

/**
 * "Yanlış/yetersiz" button on AI content (explanations, stories, sentences). Every mark is stored in
 * ai_content_flags; the flagged copy is removed from the local cache and a new one is generated when the AI server
 * is reachable. Without the server the content is only removed, and the learner is told so.
 */
class AiContentFeedbackRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val backend: BackendClient,
    private val stories: StoryRepository,
    private val enrichment: EnrichmentRepository,
    private val clock: Clock,
) {
    private val dao = db.aiFlagDao()

    private suspend fun flag(kind: String, key: String, text: String): Long =
        dao.insert(AiContentFlagEntity(kind = kind, contentKey = key, contentText = text.take(4000), at = clock.now()))

    /** Deletes the story and writes a new one with the same words and theme. */
    suspend fun flagStory(storyId: Long): FlagOutcome {
        val loaded = stories.load(storyId) ?: return FlagOutcome("Hikâye bulunamadı.")
        val id = flag("STORY", "story:$storyId", loaded.story.title + "\n" + loaded.story.paragraphs.joinToString("\n"))
        val e = loaded.entity
        val generated = stories.generate(
            StoryOptions(theme = e.theme, cefr = e.cefr, forgettingCurve = e.forgettingCurve, chosenCardIds = loaded.words.map { it.cardId },
                targetCount = loaded.words.size.coerceAtLeast(1)),
        )
        stories.delete(storyId)
        dao.markRegenerated(id)
        return FlagOutcome(generated.noticeTr ?: "İşaretin kaydedildi; eski hikâye silindi ve aynı kelimelerle yenisi yazıldı.", newStoryId = generated.id)
    }

    /** Removes an example sentence from the card; with the AI server the word gets freshly generated sentences. */
    suspend fun flagExample(example: ExampleEntity): FlagOutcome {
        val id = flag("SENTENCE", "example:${example.id}", example.en + (example.tr?.let { "\n$it" } ?: ""))
        db.wordDao().deleteExample(example.id)
        if (!settings.current().aiAvailable) return FlagOutcome("İşaretin kaydedildi ve cümle karttan kaldırıldı. Yeni cümle üretmek için AI sunucusu gerekir.")
        return try {
            val added = enrichment.enrich(example.wordId)
            dao.markRegenerated(id)
            FlagOutcome("İşaretin kaydedildi; cümle kaldırıldı ve AI $added yeni cümle üretti.")
        } catch (e: RemoteError) {
            FlagOutcome("İşaretin kaydedildi ve cümle kaldırıldı. Yeniden üretim şu an olmadı: ${e.messageTr}")
        }
    }

    /**
     * AI feedback on an answer: records the mark and asks for a new explanation. Returns the new feedback, or null
     * with a note when the server is not reachable (the old feedback is dropped either way).
     */
    suspend fun flagFeedback(old: AiFeedback, request: EvaluateRequest): FlagOutcome {
        val id = flag("FEEDBACK", "feedback:${request.text.hashCode()}", LexiJson.encodeToString(AiFeedback.serializer(), old))
        return try {
            val fresh = backend.evaluate(request)
            dao.markRegenerated(id)
            FlagOutcome("İşaretin kaydedildi; açıklama yeniden üretildi.", feedback = fresh)
        } catch (e: RemoteError) {
            FlagOutcome("İşaretin kaydedildi ve açıklama kaldırıldı. Yeniden üretilemedi: ${e.messageTr}")
        }
    }

    /** Speaking turn feedback: replaces the stored AI part of the turn (or removes it, keeping the rule-based check). */
    suspend fun flagSpeakingFeedback(turnId: Long, text: String, feedbackJson: String, topic: String): FlagOutcome {
        val current = runCatching { LexiJson.decodeFromString(TurnFeedback.serializer(), feedbackJson) }.getOrNull() ?: return FlagOutcome("Geri bildirim okunamadı.")
        val id = flag("FEEDBACK", "turn:$turnId", feedbackJson)
        val fresh = if (settings.current().aiAvailable) {
            runCatching { backend.evaluate(EvaluateRequest(text, current.targetsUsed, "speaking", settings.current().cefr, topic)) }.getOrNull()
        } else null
        val updated = current.copy(ai = fresh, engine = if (fresh != null) "AI" else "OFFLINE_COACH")
        db.speakingDao().updateTurnFeedback(turnId, LexiJson.encodeToString(TurnFeedback.serializer(), updated))
        if (fresh != null) dao.markRegenerated(id)
        return FlagOutcome(if (fresh != null) "İşaretin kaydedildi; AI açıklaması yeniden üretildi." else "İşaretin kaydedildi; AI açıklaması kaldırıldı, kural tabanlı değerlendirme gösteriliyor.")
    }

    fun observeRecent() = dao.observeRecent()

    companion object {
        /** Sentences LexiFlow wrote or generated (not the learner's own or imported ones) can be marked. */
        fun canFlag(example: ExampleEntity) = example.origin !in setOf("USER", "IMPORT")
    }
}
