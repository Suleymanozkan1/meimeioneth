package com.lexiflow.server.routes

import com.lexiflow.core.competition.BattleMode
import com.lexiflow.server.ApiException
import com.lexiflow.server.RATE_LIMIT_LEADERBOARD
import com.lexiflow.server.store.Store
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** `GET /v1/leaderboard?mode=FIVE_WORD_SENTENCE` -> JSON array of real aggregated results (empty when no games). */
fun Route.leaderboardRoutes(store: Store) = rateLimit(RATE_LIMIT_LEADERBOARD) {
    get("/v1/leaderboard") {
        val modeParam = call.request.queryParameters["mode"]?.trim()?.takeIf { it.isNotEmpty() }
        val mode = modeParam?.let { p ->
            BattleMode.entries.firstOrNull { it.name.equals(p, ignoreCase = true) }
                ?: throw ApiException.badRequest("mode must be one of ${BattleMode.entries.joinToString { it.name }}")
        }
        call.respond(store.leaderboard(mode, LEADERBOARD_SIZE))
    }
}

const val LEADERBOARD_SIZE = 50
