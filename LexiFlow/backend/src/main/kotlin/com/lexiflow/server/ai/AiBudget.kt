package com.lexiflow.server.ai

import com.lexiflow.server.ApiException
import io.ktor.http.HttpStatusCode

/**
 * Aggregate AI spend limits that minting new (free) accounts cannot reset. The per-player Ktor rate limit keys on the
 * JWT subject, so on its own it lets one client multiply its budget by registering more accounts.
 *
 * - [checkSource]: AI requests per client address per minute, over every account used from that address.
 * - [acquireUpstream]: model calls per minute for the whole server, counting corrective retries.
 *
 * Fixed one-minute windows are enough here: the goal is a hard ceiling on spend, not smooth shaping.
 */
class AiBudget(
    private val perSourcePerMinute: Int,
    private val globalPerMinute: Int,
    private val nowMs: () -> Long,
) {
    private class Window(var startMs: Long, var count: Int)

    private val global = Window(0, 0)
    private val sources = HashMap<String, Window>()

    fun checkSource(source: String) {
        val ok = synchronized(sources) {
            val now = nowMs()
            if (sources.size > MAX_TRACKED_SOURCES) sources.entries.removeIf { now - it.value.startMs >= WINDOW_MS }
            take(sources.getOrPut(source) { Window(now, 0) }, perSourcePerMinute, now)
        }
        if (!ok) throw ApiException(HttpStatusCode.TooManyRequests, "rate_limited", "Too many AI requests from this network, please slow down")
    }

    fun acquireUpstream() {
        val ok = synchronized(global) { take(global, globalPerMinute, nowMs()) }
        if (!ok) throw ApiException(HttpStatusCode.TooManyRequests, "ai_busy", "The AI service is busy right now, please try again in a minute")
    }

    private fun take(w: Window, limit: Int, now: Long): Boolean {
        if (now - w.startMs >= WINDOW_MS) { w.startMs = now; w.count = 0 }
        if (w.count >= limit) return false
        w.count++
        return true
    }

    companion object {
        private const val WINDOW_MS = 60_000L
        private const val MAX_TRACKED_SOURCES = 50_000
    }
}
