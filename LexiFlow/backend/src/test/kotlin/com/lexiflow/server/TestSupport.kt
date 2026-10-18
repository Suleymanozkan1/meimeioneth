package com.lexiflow.server

import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.competition.ClientMessage
import com.lexiflow.core.competition.ServerMessage
import com.lexiflow.server.ai.AiProvider
import com.lexiflow.server.ai.DisabledAiProvider
import com.lexiflow.server.routes.RegisterResponse
import com.lexiflow.server.store.InMemoryStore
import com.lexiflow.server.store.Store
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

const val TEST_SECRET = "test-secret-0123456789-abcdefghijklmnopqrstuvwxyz"

fun fastBattle(roundSeconds: Int = 3) = BattleSettings(
    roundSecondsOverride = roundSeconds,
    matchWindowMs = 150,
    graceMs = 300,
    messageLimit = 20,
    messageWindowMs = 10_000,
    maxViolations = 3,
    finishedRoomRetentionMs = 60_000,
)

fun testConfig(
    battle: BattleSettings = fastBattle(),
    aiPerMinute: Int = 20,
    aiProvider: String = "none",
) = AppConfig(
    port = 0,
    jwtSecret = TEST_SECRET,
    aiProvider = aiProvider,
    rateLimitAiPerMinute = aiPerMinute,
    rateLimitRegisterPerMinute = 1_000,
    battle = battle,
)

/** Runs a ktor test application wired with an in-memory store and the given AI provider. */
fun lexiTest(
    ai: AiProvider = DisabledAiProvider(),
    config: AppConfig = testConfig(),
    store: InMemoryStore = InMemoryStore(),
    block: suspend ApplicationTestBuilder.(Store) -> Unit,
) = testApplication {
    application { lexiFlowModule(ServerDeps(config, store, ai)) }
    block(store)
}

fun ApplicationTestBuilder.wsClient(): HttpClient = createClient { install(ClientWebSockets) }

suspend fun HttpClient.register(name: String): RegisterResponse {
    val res = post("/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"displayName":"$name"}""")
    }
    assertEquals(HttpStatusCode.Created, res.status)
    return LexiJson.decodeFromString(RegisterResponse.serializer(), res.bodyAsText())
}

/** Timeouts on a real-time dispatcher (independent of any virtual test clock). */
suspend fun <T> real(ms: Long = 8_000, block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(ms) { block() } }

suspend fun DefaultClientWebSocketSession.sendMsg(message: ClientMessage) {
    send(Frame.Text(LexiJson.encodeToString(ClientMessage.serializer(), message)))
}

suspend fun DefaultClientWebSocketSession.sendRaw(text: String) {
    send(Frame.Text(text))
}

suspend fun DefaultClientWebSocketSession.nextMsg(ms: Long = 8_000): ServerMessage = real(ms) {
    val frame = incoming.receive() as Frame.Text
    LexiJson.decodeFromString(ServerMessage.serializer(), frame.readText())
}

/** Receives until a message of type [T] arrives (skipping e.g. Queued updates). */
suspend inline fun <reified T : ServerMessage> DefaultClientWebSocketSession.expect(ms: Long = 8_000): T = real(ms) {
    while (true) {
        val m = nextMsg(ms)
        if (m is T) return@real m
    }
    @Suppress("UNREACHABLE_CODE")
    error("unreachable")
}

/** Returns the first message of type [T] within [ms], or null (used to prove that something does NOT happen). */
suspend inline fun <reified T : ServerMessage> DefaultClientWebSocketSession.maybe(ms: Long): T? =
    withContext(Dispatchers.Default) {
        withTimeoutOrNull(ms) {
            while (true) {
                val frame = incoming.receive() as Frame.Text
                val m = LexiJson.decodeFromString(ServerMessage.serializer(), frame.readText())
                if (m is T) return@withTimeoutOrNull m
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }
