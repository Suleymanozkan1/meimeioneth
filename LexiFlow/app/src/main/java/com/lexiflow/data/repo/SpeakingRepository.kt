package com.lexiflow.data.repo

import com.lexiflow.core.active.Skill
import com.lexiflow.core.ai.AiFeedback
import com.lexiflow.core.ai.ChatRequest
import com.lexiflow.core.ai.ChatTurn
import com.lexiflow.core.ai.LearnerProfile
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.gamification.XpReason
import com.lexiflow.core.language.Evaluation
import com.lexiflow.core.language.EvaluationTask
import com.lexiflow.core.language.Morphology
import com.lexiflow.core.language.SentenceEvaluator
import com.lexiflow.core.language.TargetWord
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.db.CardDetail
import com.lexiflow.data.db.SpeakingSessionEntity
import com.lexiflow.data.db.SpeakingTurnEntity
import com.lexiflow.data.remote.BackendClient
import com.lexiflow.data.remote.RemoteError
import com.lexiflow.data.settings.SettingsRepository
import com.lexiflow.util.Clock
import kotlinx.serialization.Serializable

@Serializable
data class TurnFeedback(
    val evaluation: Evaluation,
    val ai: AiFeedback? = null,
    val targetsUsed: List<String> = emptyList(),
    val clarity: Int,
    val engine: String,
)

data class CoachReply(val coachText: String, val feedback: TurnFeedback, val noticeTr: String?)

/**
 * Speaking coach. With a backend it uses the AI chat endpoint; without one an offline coach asks
 * prompts that weave in the learner's weak/due words, and feedback comes from the deterministic evaluator.
 * Voice and typed answers go through exactly the same evaluation.
 */
class SpeakingRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val backend: BackendClient,
    private val active: ActiveRecallRepository,
    private val gamification: GamificationRepository,
    private val clock: Clock,
    private val mistakes: MistakeRepository? = null,
) {
    private val dao = db.speakingDao()

    fun observeTurns(sessionId: Long) = dao.observeTurns(sessionId)

    val topics = listOf("Daily life", "Work", "Travel", "Relationships", "Technology", "Decisions", "Plans for the future", "Food", "Health")

    suspend fun targetWords(limit: Int = 4): List<CardDetail> {
        val flagged = active.productionQueue(limit, onlyDue = false).map { it.card }
        if (flagged.size >= limit) return flagged
        return (flagged + db.cardDao().dueReviews(clock.now(), limit) + db.cardDao().randomStudied(limit)).distinctBy { it.card.id }.take(limit)
    }

    suspend fun start(topic: String): Pair<Long, String> {
        val s = settings.current()
        val id = dao.insertSession(SpeakingSessionEntity(topic = topic, startedAt = clock.now(), engine = if (s.aiAvailable) "AI" else "OFFLINE_COACH"))
        val targets = targetWords()
        val opener = OfflineCoach.opener(topic, targets.map { it.lemma })
        dao.insertTurn(SpeakingTurnEntity(sessionId = id, role = "coach", text = opener, at = clock.now()))
        return id to opener
    }

    suspend fun answer(sessionId: Long, text: String, voice: Boolean, speakingMs: Long, topic: String): CoachReply {
        val s = settings.current()
        val targets = targetWords()
        val lemmas = targets.map { it.lemma }
        val clean = SentenceEvaluator.sanitize(text, 2000)
        val eval = SentenceEvaluator.evaluate(clean, EvaluationTask(lemmas.map { TargetWord(it) }, requireAllTargets = false, minWords = 3))
        val used = eval.targetUsage.filter { it.used }.map { it.lemma }
        val history = dao.turns(sessionId)
        var notice: String? = null
        var ai: AiFeedback? = null
        var coachText: String
        var engine = "OFFLINE_COACH"
        if (s.aiAvailable) {
            try {
                val r = backend.chat(
                    ChatRequest(
                        history = history.takeLast(12).map { ChatTurn(if (it.role == "coach") "assistant" else "user", it.text) },
                        profile = LearnerProfile(s.cefr, learnedWords = db.cardDao().randomStudied(30).map { it.lemma },
                            weakWords = lemmas, dueWords = db.cardDao().dueReviews(clock.now(), 10).map { it.lemma },
                            recentErrors = eval.keyPoints.map { it.ruleId }),
                        userMessage = clean, topic = topic,
                    ),
                )
                coachText = r.reply
                ai = r.feedback
                engine = "AI"
            } catch (e: RemoteError) {
                notice = e.messageTr + " Çevrim dışı koç devam ediyor."
                coachText = OfflineCoach.followUp(topic, clean, used, lemmas, history.size)
            }
        } else {
            coachText = OfflineCoach.followUp(topic, clean, used, lemmas, history.size)
        }
        val clarity = ((eval.scores.coherence + eval.scores.grammar) / 2)
        val feedback = TurnFeedback(eval, ai, used, clarity, engine)
        val now = clock.now()
        dao.insertTurn(SpeakingTurnEntity(sessionId = sessionId, role = "learner", text = clean, inputMode = if (voice) "VOICE" else "TEXT",
            feedbackJson = LexiJson.encodeToString(TurnFeedback.serializer(), feedback), at = now, speakingMs = speakingMs))
        dao.insertTurn(SpeakingTurnEntity(sessionId = sessionId, role = "coach", text = coachText, at = now + 1))
        targets.filter { it.lemma in used }.forEach { c ->
            val score = (eval.scores.grammar * 0.5 + eval.scores.naturalness * 0.3 + eval.scores.coherence * 0.2) / 100.0
            active.record(c.card.id, if (voice) Skill.SPEAKING else Skill.PRODUCTION, score, 0, if (voice) "AI_SPEAKING" else "TEXT_SPEAKING",
                if (score >= 0.7) "CORRECT" else "PARTIAL", answerText = clean, source = "SPEAKING")
        }
        // Certain grammar mistakes of the turn go to the error log.
        mistakes?.let { log -> com.lexiflow.domain.MistakeClassifier.speakingTurn(clean, eval, voice).forEach { log.record(it, source = "SPEAKING") } }
        gamification.award(XpReason.SPEAKING_TURN, sessionId, ActivityDelta(speakingMs = speakingMs))
        return CoachReply(coachText, feedback, notice)
    }

    suspend fun end(sessionId: Long) {
        db.speakingDao().observeSessions()
    }

    fun decodeFeedback(json: String?): TurnFeedback? = json?.let { runCatching { LexiJson.decodeFromString(TurnFeedback.serializer(), it) }.getOrNull() }

    suspend fun deleteAll() = dao.deleteAll()
}

/** Rule-based coach used offline. It asks open questions and invites (never forces) the target words. */
object OfflineCoach {
    private val openers = mapOf(
        "Daily life" to "Tell me about a normal day for you. What do you usually do in the morning?",
        "Work" to "What do you do at work or school? What is the most challenging part?",
        "Travel" to "Tell me about a trip you remember well. What happened?",
        "Relationships" to "Tell me about a friend who is important to you. How did you meet?",
        "Technology" to "Which app or device do you use the most? Why?",
        "Decisions" to "Tell me about a difficult decision you made recently.",
        "Plans for the future" to "What would you like to achieve in the next five years?",
        "Food" to "What is a meal you love to cook or eat? Describe it.",
        "Health" to "What do you do to stay healthy? Is it easy for you?",
    )

    private val followUps = listOf(
        "Interesting! Why do you think that happened?",
        "How did you feel about it?",
        "What would you do differently next time?",
        "Can you give me an example?",
        "What happened after that?",
        "Do you think other people feel the same way? Why?",
    )

    fun opener(topic: String, targets: List<String>): String {
        val q = openers[topic] ?: openers.getValue("Daily life")
        return if (targets.isEmpty()) q else "$q\n(If it feels natural, try to use: ${targets.joinToString(", ")})"
    }

    fun followUp(topic: String, answer: String, used: List<String>, targets: List<String>, turn: Int): String {
        val ack = when {
            Morphology.tokens(answer).size < 5 -> "Could you tell me a bit more?"
            used.isNotEmpty() -> "Nice use of \"${used.first()}\"!"
            else -> "Thanks for sharing."
        }
        val remaining = targets.filter { it !in used }
        val q = followUps[turn / 2 % followUps.size]
        val invite = if (remaining.isNotEmpty() && turn % 4 == 2) " Maybe you can use \"${remaining.first()}\" in your answer." else ""
        return "$ack $q$invite"
    }
}
