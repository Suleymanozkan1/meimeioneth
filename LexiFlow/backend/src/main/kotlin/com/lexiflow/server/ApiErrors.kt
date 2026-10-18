package com.lexiflow.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/** Every error response body: `{"code": "...", "message": "..."}`. */
@Serializable
data class ErrorBody(val code: String, val message: String)

/** Thrown by routes/validators; mapped to its status + body by StatusPages. */
class ApiException(val status: HttpStatusCode, val code: String, override val message: String) : RuntimeException(message) {
    companion object {
        fun badRequest(message: String, code: String = "invalid_request") = ApiException(HttpStatusCode.BadRequest, code, message)
        fun tooLarge(message: String = "Request body too large") = ApiException(HttpStatusCode.PayloadTooLarge, "payload_too_large", message)
    }
}
