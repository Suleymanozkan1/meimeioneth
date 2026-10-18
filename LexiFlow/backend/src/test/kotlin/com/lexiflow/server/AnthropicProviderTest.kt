package com.lexiflow.server

import com.lexiflow.server.ai.AiCompletionRequest
import com.lexiflow.server.ai.AiRefusedException
import com.lexiflow.server.ai.AiUpstreamException
import com.lexiflow.server.ai.AnthropicProvider
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AnthropicProviderTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val request = AiCompletionRequest(task = "evaluate", system = "SYSTEM", user = "USER", maxTokens = 1234)

    @Test
    fun `sends messages api request and concatenates text blocks`() = runBlocking {
        var captured: String? = null
        val engine = MockEngine { req ->
            assertEquals("https://api.anthropic.com/v1/messages", req.url.toString())
            assertEquals("sk-test", req.headers["x-api-key"])
            assertEquals("2023-06-01", req.headers["anthropic-version"])
            assertEquals("server-side-fallback-2026-07-01", req.headers["anthropic-beta"])
            captured = String(req.body.toByteArray())
            respond(
                """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","stop_reason":"end_turn",
                   "content":[{"type":"thinking","thinking":""},{"type":"text","text":"{\"a\":"},{"type":"text","text":"1}"}]}""",
                HttpStatusCode.OK, jsonHeaders,
            )
        }
        val provider = AnthropicProvider("sk-test", "claude-opus-5-5", engine = engine)
        val result = provider.complete(request)
        assertEquals("{\"a\":1}", result.text)
        assertEquals("end_turn", result.stopReason)
        val body = Json.parseToJsonElement(captured!!).jsonObject
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(1234, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("SYSTEM", body["system"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        assertEquals("medium", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val msg = body["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", msg["role"]!!.jsonPrimitive.content)
        assertEquals("USER", msg["content"]!!.jsonPrimitive.content)
        assertFalse("no sampling params (rejected by this model)", body.containsKey("temperature"))
        assertFalse("thinking left at model default", body.containsKey("thinking"))
    }

    @Test
    fun `retries 429 and 529 then succeeds`() = runBlocking {
        val calls = AtomicInteger()
        val engine = MockEngine {
            when (calls.incrementAndGet()) {
                1 -> respond("""{"type":"error","error":{"type":"rate_limit_error","message":"slow"}}""", HttpStatusCode.TooManyRequests, headersOf("retry-after", "0"))
                2 -> respond("""{"type":"error","error":{"type":"overloaded_error","message":"busy"}}""", HttpStatusCode.fromValue(529), jsonHeaders)
                else -> respond("""{"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val provider = AnthropicProvider("sk-test", "claude-opus-5-5", engine = engine, retryBaseDelayMs = 1)
        assertEquals("ok", provider.complete(request).text)
        assertEquals(3, calls.get())
    }

    @Test
    fun `does not retry client errors`() = runBlocking {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("""{"type":"error","error":{"type":"invalid_request_error","message":"bad"}}""", HttpStatusCode.BadRequest, jsonHeaders)
        }
        val provider = AnthropicProvider("sk-test", "claude-opus-5-5", engine = engine, retryBaseDelayMs = 1)
        try {
            provider.complete(request)
            fail("expected AiUpstreamException")
        } catch (e: AiUpstreamException) {
            assertEquals(400, e.status)
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun `refusal stop reason is surfaced`() = runBlocking {
        val engine = MockEngine {
            respond("""{"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber"},"content":[]}""", HttpStatusCode.OK, jsonHeaders)
        }
        val provider = AnthropicProvider("sk-test", "claude-opus-5-5", engine = engine, useFallbacks = false)
        try {
            provider.complete(request)
            fail("expected refusal")
        } catch (e: AiRefusedException) {
            assertEquals("cyber", e.category)
        }
        assertTrue(true)
    }
}
