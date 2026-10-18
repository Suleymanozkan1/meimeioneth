package com.lexiflow.server.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Claude via the Messages API (`POST /v1/messages`) over ktor-client CIO.
 *
 * - Headers: `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json`.
 * - Thinking is left at the model default (adaptive on Claude Opus 5.5); depth is controlled with
 *   `output_config.effort` (set explicitly, the model default is "medium").
 * - Server-side refusal fallback (`fallbacks: "default"` + beta `server-side-fallback-2026-07-01`) is on by default.
 * - Retries 429 / 5xx / 529 / network errors (honouring `retry-after`); 4xx errors are not retried.
 * - `stop_reason: "refusal"` is checked before reading content.
 * Only status codes, error types and request ids are logged - never prompts, outputs or the API key.
 */
class AnthropicProvider(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://api.anthropic.com",
    private val useFallbacks: Boolean = true,
    private val effort: String = "medium",
    private val maxRetries: Int = 2,
    engine: HttpClientEngine? = null,
    private val retryBaseDelayMs: Long = 1_000,
) : AiProvider {
    override val name: String = "anthropic"
    override val enabled: Boolean = true

    private val log = LoggerFactory.getLogger(AnthropicProvider::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val client: HttpClient = (if (engine != null) HttpClient(engine) { configure() } else HttpClient(CIO) { configure() })

    private fun io.ktor.client.HttpClientConfig<*>.configure() {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 180_000
        }
    }

    override suspend fun complete(request: AiCompletionRequest): AiCompletion {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", request.maxTokens)
            put("system", request.system)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", request.user)
                })
            })
            put("output_config", buildJsonObject { put("effort", effort) })
            if (useFallbacks) put("fallbacks", "default")
        }.toString()

        var attempt = 0
        while (true) {
            val started = System.nanoTime()
            val response: HttpResponse = try {
                client.post("$baseUrl/v1/messages") {
                    header("x-api-key", apiKey)
                    header("anthropic-version", ANTHROPIC_VERSION)
                    if (useFallbacks) header("anthropic-beta", FALLBACK_BETA)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Connection errors and timeouts are retryable.
                if (attempt < maxRetries && (e is IOException || e.javaClass.name.contains("Timeout"))) {
                    attempt++
                    log.warn("Anthropic request failed task={} error={} - retry {}/{}", request.task, e.javaClass.simpleName, attempt, maxRetries)
                    delay(backoff(attempt, null))
                    continue
                }
                throw AiUpstreamException("AI provider unreachable", cause = e)
            }

            val status = response.status.value
            val text = response.bodyAsText()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val requestId = response.headers["request-id"]

            if (status == 200) {
                return parseSuccess(text, request.task, elapsedMs, requestId)
            }

            val errorType = runCatching {
                json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            val retryable = status == 408 || status == 409 || status == 429 || status >= 500
            if (retryable && attempt < maxRetries) {
                attempt++
                val wait = backoff(attempt, response.headers["retry-after"])
                log.warn("Anthropic HTTP {} ({}) task={} requestId={} - retry {}/{} in {} ms", status, errorType, request.task, requestId, attempt, maxRetries, wait)
                delay(wait)
                continue
            }
            when (status) {
                401 -> log.error("Anthropic authentication failed (401): check ANTHROPIC_API_KEY. requestId={}", requestId)
                403 -> log.error("Anthropic permission error (403) requestId={}", requestId)
                404 -> log.error("Anthropic 404 ({}): check ANTHROPIC_MODEL is available to this API key. requestId={}", errorType, requestId)
                else -> log.error("Anthropic HTTP {} ({}) task={} requestId={}", status, errorType, request.task, requestId)
            }
            throw AiUpstreamException("AI provider returned HTTP $status", status)
        }
    }

    private fun parseSuccess(text: String, task: String, elapsedMs: Long, requestId: String?): AiCompletion {
        val obj: JsonObject = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw AiUpstreamException("AI provider returned an unreadable response", 200, e)
        }
        val stopReason = (obj["stop_reason"] as? JsonPrimitive)?.contentOrNull
        if (stopReason == "refusal") {
            val category = runCatching { obj["stop_details"]?.jsonObject?.get("category")?.jsonPrimitive?.contentOrNull }.getOrNull()
            log.warn("Anthropic refusal task={} category={} requestId={}", task, category, requestId)
            throw AiRefusedException(category)
        }
        // Concatenate text blocks only; thinking / fallback / other block types are ignored.
        val content = obj["content"]?.jsonArray.orEmpty()
        val out = content.mapNotNull { block ->
            val b = block.jsonObject
            if ((b["type"] as? JsonPrimitive)?.contentOrNull == "text") (b["text"] as? JsonPrimitive)?.contentOrNull else null
        }.joinToString("")
        val servedBy = (obj["model"] as? JsonPrimitive)?.contentOrNull
        log.info("Anthropic ok task={} stop={} model={} ms={} requestId={}", task, stopReason, servedBy, elapsedMs, requestId)
        return AiCompletion(out, stopReason)
    }

    private fun backoff(attempt: Int, retryAfter: String?): Long {
        val fromHeader = retryAfter?.trim()?.toLongOrNull()?.let { it * 1000 }
        val exponential = retryBaseDelayMs * (1L shl (attempt - 1))
        return (fromHeader ?: exponential).coerceIn(0, 20_000)
    }

    override fun close() {
        client.close()
    }

    companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    }
}
