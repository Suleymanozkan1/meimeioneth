package com.lexiflow.core.ai

import com.lexiflow.core.language.Morphology
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire contracts between the Android app and the LexiFlow backend AI proxy. The backend asks the model
 * for exactly these JSON shapes and validates them with [AiValidation] before returning; the app validates
 * again (defence in depth) and falls back to local content when validation fails.
 */
val LexiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    classDiscriminator = "type"
}

@Serializable
data class StoryTarget(val lemma: String, val meaningTr: String = "", val senseHint: String = "")

@Serializable
data class StoryRequest(
    val targets: List<StoryTarget>,
    /** Well-known words that may appear freely to support comprehension. */
    val knownWords: List<String> = emptyList(),
    val cefr: String = "B1",
    val wordCount: Int = 180,
    val theme: String = "Daily life",
    val grammarFocus: String? = null,
    val difficulty: String = "normal",
)

@Serializable
enum class QuestionType { MULTIPLE_CHOICE, MEANING_IN_CONTEXT, CLOZE, OPEN_SENTENCE, RETELL, GRAMMAR }

@Serializable
data class StoryQuestion(
    val type: QuestionType,
    val prompt: String,
    val options: List<String> = emptyList(),
    val answerIndex: Int? = null,
    val answer: String? = null,
    val targetLemma: String? = null,
)

@Serializable
data class TargetOccurrence(val lemma: String, val count: Int, val senseHint: String = "")

@Serializable
data class StoryResponse(
    val title: String,
    val paragraphs: List<String>,
    val targetOccurrences: List<TargetOccurrence> = emptyList(),
    val questions: List<StoryQuestion> = emptyList(),
    val grammarNoteTr: String? = null,
)

@Serializable
data class Correction(val original: String, val corrected: String, val explanationTr: String, val category: String = "grammar")

@Serializable
data class TargetWordFeedback(val lemma: String, val correct: Boolean, val commentTr: String)

@Serializable
data class AiFeedback(
    /** Short Turkish paraphrase of what the learner meant. */
    val intendedMeaningTr: String,
    /** At most three important corrections. */
    val corrections: List<Correction> = emptyList(),
    val naturalAlternative: String? = null,
    val alternatives: List<String> = emptyList(),
    val targetWordFeedback: List<TargetWordFeedback> = emptyList(),
    val encouragementTr: String? = null,
)

@Serializable
data class EvaluateRequest(
    val text: String,
    val targets: List<String> = emptyList(),
    val mode: String = "writing",
    val cefr: String = "B1",
    val prompt: String? = null,
)

@Serializable
data class ChatTurn(val role: String, val text: String)

@Serializable
data class LearnerProfile(
    val cefr: String = "B1",
    val learnedWords: List<String> = emptyList(),
    val weakWords: List<String> = emptyList(),
    val dueWords: List<String> = emptyList(),
    val recentErrors: List<String> = emptyList(),
)

@Serializable
data class ChatRequest(
    val history: List<ChatTurn>,
    val profile: LearnerProfile,
    val userMessage: String,
    val topic: String? = null,
)

@Serializable
data class ChatResponse(
    val reply: String,
    val feedback: AiFeedback? = null,
    val wordsUsedByCoach: List<String> = emptyList(),
)

@Serializable
data class EnrichRequest(val lemma: String, val meaningTr: String = "", val cefr: String = "B1")

@Serializable
data class ExampleDraft(
    val en: String,
    val tr: String = "",
    /** daily | friends | work | academic | other_sense | grammar | collocation | phrasal | idiom | question | negative | spoken */
    val context: String = "daily",
    /** Exact surface form of the word inside [en]. */
    val answer: String = "",
    val acceptedAlternatives: List<String> = emptyList(),
    val noteTr: String? = null,
)

@Serializable
data class SenseDraft(val label: String, val meaningTr: String, val definitionEn: String, val examples: List<ExampleDraft> = emptyList())

@Serializable
data class EnrichResponse(
    val lemma: String,
    val pos: String? = null,
    val ipaUk: String? = null,
    val ipaUs: String? = null,
    val senses: List<SenseDraft> = emptyList(),
    val collocations: List<String> = emptyList(),
    val phrasalVerbs: List<String> = emptyList(),
    val wordFamily: List<String> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    val register: String? = null,
    val commonMistakesTr: List<String> = emptyList(),
    val grammarNoteTr: String? = null,
)

/** Schema-level validation of AI output. Returns a list of problems; empty means valid. */
object AiValidation {
    fun validateStory(story: StoryResponse, request: StoryRequest): List<String> {
        val problems = mutableListOf<String>()
        if (story.title.isBlank()) problems += "empty title"
        if (story.paragraphs.isEmpty() || story.paragraphs.all { it.isBlank() }) problems += "no paragraphs"
        val body = story.paragraphs.joinToString("\n")
        val words = Morphology.tokens(body).size
        if (words < request.wordCount * 0.5 || words > request.wordCount * 1.8) problems += "length $words outside range for ${request.wordCount}"
        request.targets.forEach { t -> if (!Morphology.usesWord(body, t.lemma)) problems += "target '${t.lemma}' missing" }
        story.questions.forEachIndexed { i, q ->
            if (q.prompt.isBlank()) problems += "question $i empty"
            if (q.type == QuestionType.MULTIPLE_CHOICE || q.type == QuestionType.MEANING_IN_CONTEXT) {
                if (q.options.size < 2) problems += "question $i needs options"
                if (q.answerIndex == null || q.answerIndex !in q.options.indices) problems += "question $i answerIndex invalid"
            }
        }
        return problems
    }

    fun validateFeedback(feedback: AiFeedback): List<String> {
        val problems = mutableListOf<String>()
        if (feedback.intendedMeaningTr.isBlank()) problems += "missing intendedMeaningTr"
        if (feedback.corrections.size > 5) problems += "too many corrections"
        feedback.corrections.forEachIndexed { i, c -> if (c.explanationTr.isBlank()) problems += "correction $i has no explanation" }
        return problems
    }

    fun validateChat(response: ChatResponse): List<String> {
        val problems = mutableListOf<String>()
        if (response.reply.isBlank()) problems += "empty reply"
        if (response.reply.length > 1500) problems += "reply too long"
        response.feedback?.let { problems += validateFeedback(it) }
        return problems
    }

    /** Enrichment must give at least [MIN_ENRICH_EXAMPLES] distinct examples, each really containing the word. */
    fun validateEnrich(r: EnrichResponse): List<String> {
        val problems = mutableListOf<String>()
        if (r.senses.isEmpty()) problems += "no senses"
        val all = r.senses.flatMap { it.examples }
        if (all.map { it.en.trim().lowercase() }.toSet().size < MIN_ENRICH_EXAMPLES) problems += "fewer than $MIN_ENRICH_EXAMPLES distinct examples"
        r.senses.forEachIndexed { i, s ->
            s.examples.forEachIndexed { j, e ->
                if (e.en.isBlank()) problems += "sense $i example $j empty"
                else if (e.answer.isBlank() || !e.en.contains(e.answer)) problems += "sense $i example $j answer not in sentence"
                else if (!Morphology.usesWord(e.en, r.lemma) && !e.en.contains(r.lemma, ignoreCase = true)) problems += "sense $i example $j does not use the word"
            }
        }
        return problems
    }

    const val MIN_ENRICH_EXAMPLES = 10

    /** Extracts the first top-level JSON object from a model reply (models sometimes wrap JSON in prose). */
    fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
        }
        return null
    }
}
