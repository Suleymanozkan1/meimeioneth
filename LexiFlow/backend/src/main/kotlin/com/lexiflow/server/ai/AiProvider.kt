package com.lexiflow.server.ai

/** One model call: a static system prompt plus a single user message. */
data class AiCompletionRequest(
    /** Short task name for logs/metrics ("story", "evaluate", ...). Never log prompt contents. */
    val task: String,
    val system: String,
    val user: String,
    val maxTokens: Int = 16_000,
)

/** Raw text returned by the model (the caller extracts and validates JSON). */
data class AiCompletion(val text: String, val stopReason: String?)

/** Low-level model access. Prompting, parsing, validation and retry live in [AiService]. */
interface AiProvider {
    val name: String
    val enabled: Boolean
    suspend fun complete(request: AiCompletionRequest): AiCompletion
    fun close() {}
}

/** AI is turned off (AI_PROVIDER=none or no API key): every call fails with [AiUnavailableException]. */
class DisabledAiProvider : AiProvider {
    override val name: String = "none"
    override val enabled: Boolean = false
    override suspend fun complete(request: AiCompletionRequest): AiCompletion = throw AiUnavailableException()
}

open class AiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** AI is not configured on this server -> HTTP 503 ai_unavailable. */
class AiUnavailableException : AiException("AI features are not enabled on this server")

/** The upstream API failed (network, 4xx/5xx after retries) -> HTTP 502 ai_upstream_error. */
class AiUpstreamException(message: String, val status: Int? = null, cause: Throwable? = null) : AiException(message, cause)

/** The model declined the request (stop_reason = refusal) -> HTTP 422 ai_refused. */
class AiRefusedException(val category: String?) : AiException("The model declined this request")

/** The model output stayed invalid after one corrective retry -> HTTP 502 ai_invalid_output. */
class AiInvalidOutputException(val problemCount: Int) : AiException("AI output failed validation")
