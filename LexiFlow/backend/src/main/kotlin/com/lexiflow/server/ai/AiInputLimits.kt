package com.lexiflow.server.ai

import com.lexiflow.core.ai.ChatRequest
import com.lexiflow.core.ai.ChatTurn
import com.lexiflow.core.ai.EnrichRequest
import com.lexiflow.core.ai.EvaluateRequest
import com.lexiflow.core.ai.LearnerProfile
import com.lexiflow.core.ai.StoryRequest
import com.lexiflow.core.ai.StoryTarget
import com.lexiflow.core.language.SentenceEvaluator
import com.lexiflow.server.ApiException

/**
 * Hard limits on what clients may send to the AI proxy (cost + abuse control). Requests that exceed them are
 * rejected with 400 (never silently truncated, except for whitespace/control-character sanitising).
 */
object AiInputLimits {
    const val MAX_TEXT = 4_000
    const val MAX_HISTORY_TURNS = 20
    const val MAX_TARGETS = 12
    const val MAX_WORD_LIST = 200
    const val MAX_WORD = 60
    const val MAX_SHORT = 200
    const val MAX_BODY_BYTES = 128 * 1024L

    private val cefrLevels = setOf("A1", "A2", "B1", "B2", "C1", "C2")
    private val modePattern = Regex("^[a-z_]{1,20}$")

    private fun fail(msg: String): Nothing = throw ApiException.badRequest(msg)

    private fun clean(s: String, max: Int, field: String): String {
        if (s.length > max) fail("$field is longer than $max characters")
        return SentenceEvaluator.sanitize(s, max)
    }

    private fun word(s: String, field: String): String {
        val w = clean(s, MAX_WORD, field)
        if (w.isBlank()) fail("$field must not be blank")
        return w
    }

    private fun words(list: List<String>, field: String, max: Int = MAX_WORD_LIST): List<String> {
        if (list.size > max) fail("$field has more than $max entries")
        return list.map { word(it, field) }
    }

    private fun cefr(s: String): String {
        val c = s.trim().uppercase()
        if (c !in cefrLevels) fail("cefr must be one of ${cefrLevels.joinToString()}")
        return c
    }

    fun story(r: StoryRequest): StoryRequest {
        if (r.targets.isEmpty()) fail("targets must not be empty")
        if (r.targets.size > MAX_TARGETS) fail("at most $MAX_TARGETS targets")
        if (r.wordCount !in 50..600) fail("wordCount must be between 50 and 600")
        return r.copy(
            targets = r.targets.map { StoryTarget(word(it.lemma, "targets.lemma"), clean(it.meaningTr, MAX_SHORT, "targets.meaningTr"), clean(it.senseHint, MAX_SHORT, "targets.senseHint")) },
            knownWords = words(r.knownWords, "knownWords"),
            cefr = cefr(r.cefr),
            theme = clean(r.theme, MAX_SHORT, "theme").ifBlank { "Daily life" },
            grammarFocus = r.grammarFocus?.let { clean(it, MAX_SHORT, "grammarFocus") }?.ifBlank { null },
            difficulty = clean(r.difficulty, 20, "difficulty").ifBlank { "normal" },
        )
    }

    fun evaluate(r: EvaluateRequest): EvaluateRequest {
        val text = clean(r.text, MAX_TEXT, "text")
        if (text.isBlank()) fail("text must not be blank")
        val mode = r.mode.trim().lowercase()
        if (!modePattern.matches(mode)) fail("mode must be a short identifier such as writing or speaking")
        return r.copy(
            text = text,
            targets = words(r.targets, "targets", MAX_TARGETS),
            mode = mode,
            cefr = cefr(r.cefr),
            prompt = r.prompt?.let { clean(it, 1_000, "prompt") }?.ifBlank { null },
        )
    }

    fun chat(r: ChatRequest): ChatRequest {
        if (r.history.size > MAX_HISTORY_TURNS) fail("history has more than $MAX_HISTORY_TURNS turns")
        val message = clean(r.userMessage, MAX_TEXT, "userMessage")
        if (message.isBlank()) fail("userMessage must not be blank")
        val history = r.history.map { t ->
            val role = t.role.trim().lowercase()
            if (role !in setOf("user", "coach", "assistant")) fail("history.role must be user, coach or assistant")
            ChatTurn(role, clean(t.text, MAX_TEXT, "history.text"))
        }
        if (history.sumOf { it.text.length } > MAX_TEXT * 5) fail("history is too long")
        val p = r.profile
        return r.copy(
            history = history,
            userMessage = message,
            topic = r.topic?.let { clean(it, MAX_SHORT, "topic") }?.ifBlank { null },
            profile = LearnerProfile(
                cefr = cefr(p.cefr),
                learnedWords = words(p.learnedWords, "profile.learnedWords"),
                weakWords = words(p.weakWords, "profile.weakWords"),
                dueWords = words(p.dueWords, "profile.dueWords"),
                recentErrors = p.recentErrors.also { if (it.size > 20) fail("profile.recentErrors has more than 20 entries") }
                    .map { clean(it, MAX_SHORT, "profile.recentErrors") },
            ),
        )
    }

    fun enrich(r: EnrichRequest): EnrichRequest = r.copy(
        lemma = word(r.lemma, "lemma"),
        meaningTr = clean(r.meaningTr, MAX_SHORT, "meaningTr"),
        cefr = cefr(r.cefr),
    )
}
