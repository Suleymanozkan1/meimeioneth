package com.lexiflow.server.ai

import com.lexiflow.core.ai.AiFeedback
import com.lexiflow.core.ai.AiValidation
import com.lexiflow.core.ai.ChatRequest
import com.lexiflow.core.ai.ChatResponse
import com.lexiflow.core.ai.EnrichRequest
import com.lexiflow.core.ai.EnrichResponse
import com.lexiflow.core.ai.EvaluateRequest
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.ai.StoryRequest
import com.lexiflow.core.ai.StoryResponse
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

/**
 * Turns the core contract requests into model calls: builds prompts, extracts the JSON object from the reply,
 * decodes it with [LexiJson], validates it with [AiValidation] (+ a few server-side rules) and retries once
 * with the validation problems appended. Still invalid -> [AiInvalidOutputException].
 */
class AiService(private val provider: AiProvider, private val budget: AiBudget? = null) {
    private val log = LoggerFactory.getLogger(AiService::class.java)

    val enabled: Boolean get() = provider.enabled

    suspend fun story(request: StoryRequest): StoryResponse =
        generate("story", Prompts.STORY, LexiJson.encodeToString(StoryRequest.serializer(), request), StoryResponse.serializer(), maxTokens = 16_000) {
            AiValidation.validateStory(it, request)
        }

    suspend fun evaluate(request: EvaluateRequest): AiFeedback =
        generate("evaluate", Prompts.EVALUATE, LexiJson.encodeToString(EvaluateRequest.serializer(), request), AiFeedback.serializer(), maxTokens = 8_000) {
            AiValidation.validateFeedback(it)
        }.let(::limitFeedback)

    suspend fun chat(request: ChatRequest): ChatResponse =
        generate("chat", Prompts.CHAT, LexiJson.encodeToString(ChatRequest.serializer(), request), ChatResponse.serializer(), maxTokens = 8_000) {
            AiValidation.validateChat(it)
        }.let { r -> r.copy(feedback = r.feedback?.let(::limitFeedback)) }

    suspend fun enrich(request: EnrichRequest): EnrichResponse =
        generate("enrich", Prompts.ENRICH, LexiJson.encodeToString(EnrichRequest.serializer(), request), EnrichResponse.serializer(), maxTokens = 16_000) {
            val problems = AiValidation.validateEnrich(it).toMutableList()
            if (!it.lemma.trim().equals(request.lemma.trim(), ignoreCase = true)) problems += "lemma must be '${request.lemma}'"
            problems
        }

    /** The product promise is "at most 3 corrections"; the core validator tolerates up to 5, so trim here. */
    private fun limitFeedback(f: AiFeedback): AiFeedback = f.copy(corrections = f.corrections.take(MAX_CORRECTIONS), alternatives = f.alternatives.take(3))

    private suspend fun <T> generate(
        task: String,
        system: String,
        requestJson: String,
        serializer: KSerializer<T>,
        maxTokens: Int,
        validate: (T) -> List<String>,
    ): T {
        if (!provider.enabled) throw AiUnavailableException()
        var problems: List<String>? = null
        for (attempt in 1..2) {
            // Every model call is billed, including the corrective retry, so each one draws from the global budget.
            budget?.acquireUpstream()
            val completion = provider.complete(
                AiCompletionRequest(task = task, system = system, user = Prompts.userMessage(task, requestJson, problems), maxTokens = maxTokens),
            )
            problems = parseAndValidate(completion, serializer, validate).fold(
                onSuccess = { value ->
                    if (attempt > 1) log.info("AI task={} valid after corrective retry", task)
                    return value
                },
                onFailure = { (it as InvalidOutput).problems },
            )
            // Log only the number of problems: they can echo learner text.
            log.warn("AI task={} attempt={} produced invalid output ({} problems)", task, attempt, problems.size)
        }
        throw AiInvalidOutputException(problems?.size ?: 0)
    }

    private class InvalidOutput(val problems: List<String>) : Exception()

    private fun <T> parseAndValidate(completion: AiCompletion, serializer: KSerializer<T>, validate: (T) -> List<String>): Result<T> {
        if (completion.stopReason == "max_tokens") return Result.failure(InvalidOutput(listOf("output was cut off (too long); be more concise")))
        val jsonText = AiValidation.extractJsonObject(completion.text)
            ?: return Result.failure(InvalidOutput(listOf("no JSON object found in the reply")))
        val value = try {
            LexiJson.decodeFromString(serializer, jsonText)
        } catch (e: Exception) {
            val reason = e.message?.lineSequence()?.firstOrNull()?.take(200) ?: "invalid JSON"
            return Result.failure(InvalidOutput(listOf("JSON does not match the required shape: $reason")))
        }
        val problems = try {
            validate(value)
        } catch (e: Exception) {
            listOf("output could not be validated")
        }
        return if (problems.isEmpty()) Result.success(value) else Result.failure(InvalidOutput(problems))
    }

    companion object {
        const val MAX_CORRECTIONS = 3
    }
}
