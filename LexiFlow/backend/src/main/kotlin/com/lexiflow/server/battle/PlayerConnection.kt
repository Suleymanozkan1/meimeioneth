package com.lexiflow.server.battle

import com.lexiflow.core.competition.ServerMessage
import kotlinx.coroutines.channels.Channel

/**
 * One authenticated WebSocket. Outgoing messages go through a bounded channel drained by a writer coroutine,
 * so the battle manager never suspends on network I/O while holding its lock.
 */
class PlayerConnection(
    val connectionId: String,
    val playerId: String,
    val displayName: String,
    val limiter: MessageRateLimiter,
    /** Invoked (once) when the connection must be dropped: slow consumer, replaced by a newer socket, abuse. */
    private val onKill: (PlayerConnection, String) -> Unit,
) {
    val outgoing = Channel<ServerMessage>(capacity = OUTGOING_BUFFER)

    @Volatile
    var killed: Boolean = false
        private set

    /** Non-blocking send. A client that does not read its messages is disconnected instead of buffering forever. */
    fun send(message: ServerMessage): Boolean {
        if (killed) return false
        val ok = outgoing.trySend(message).isSuccess
        if (!ok) kill("slow consumer")
        return ok
    }

    fun kill(reason: String) {
        if (killed) return
        killed = true
        onKill(this, reason)
    }

    companion object {
        const val OUTGOING_BUFFER = 64
    }
}

/** Sliding-window limiter: at most [limit] messages per [windowMs]; counts violations inside the window. */
class MessageRateLimiter(
    private val limit: Int,
    private val windowMs: Long,
    private val maxViolations: Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val stamps = ArrayDeque<Long>()
    private val violations = ArrayDeque<Long>()

    /** True when the message may be processed. */
    @Synchronized
    fun tryAcquire(): Boolean {
        val now = nowMs()
        while (stamps.isNotEmpty() && now - stamps.first() >= windowMs) stamps.removeFirst()
        while (violations.isNotEmpty() && now - violations.first() >= windowMs) violations.removeFirst()
        if (stamps.size >= limit) {
            violations.addLast(now)
            return false
        }
        stamps.addLast(now)
        return true
    }

    /** True when the client keeps flooding after being told to slow down -> close the socket. */
    @get:Synchronized
    val abusive: Boolean get() = violations.size > maxViolations
}
