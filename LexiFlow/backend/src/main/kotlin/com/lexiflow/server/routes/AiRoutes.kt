package com.lexiflow.server.routes

import com.lexiflow.core.ai.AiFeedback
import com.lexiflow.core.ai.ChatRequest
import com.lexiflow.core.ai.ChatResponse
import com.lexiflow.core.ai.EnrichRequest
import com.lexiflow.core.ai.EnrichResponse
import com.lexiflow.core.ai.EvaluateRequest
import com.lexiflow.core.ai.StoryRequest
import com.lexiflow.core.ai.StoryResponse
import com.lexiflow.server.RATE_LIMIT_AI
import com.lexiflow.server.ai.AiInputLimits
import com.lexiflow.server.ai.AiService
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Must be mounted inside `authenticate(...)` so the rate limiter can key on the player id. */
fun Route.aiRoutes(ai: AiService) {
    rateLimit(RATE_LIMIT_AI) {
        route("/v1/ai") {
            post("/story") {
                val req = AiInputLimits.story(call.receiveJson(StoryRequest.serializer(), AiInputLimits.MAX_BODY_BYTES))
                val res: StoryResponse = ai.story(req)
                call.respond(res)
            }
            post("/evaluate") {
                val req = AiInputLimits.evaluate(call.receiveJson(EvaluateRequest.serializer(), AiInputLimits.MAX_BODY_BYTES))
                val res: AiFeedback = ai.evaluate(req)
                call.respond(res)
            }
            post("/chat") {
                val req = AiInputLimits.chat(call.receiveJson(ChatRequest.serializer(), AiInputLimits.MAX_BODY_BYTES))
                val res: ChatResponse = ai.chat(req)
                call.respond(res)
            }
            post("/enrich") {
                val req = AiInputLimits.enrich(call.receiveJson(EnrichRequest.serializer(), AiInputLimits.MAX_BODY_BYTES))
                val res: EnrichResponse = ai.enrich(req)
                call.respond(res)
            }
        }
    }
}
