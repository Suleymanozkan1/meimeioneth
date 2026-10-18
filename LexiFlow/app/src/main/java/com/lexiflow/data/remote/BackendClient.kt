package com.lexiflow.data.remote

import android.util.Log
import com.lexiflow.core.ai.AiFeedback
import com.lexiflow.core.ai.AiValidation
import com.lexiflow.core.ai.ChatRequest
import com.lexiflow.core.ai.ChatResponse
import com.lexiflow.core.ai.EnrichRequest
import com.lexiflow.core.ai.EnrichResponse
import com.lexiflow.core.ai.EvaluateRequest
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.ai.StoryRequest
import com.lexiflow.core.ai.StoryResponse
import com.lexiflow.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class RemoteError(val messageTr: String) : Exception(messageTr) {
    class NotConfigured : RemoteError("Sunucu adresi ayarlanmamış. Ayarlar → Sunucu bölümünden LexiFlow sunucu adresini gir. Bu özellik çevrim dışı modda yerel alternatifle çalışır.")
    class Network(cause: Throwable) : RemoteError("Sunucuya ulaşılamadı (${cause.javaClass.simpleName}). İnternet bağlantını kontrol et.")
    class Unauthorized : RemoteError("Oturum geçersiz. Ayarlar → Sunucu → \"Bağlan\" ile yeniden kayıt ol.")
    class AiUnavailable : RemoteError("Sunucuda AI sağlayıcısı etkin değil (API anahtarı yapılandırılmamış).")
    class RateLimited : RemoteError("Çok fazla istek gönderildi. Biraz sonra tekrar dene.")
    class InvalidOutput(detail: String) : RemoteError("AI çıktısı doğrulanamadı ($detail). Yerel içerik kullanılıyor.")
    class Server(code: Int) : RemoteError("Sunucu hatası ($code).")
}

@Serializable data class RegisterRequest(val displayName: String)
@Serializable data class RegisterResponse(val token: String, val playerId: String, val displayName: String)
@Serializable data class HealthResponse(val status: String, val ai: Boolean = false, val db: String? = null)
@Serializable data class LeaderboardEntry(
    val displayName: String,
    val wins: Int = 0,
    val games: Int = 0,
    val averageScore: Double = 0.0,
)
@Serializable data class ReportBody(val roomId: String, val playerId: String, val reason: String)

/**
 * Talks to the LexiFlow backend. No API keys live in the app: the backend holds provider secrets and the app
 * only carries an anonymous JWT. Every AI response is validated again on device before use.
 */
class BackendClient(private val settings: SettingsRepository, private val http: OkHttpClient = defaultClient()) {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun baseUrl(): String {
        val url = settings.current().backendUrl.trim().trimEnd('/')
        if (url.isEmpty()) throw RemoteError.NotConfigured()
        return url
    }

    suspend fun health(): HealthResponse = get("/health", HealthResponse.serializer(), auth = false)

    suspend fun register(displayName: String): RegisterResponse {
        val r = post("/v1/auth/register", RegisterRequest(displayName), RegisterRequest.serializer(), RegisterResponse.serializer(), auth = false)
        settings.update { it.copy(serverToken = r.token, playerId = r.playerId, displayName = r.displayName) }
        return r
    }

    suspend fun story(req: StoryRequest): StoryResponse {
        val r = post("/v1/ai/story", req, StoryRequest.serializer(), StoryResponse.serializer())
        val problems = AiValidation.validateStory(r, req)
        if (problems.isNotEmpty()) throw RemoteError.InvalidOutput(problems.first())
        return r
    }

    suspend fun evaluate(req: EvaluateRequest): AiFeedback {
        val r = post("/v1/ai/evaluate", req, EvaluateRequest.serializer(), AiFeedback.serializer())
        AiValidation.validateFeedback(r).firstOrNull()?.let { throw RemoteError.InvalidOutput(it) }
        return r
    }

    suspend fun chat(req: ChatRequest): ChatResponse {
        val r = post("/v1/ai/chat", req, ChatRequest.serializer(), ChatResponse.serializer())
        AiValidation.validateChat(r).firstOrNull()?.let { throw RemoteError.InvalidOutput(it) }
        return r
    }

    suspend fun enrich(req: EnrichRequest): EnrichResponse {
        val r = post("/v1/ai/enrich", req, EnrichRequest.serializer(), EnrichResponse.serializer())
        AiValidation.validateEnrich(r).firstOrNull()?.let { throw RemoteError.InvalidOutput(it) }
        return r
    }

    suspend fun leaderboard(mode: String): List<LeaderboardEntry> = withContext(Dispatchers.IO) {
        val body = rawGet("/v1/leaderboard?mode=$mode", auth = true)
        val element = LexiJson.parseToJsonElement(body)
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["entries"] ?: element["leaderboard"] ?: JsonArray(emptyList())).jsonArray
            else -> JsonArray(emptyList())
        }
        LexiJson.decodeFromJsonElement(ListSerializer(LeaderboardEntry.serializer()), array)
    }

    /** The token travels in the Authorization header, never in the URL (URLs end up in proxy/server logs). */
    suspend fun battleSocketTarget(): BattleSocketTarget {
        val token = settings.current().serverToken ?: throw RemoteError.Unauthorized()
        val base = baseUrl().replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        return BattleSocketTarget("$base/v1/battle", token)
    }

    private suspend fun <T> get(path: String, out: KSerializer<T>, auth: Boolean = true): T = withContext(Dispatchers.IO) {
        LexiJson.decodeFromString(out, rawGet(path, auth))
    }

    private suspend fun rawGet(path: String, auth: Boolean): String {
        val builder = Request.Builder().url(baseUrl() + path).get()
        if (auth) builder.header("Authorization", "Bearer ${token()}")
        return execute(builder.build())
    }

    private suspend fun <I, O> post(path: String, body: I, inS: KSerializer<I>, outS: KSerializer<O>, auth: Boolean = true): O = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(baseUrl() + path).post(LexiJson.encodeToString(inS, body).toRequestBody(jsonType))
        if (auth) builder.header("Authorization", "Bearer ${token()}")
        val text = execute(builder.build())
        try {
            LexiJson.decodeFromString(outS, text)
        } catch (e: Exception) {
            throw RemoteError.InvalidOutput("JSON şeması uyuşmadı")
        }
    }

    private suspend fun token(): String = settings.current().serverToken ?: throw RemoteError.Unauthorized()

    private fun execute(request: Request): String {
        try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                return when (resp.code) {
                    in 200..299 -> text
                    401, 403 -> throw RemoteError.Unauthorized()
                    429 -> throw RemoteError.RateLimited()
                    503 -> throw RemoteError.AiUnavailable()
                    502 -> throw RemoteError.InvalidOutput("sunucu doğrulaması başarısız")
                    else -> throw RemoteError.Server(resp.code)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "network error ${request.url.encodedPath}: ${e.javaClass.simpleName}")
            throw RemoteError.Network(e)
        }
    }

    companion object {
        private const val TAG = "BackendClient"
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
