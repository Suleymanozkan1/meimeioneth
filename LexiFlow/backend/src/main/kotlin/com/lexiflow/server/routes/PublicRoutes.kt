package com.lexiflow.server.routes

import com.lexiflow.server.ApiException
import com.lexiflow.server.RATE_LIMIT_REGISTER
import com.lexiflow.server.auth.DisplayNameValidator
import com.lexiflow.server.auth.JwtService
import com.lexiflow.server.store.Store
import com.lexiflow.server.store.UserRecord
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class HealthResponse(val status: String, val ai: Boolean, val db: String)

@Serializable
data class RegisterRequest(val displayName: String)

@Serializable
data class RegisterResponse(val token: String, val playerId: String, val displayName: String)

fun Route.healthRoutes(store: Store, aiEnabled: Boolean) {
    get("/health") {
        call.respond(HealthResponse(status = "ok", ai = aiEnabled, db = store.kind))
    }
}

fun Route.authRoutes(store: Store, jwt: JwtService, nowMs: () -> Long) {
    rateLimit(RATE_LIMIT_REGISTER) {
        post("/v1/auth/register") {
            val body = call.receiveJson(RegisterRequest.serializer(), maxBytes = 4 * 1024)
            val name = DisplayNameValidator.normalize(body.displayName)
            DisplayNameValidator.validate(name)?.let { throw ApiException.badRequest(it, code = "invalid_display_name") }
            val user = UserRecord(id = UUID.randomUUID().toString(), displayName = name, createdAtMs = nowMs())
            store.createUser(user)
            call.respond(HttpStatusCode.Created, RegisterResponse(jwt.issue(user.id, user.displayName), user.id, user.displayName))
        }
    }
}
