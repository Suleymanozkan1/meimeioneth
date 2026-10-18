package com.lexiflow.server

import com.lexiflow.core.ai.LexiJson
import com.lexiflow.server.ai.AiInvalidOutputException
import com.lexiflow.server.ai.AiProvider
import com.lexiflow.server.ai.AiRefusedException
import com.lexiflow.server.ai.AiBudget
import com.lexiflow.server.ai.AiService
import com.lexiflow.server.ai.AiUnavailableException
import com.lexiflow.server.ai.AiUpstreamException
import com.lexiflow.server.ai.AnthropicProvider
import com.lexiflow.server.ai.DisabledAiProvider
import com.lexiflow.server.auth.JwtService
import com.lexiflow.server.battle.BattleManager
import com.lexiflow.server.routes.aiRoutes
import com.lexiflow.server.routes.authRoutes
import com.lexiflow.server.routes.battleRoutes
import com.lexiflow.server.routes.healthRoutes
import com.lexiflow.server.routes.leaderboardRoutes
import com.lexiflow.server.store.InMemoryStore
import com.lexiflow.server.store.PostgresStore
import com.lexiflow.server.store.Store
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.seconds

val RATE_LIMIT_AI = RateLimitName("ai")
val RATE_LIMIT_REGISTER = RateLimitName("register")
val RATE_LIMIT_LEADERBOARD = RateLimitName("leaderboard")
const val AUTH_JWT = "jwt"
const val AUTH_JWT_WS = "jwt-ws"

private val log = LoggerFactory.getLogger("com.lexiflow.server.Application")

/** Everything the module needs; tests build this directly with fakes and short timings. */
class ServerDeps(
    val config: AppConfig,
    val store: Store,
    val aiProvider: AiProvider,
    val nowMs: () -> Long = System::currentTimeMillis,
)

fun main() {
    val config = AppConfig.fromEnv()
    val store: Store = config.database?.let {
        log.info("Using PostgreSQL store")
        PostgresStore.create(it)
    } ?: InMemoryStore().also {
        log.info("DATABASE_URL not set: using in-memory store (data is lost on restart)")
    }
    val ai: AiProvider = if (config.aiEnabled) {
        log.info("AI provider: anthropic (model {})", config.anthropicModel)
        AnthropicProvider(
            apiKey = config.anthropicApiKey!!,
            model = config.anthropicModel,
            baseUrl = config.anthropicBaseUrl,
            useFallbacks = config.anthropicFallbacks,
        )
    } else {
        log.info("AI provider: none (AI endpoints return 503)")
        DisabledAiProvider()
    }
    log.info("Starting LexiFlow server: {}", config.describe())
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        lexiFlowModule(ServerDeps(config, store, ai))
    }.start(wait = true)
}

fun Application.lexiFlowModule(deps: ServerDeps) {
    val config = deps.config
    val store = deps.store
    val jwtService = JwtService(config.jwtSecret, deps.nowMs)
    val aiBudget = AiBudget(config.rateLimitAiPerSourcePerMinute, config.rateLimitAiGlobalPerMinute, deps.nowMs)
    val aiService = AiService(deps.aiProvider, aiBudget)
    val battleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val battle = BattleManager(store, config.battle, battleScope, deps.nowMs)

    monitor.subscribe(ApplicationStopped) {
        battleScope.cancel()
        deps.aiProvider.close()
        store.close()
    }

    install(ContentNegotiation) { json(LexiJson) }

    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path() != "/health" }
        // Path only: never log query strings (the WebSocket token may be there), headers or bodies.
        format { call -> "${call.response.status()?.value ?: "-"} ${call.request.httpMethod.value} ${call.request.path()}" }
    }

    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 60.seconds
        maxFrameSize = 16 * 1024L
        masking = false
    }

    install(Authentication) {
        val validateUser: suspend (JWTPrincipal) -> JWTPrincipal? = { principal ->
            val id = principal.subject
            if (id != null && store.findUser(id) != null) principal else null
        }
        jwt(AUTH_JWT) {
            realm = JwtService.REALM
            verifier(jwtService.verifier)
            validate { credential -> validateUser(JWTPrincipal(credential.payload)) }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ErrorBody("unauthorized", "Missing or invalid token")) }
        }
        jwt(AUTH_JWT_WS) {
            realm = JwtService.REALM
            verifier(jwtService.verifier)
            // The app sends the token in the Authorization header. `?token=` (old app versions) is only accepted when
            // ALLOW_WS_QUERY_TOKEN=true, because request URIs end up in proxy and framework logs.
            authHeader { call ->
                val queryToken = if (config.allowWsQueryToken) call.request.queryParameters["token"]?.takeIf { it.isNotBlank() } else null
                queryToken?.let { HttpAuthHeader.Single("Bearer", it) } ?: runCatching { call.request.parseAuthorizationHeader() }.getOrNull()
            }
            validate { credential -> validateUser(JWTPrincipal(credential.payload)) }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ErrorBody("unauthorized", "Missing or invalid token")) }
        }
    }

    install(RateLimit) {
        register(RATE_LIMIT_AI) {
            rateLimiter(limit = config.rateLimitAiPerMinute, refillPeriod = 60.seconds)
            requestKey { call -> call.principal<JWTPrincipal>()?.subject ?: call.request.origin.remoteHost }
        }
        register(RATE_LIMIT_LEADERBOARD) {
            rateLimiter(limit = 30, refillPeriod = 60.seconds)
            requestKey { call -> call.principal<JWTPrincipal>()?.subject ?: call.request.origin.remoteHost }
        }
        register(RATE_LIMIT_REGISTER) {
            rateLimiter(limit = config.rateLimitRegisterPerMinute, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }

    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ErrorBody(e.code, e.message)) }
        exception<AiUnavailableException> { call, _ ->
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorBody("ai_unavailable", "AI features are not enabled on this server. Use the offline content in the app."))
        }
        exception<AiInvalidOutputException> { call, _ ->
            call.respond(HttpStatusCode.BadGateway, ErrorBody("ai_invalid_output", "The AI returned content that failed validation. Please try again or use offline content."))
        }
        exception<AiRefusedException> { call, _ ->
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorBody("ai_refused", "The AI declined this request."))
        }
        exception<AiUpstreamException> { call, _ ->
            call.respond(HttpStatusCode.BadGateway, ErrorBody("ai_upstream_error", "The AI service is temporarily unavailable. Please try again later."))
        }
        exception<io.ktor.server.plugins.BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", "Malformed request"))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error on {} {}", call.request.httpMethod.value, call.request.path(), e)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("internal", "Internal server error"))
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respond(status, ErrorBody("rate_limited", "Too many requests, please slow down"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorBody("not_found", "Not found"))
        }
    }

    routing {
        healthRoutes(store, aiService.enabled)
        authRoutes(store, jwtService, deps.nowMs)
        authenticate(AUTH_JWT) {
            aiRoutes(aiService, aiBudget)
            leaderboardRoutes(store)
        }
        authenticate(AUTH_JWT_WS) {
            battleRoutes(battle)
        }
    }
}
