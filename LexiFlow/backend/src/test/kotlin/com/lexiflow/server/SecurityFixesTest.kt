package com.lexiflow.server

import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.competition.BattleMode
import com.lexiflow.core.competition.ClientMessage
import com.lexiflow.core.competition.ServerMessage
import com.lexiflow.server.auth.DisplayNameValidator
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Regression tests for the findings of the security audit (docs/security-audit/REPORT.md). */
class SecurityFixesTest {
    private val evaluateBody = """{"text":"I go to park yesterday and I rely on my friend.","targets":["rely"],"mode":"writing","cefr":"B1"}"""

    private suspend fun HttpClient.postAi(token: String): HttpResponse = post("/v1/ai/evaluate") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(evaluateBody)
    }

    private suspend fun errorCode(res: HttpResponse): String? =
        LexiJson.parseToJsonElement(res.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content

    // --- JWT secret: the committed example value must never become the signing key ---

    @Test
    fun `placeholder and low-entropy jwt secrets are refused`() {
        listOf(
            "replace-with-a-long-random-secret-at-least-32-chars",
            "changeme-changeme-changeme-changeme",
            "your-jwt-secret-goes-in-this-place-ok",
            "a".repeat(40),
            "abababababababababababababababababab",
        ).forEach { value ->
            try {
                AppConfig.fromEnv(mapOf("JWT_SECRET" to value))
                fail("JWT_SECRET '$value' should be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
        val ok = AppConfig.fromEnv(mapOf("JWT_SECRET" to "Zq8xP2mL0vR7tK4nB9cW1yH6dF3sJ5gA"))
        assertEquals(false, ok.jwtSecretGenerated)
        // Empty (as in the new .env.example) falls back to a random per-process secret.
        assertTrue(AppConfig.fromEnv(mapOf("JWT_SECRET" to "")).jwtSecretGenerated)
    }

    // --- Battle token must not travel in the URL ---

    @Test
    fun `battle socket ignores the query token unless legacy mode is enabled`() = lexiTest { _ ->
        val ws = wsClient()
        val u = client.register("Header Only")
        try {
            ws.webSocketSession("/v1/battle?token=${u.token}")
            fail("query-string token must be rejected by default")
        } catch (_: Exception) {
        }
        val s = ws.webSocketSession("/v1/battle") { header(HttpHeaders.Authorization, "Bearer ${u.token}") }
        assertEquals(u.playerId, s.expect<ServerMessage.Welcome>().playerId)
    }

    @Test
    fun `legacy query token still works when explicitly allowed`() = lexiTest(config = testConfig(allowWsQueryToken = true)) { _ ->
        val u = client.register("Legacy App")
        val s = wsClient().webSocketSession("/v1/battle?token=${u.token}")
        assertEquals(u.playerId, s.expect<ServerMessage.Welcome>().playerId)
    }

    // --- AI spend: new accounts must not multiply the budget ---

    @Test
    fun `ai budget per source is shared by every account from that address`() {
        val fake = FakeAiProvider("not json")
        lexiTest(ai = fake, config = testConfig(aiPerMinute = 20, aiPerSource = 3)) { _ ->
            val tokens = (1..3).map { client.register("Source User $it").token }
            tokens.forEach { assertEquals(HttpStatusCode.BadGateway, client.postAi(it).status) }
            // A fourth account from the same address gets nothing more.
            val fresh = client.register("Source User 4").token
            val limited = client.postAi(fresh)
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("rate_limited", errorCode(limited))
            assertEquals(6, fake.requests.size) // 3 counted requests x (call + corrective retry)
        }
    }

    @Test
    fun `global ai budget counts every model call including retries`() {
        val fake = FakeAiProvider("not json")
        lexiTest(ai = fake, config = testConfig(aiPerMinute = 20, aiGlobal = 4)) { _ ->
            val a = client.register("Global One").token
            val b = client.register("Global Two").token
            assertEquals(HttpStatusCode.BadGateway, client.postAi(a).status)
            assertEquals(HttpStatusCode.BadGateway, client.postAi(b).status)
            val busy = client.postAi(a)
            assertEquals(HttpStatusCode.TooManyRequests, busy.status)
            assertEquals("ai_busy", errorCode(busy))
            assertEquals(4, fake.requests.size)
        }
    }

    // --- Leaderboard: wins need a real opponent and are capped per opponent set ---

    private fun sentenceWith(words: List<String>) =
        "Yesterday I was thinking about the words ${words.dropLast(1).joinToString(", ")} and ${words.last()}, because my teacher said they are useful."

    @Test
    fun `wins against a trivial or repeated opponent are not credited`() = lexiTest(
        config = testConfig(battle = fastBattle(roundSeconds = 20).copy(maxCreditedWinsPerOpponentSet = 2)),
    ) { store ->
        val ws = wsClient()
        val main = client.register("Farm Main")
        val alt = client.register("Farm Alt")
        val a = ws.webSocketSession("/v1/battle") { header(HttpHeaders.Authorization, "Bearer ${main.token}") }
        val b = ws.webSocketSession("/v1/battle") { header(HttpHeaders.Authorization, "Bearer ${alt.token}") }
        a.expect<ServerMessage.Welcome>(); b.expect<ServerMessage.Welcome>()
        val mode = BattleMode.FIVE_WORD_SENTENCE

        suspend fun round(altText: (List<String>) -> String) {
            a.sendMsg(ClientMessage.JoinQueue(mode)); b.sendMsg(ClientMessage.JoinQueue(mode))
            val m = a.expect<ServerMessage.MatchFound>(); b.expect<ServerMessage.MatchFound>()
            a.sendMsg(ClientMessage.Submit(m.roomId, sentenceWith(m.words)))
            b.sendMsg(ClientMessage.Submit(m.roomId, altText(m.words)))
            val r = a.expect<ServerMessage.RoundResult>(); b.expect<ServerMessage.RoundResult>()
            assertEquals(main.playerId, r.ranking.first())
        }

        // 1) Opponent submits a throwaway answer: the round is played but no win is credited.
        round { "x" }
        assertEquals(0, store.leaderboard(mode, 50).first { it.playerId == main.playerId }.wins)

        // 2) Real opponent answers: wins count, but only up to the per-opponent-set cap (2 here).
        repeat(3) { round { words -> words.joinToString(" ") + " " + words.first() } }
        val entry = store.leaderboard(mode, 50).first { it.playerId == main.playerId }
        assertEquals(4, entry.games)
        assertEquals(2, entry.wins)
    }

    // --- Staff look-alike display names ---

    @Test
    fun `staff names written with turkish letters or accents are rejected`() {
        listOf("Admın", "ADMİN", "Lexıflow", "LEXİFLOW", "Moderatör", "Sıstem", "Stäff", "Ädmin", "Destek Admın", "Lexi Flow", "Ad-min", "Yönetici")
            .forEach { assertNotNull("'$it' should be rejected", DisplayNameValidator.validate(it)) }
        listOf("I got it", "Klasik Müzik", "Ecosystem Fan", "Modern Okur", "Admiral Ayşe")
            .forEach { assertNull("'$it' should stay valid", DisplayNameValidator.validate(it)) }
    }
}
