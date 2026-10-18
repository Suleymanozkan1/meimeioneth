package com.lexiflow.server.routes

import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.competition.ClientMessage
import com.lexiflow.core.competition.ServerMessage
import com.lexiflow.server.auth.JwtService
import com.lexiflow.server.battle.BattleManager
import com.lexiflow.server.battle.PlayerConnection
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.UUID

private val log = LoggerFactory.getLogger("com.lexiflow.server.routes.BattleRoutes")

/** Must be mounted inside the WebSocket JWT provider (token via `?token=` or `Authorization: Bearer`). */
fun Route.battleRoutes(manager: BattleManager) {
    webSocket("/v1/battle") {
        val principal = call.principal<JWTPrincipal>()
        val playerId = principal?.subject
        val displayName = principal?.payload?.getClaim(JwtService.CLAIM_NAME)?.asString()
        if (playerId == null || displayName == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }
        val session = this
        val conn = PlayerConnection(
            connectionId = UUID.randomUUID().toString(),
            playerId = playerId,
            displayName = displayName,
            limiter = manager.newRateLimiter(),
            onKill = { c, reason ->
                c.outgoing.close()
                session.launch {
                    // Give the writer a moment to flush the last messages (e.g. the error explaining the close).
                    kotlinx.coroutines.delay(100)
                    runCatching { session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason.take(100))) }
                }
            },
        )
        val writer = launch {
            try {
                for (message in conn.outgoing) {
                    send(Frame.Text(LexiJson.encodeToString(ServerMessage.serializer(), message)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Socket already closed.
            }
        }

        manager.connect(conn)
        try {
            for (frame in incoming) {
                if (conn.killed) break
                if (!conn.limiter.tryAcquire()) {
                    conn.send(ServerMessage.Error("rate_limited", "Too many messages, slow down"))
                    if (conn.limiter.abusive) {
                        conn.kill("rate limit exceeded")
                        break
                    }
                    continue
                }
                val message: ClientMessage? = when (frame) {
                    is Frame.Text -> try {
                        LexiJson.decodeFromString(ClientMessage.serializer(), frame.readText())
                    } catch (e: Exception) {
                        null
                    }
                    else -> null
                }
                if (message == null) {
                    conn.send(ServerMessage.Error("bad_message", "Malformed message"))
                    continue
                }
                manager.handle(conn, message)
            }
        } catch (_: ClosedReceiveChannelException) {
            // client went away
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("battle socket error: {}", e.javaClass.simpleName)
        } finally {
            manager.disconnect(conn)
            conn.outgoing.close()
            withTimeoutOrNull(1_000) { writer.join() }
            writer.cancel()
        }
    }
}
