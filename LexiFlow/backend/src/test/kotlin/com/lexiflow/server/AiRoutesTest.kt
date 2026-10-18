package com.lexiflow.server

import com.lexiflow.core.ai.AiFeedback
import com.lexiflow.core.ai.EnrichResponse
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.server.ai.AiCompletion
import com.lexiflow.server.ai.AiCompletionRequest
import com.lexiflow.server.ai.AiProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Scripted provider: returns the queued replies in order (the last one repeats) and records every request. */
class FakeAiProvider(vararg replies: String) : AiProvider {
    private val queue = ArrayDeque(replies.toList())
    val requests = CopyOnWriteArrayList<AiCompletionRequest>()
    override val name = "fake"
    override val enabled = true
    override suspend fun complete(request: AiCompletionRequest): AiCompletion {
        requests += request
        val text = if (queue.size > 1) queue.removeFirst() else queue.first()
        return AiCompletion(text, "end_turn")
    }
}

class AiRoutesTest {
    private val evaluateBody = """{"text":"I go to park yesterday and I rely on my friend.","targets":["rely"],"mode":"writing","cefr":"B1"}"""

    private suspend fun HttpClient.postAi(path: String, token: String, body: String): HttpResponse = post("/v1/ai/$path") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun code(res: HttpResponse, text: String) = LexiJson.parseToJsonElement(text).jsonObject["code"]?.jsonPrimitive?.content

    @Test
    fun `ai routes return 503 when ai is disabled`() = lexiTest { _ ->
        val token = client.register("No AI").token
        val bodies = mapOf(
            "story" to """{"targets":[{"lemma":"rely"}],"cefr":"B1","wordCount":120}""",
            "evaluate" to evaluateBody,
            "chat" to """{"history":[],"profile":{"cefr":"A2"},"userMessage":"Hello, how are you?"}""",
            "enrich" to """{"lemma":"rely"}""",
        )
        bodies.forEach { (path, body) ->
            val res = client.postAi(path, token, body)
            assertEquals("$path status", HttpStatusCode.ServiceUnavailable, res.status)
            val text = res.bodyAsText()
            assertEquals("ai_unavailable", code(res, text))
            assertTrue(LexiJson.parseToJsonElement(text).jsonObject["message"]!!.jsonPrimitive.content.isNotBlank())
        }
    }

    @Test
    fun `invalid model json triggers one corrective retry then 502`() {
        val fake = FakeAiProvider("Sure! Here is your feedback: {\"corrections\": [}")
        lexiTest(ai = fake) { _ ->
            val token = client.register("Retry Tester").token
            val res = client.postAi("evaluate", token, evaluateBody)
            assertEquals(HttpStatusCode.BadGateway, res.status)
            assertEquals("ai_invalid_output", code(res, res.bodyAsText()))
            assertEquals("exactly one retry", 2, fake.requests.size)
            assertFalse(fake.requests[0].user.contains("rejected by the validator"))
            assertTrue("validation errors are appended on retry", fake.requests[1].user.contains("rejected by the validator"))
            assertTrue(fake.requests[0].system.contains("intendedMeaningTr"))
        }
    }

    @Test
    fun `schema-valid but semantically invalid output is retried with the validator problems`() {
        // Valid JSON, but intendedMeaningTr is blank -> AiValidation.validateFeedback rejects it.
        val bad = """{"intendedMeaningTr":"","corrections":[]}"""
        val good = """{"intendedMeaningTr":"Dün parka gittiğini anlatıyorsun.","corrections":[
            {"original":"go","corrected":"went","explanationTr":"Geçmiş zaman: went.","category":"grammar"},
            {"original":"park","corrected":"the park","explanationTr":"Belirli yer: the park.","category":"grammar"},
            {"original":"a","corrected":"b","explanationTr":"c","category":"grammar"},
            {"original":"d","corrected":"e","explanationTr":"f","category":"grammar"}],
            "targetWordFeedback":[{"lemma":"rely","correct":true,"commentTr":"Doğru."}]}"""
        val fake = FakeAiProvider(bad, good)
        lexiTest(ai = fake) { _ ->
            val token = client.register("Retry Ok").token
            val res = client.postAi("evaluate", token, evaluateBody)
            assertEquals(HttpStatusCode.OK, res.status)
            val feedback = LexiJson.decodeFromString(AiFeedback.serializer(), res.bodyAsText())
            assertEquals("Dün parka gittiğini anlatıyorsun.", feedback.intendedMeaningTr)
            assertEquals("server enforces at most 3 corrections", 3, feedback.corrections.size)
            assertEquals(2, fake.requests.size)
            assertTrue(fake.requests[1].user.contains("missing intendedMeaningTr"))
        }
    }

    @Test
    fun `enrich requires at least ten example sentences`() {
        fun examples(n: Int, answer: String = "rely") = (1..n).joinToString(",") {
            """{"en":"Example $it: we rely on friends.","tr":"Örnek $it","context":"daily","answer":"$answer"}"""
        }
        fun sense(n: Int, answer: String = "rely") =
            """{"label":"depend on","meaningTr":"güvenmek","definitionEn":"to depend on","examples":[${examples(n, answer)}]}"""
        val fake = FakeAiProvider(
            """{"lemma":"rely","senses":[${sense(3)}]}""",
            // 10 examples but the cloze answer is not a substring of the sentence -> still invalid.
            """{"lemma":"rely","senses":[${sense(10, answer = "relies")}]}""",
        )
        lexiTest(ai = fake) { _ ->
            val token = client.register("Enricher").token
            val res = client.postAi("enrich", token, """{"lemma":"rely","cefr":"B1"}""")
            assertEquals(HttpStatusCode.BadGateway, res.status)
            assertTrue(fake.requests[1].user.contains("fewer than 10 distinct examples"))
        }
        val good = FakeAiProvider("""{"lemma":"rely","senses":[${sense(4)}]}""", """{"lemma":"rely","pos":"verb","senses":[${sense(6)},${sense(4).replace("Example", "Sample")}]}""")
        lexiTest(ai = good) { _ ->
            val token = client.register("Enricher Two").token
            val res = client.postAi("enrich", token, """{"lemma":"rely","cefr":"B1"}""")
            assertEquals(HttpStatusCode.OK, res.status)
            val body = LexiJson.decodeFromString(EnrichResponse.serializer(), res.bodyAsText())
            assertEquals(10, body.senses.sumOf { it.examples.size })
            assertTrue(body.senses.flatMap { it.examples }.all { it.en.contains(it.answer) })
            assertEquals(2, good.requests.size)
        }
    }

    @Test
    fun `input limits are enforced`() {
        val fake = FakeAiProvider("{}")
        lexiTest(ai = fake) { _ ->
            val token = client.register("Limits").token
            val longText = "a".repeat(4_001)
            assertEquals(HttpStatusCode.BadRequest, client.postAi("evaluate", token, """{"text":"$longText"}""").status)
            val history = (1..21).joinToString(",") { """{"role":"user","text":"hi"}""" }
            assertEquals(HttpStatusCode.BadRequest, client.postAi("chat", token, """{"history":[$history],"profile":{},"userMessage":"hi"}""").status)
            assertEquals(HttpStatusCode.BadRequest, client.postAi("story", token, """{"targets":[]}""").status)
            assertEquals(HttpStatusCode.BadRequest, client.postAi("enrich", token, """{"lemma":"rely","cefr":"Z9"}""").status)
            assertEquals(HttpStatusCode.PayloadTooLarge, client.postAi("evaluate", token, """{"text":"${"b".repeat(200_000)}"}""").status)
            assertEquals("provider is never called for rejected input", 0, fake.requests.size)
        }
    }

    @Test
    fun `ai routes are rate limited per user`() {
        val fake = FakeAiProvider("not json")
        lexiTest(ai = fake, config = testConfig(aiPerMinute = 2)) { _ ->
            val a = client.register("Rate One").token
            val b = client.register("Rate Two").token
            assertEquals(HttpStatusCode.BadGateway, client.postAi("evaluate", a, evaluateBody).status)
            assertEquals(HttpStatusCode.BadGateway, client.postAi("evaluate", a, evaluateBody).status)
            val limited = client.postAi("evaluate", a, evaluateBody)
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("rate_limited", code(limited, limited.bodyAsText()))
            // Another user has their own bucket.
            assertEquals(HttpStatusCode.BadGateway, client.postAi("evaluate", b, evaluateBody).status)
        }
    }
}
