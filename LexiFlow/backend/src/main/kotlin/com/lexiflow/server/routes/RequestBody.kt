package com.lexiflow.server.routes

import com.lexiflow.core.ai.LexiJson
import com.lexiflow.server.ApiException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer

/** Reads a JSON body with a hard byte limit (also enforced for chunked bodies without Content-Length). */
suspend fun <T> ApplicationCall.receiveJson(serializer: KSerializer<T>, maxBytes: Long): T {
    val declared = request.contentLength()
    if (declared != null && declared > maxBytes) throw ApiException.tooLarge()
    val bytes = receiveChannel().readRemaining(maxBytes + 1).readByteArray()
    if (bytes.size > maxBytes) throw ApiException.tooLarge()
    if (bytes.isEmpty()) throw ApiException.badRequest("Request body must be a JSON object", code = "bad_request")
    return try {
        LexiJson.decodeFromString(serializer, bytes.decodeToString())
    } catch (e: Exception) {
        // Do not echo the body back; a short reason is enough for client developers.
        throw ApiException.badRequest("Malformed JSON or wrong shape for this endpoint", code = "bad_request")
    }
}
