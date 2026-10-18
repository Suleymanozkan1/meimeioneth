package com.lexiflow.server

import com.lexiflow.core.ai.LexiJson
import com.lexiflow.server.auth.DisplayNameValidator
import com.lexiflow.server.auth.JwtService
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpApiTest {

    @Test
    fun `health is public and reports ai and db`() = lexiTest { _ ->
        val res = client.get("/health")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = LexiJson.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("ok", body["status"]!!.jsonPrimitive.content)
        assertEquals(false, body["ai"]!!.jsonPrimitive.boolean)
        assertEquals("memory", body["db"]!!.jsonPrimitive.content)
    }

    @Test
    fun `register returns a verifiable token`() = lexiTest { store ->
        val reg = client.register("Ayşe_Öğrenci 7")
        assertEquals("Ayşe_Öğrenci 7", reg.displayName)
        assertNotNull(store.findUser(reg.playerId))
        val jwt = JwtService(TEST_SECRET).verifier.verify(reg.token)
        assertEquals(reg.playerId, jwt.subject)
        assertEquals("lexiflow", jwt.issuer)
        val days = (jwt.expiresAt.time - jwt.issuedAt.time) / 86_400_000.0
        assertEquals(90.0, days, 0.01)
    }

    @Test
    fun `register validates display name`() = lexiTest { _ ->
        suspend fun status(json: String): Pair<HttpStatusCode, String> {
            val res = client.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(json)
            }
            return res.status to res.bodyAsText()
        }
        listOf("a", "x".repeat(25), "<script>", "bad;name", "fuckface", "Admin", "s1kt1r", "12345").forEach { name ->
            val (code, body) = status("""{"displayName":"$name"}""")
            assertEquals("name '$name' should be rejected", HttpStatusCode.BadRequest, code)
            assertTrue(body.contains("invalid_display_name"))
        }
        assertEquals(HttpStatusCode.BadRequest, status("""{"nope":1}""").first)
        assertEquals(HttpStatusCode.BadRequest, status("not json").first)
        assertEquals(HttpStatusCode.Created, status("""{"displayName":"  Klasik   Okur  "}""").first)
    }

    @Test
    fun `display name validator keeps harmless words`() {
        assertNull(DisplayNameValidator.validate("Klasik Müzik"))
        assertNull(DisplayNameValidator.validate("I got it"))
        assertNull(DisplayNameValidator.validate("Götür-Getir"))
        assertNotNull(DisplayNameValidator.validate("amk"))
    }

    @Test
    fun `protected routes require a valid bearer token`() = lexiTest { _ ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/leaderboard").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/leaderboard") { bearerAuth("garbage") }.status)
        val forged = JwtService("another-secret-another-secret-another-secret").issue("p1", "Mallory")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/leaderboard") { bearerAuth(forged) }.status)
        val unauth = client.post("/v1/ai/story") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, unauth.status)
        assertTrue(unauth.bodyAsText().contains("unauthorized"))
        // WebSocket endpoint rejects the upgrade without a token.
        val ws = wsClient()
        listOf("/v1/battle", "/v1/battle?token=garbage", "/v1/battle?token=$forged").forEach { url ->
            val failure = runCatching { ws.webSocketSession(url) }.exceptionOrNull()
            assertNotNull("handshake must fail for $url", failure)
            // (the test engine hides the 401 status of a failed handshake; a valid token connects in BattleTest)
        }

        val reg = client.register("Valid User")
        val ok = client.get("/v1/leaderboard") { bearerAuth(reg.token) }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(0, LexiJson.parseToJsonElement(ok.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `leaderboard rejects unknown mode`() = lexiTest { _ ->
        val reg = client.register("Mode Tester")
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/leaderboard?mode=NOPE") { bearerAuth(reg.token) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/leaderboard?mode=TIMED_WRITING") { bearerAuth(reg.token) }.status)
    }
}
