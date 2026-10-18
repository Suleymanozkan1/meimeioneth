package com.lexiflow.server

import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64

data class DatabaseConfig(val url: String, val user: String?, val password: String?) {
    /** Never print credentials. */
    override fun toString(): String = "DatabaseConfig(url=<redacted>, user=<redacted>, password=<redacted>)"
}

/** Tunables for the online word battle. Tests shorten the timings. */
data class BattleSettings(
    /** When set, overrides BattleMode.roundSeconds (tests use 1-2 s rounds). */
    val roundSecondsOverride: Int? = null,
    /** How long to wait for a 3rd/4th player once two players are queued. */
    val matchWindowMs: Long = 3_000,
    /** Submissions are still accepted this long after endsAt (network latency). */
    val graceMs: Long = 3_000,
    val maxPlayersPerRoom: Int = 4,
    /** Per-connection rate limit: at most [messageLimit] messages per [messageWindowMs]. */
    val messageLimit: Int = 20,
    val messageWindowMs: Long = 10_000,
    /** After this many rate-limit violations inside one window the socket is closed. */
    val maxViolations: Int = 3,
    /** Finished rooms are kept this long so late messages get a precise answer ("late", stored result). */
    val finishedRoomRetentionMs: Long = 10 * 60_000,
    /** A win only counts for the leaderboard if at least one opponent reached this task-completion score. */
    val minRankedOpponentTaskCompletion: Int = 60,
    /** Leaderboard wins a player can earn per UTC day against the same group of opponents. */
    val maxCreditedWinsPerOpponentSet: Int = 3,
    /** Leaderboard wins a player can earn per UTC day in total. */
    val maxCreditedWinsPerDay: Int = 30,
)

data class AppConfig(
    val port: Int = 8080,
    val jwtSecret: String,
    val jwtSecretGenerated: Boolean = false,
    val database: DatabaseConfig? = null,
    val aiProvider: String = "none",
    val anthropicApiKey: String? = null,
    val anthropicModel: String = DEFAULT_ANTHROPIC_MODEL,
    val anthropicBaseUrl: String = "https://api.anthropic.com",
    /** Server-side refusal fallback (`fallbacks: "default"`); disable with ANTHROPIC_FALLBACKS=off. */
    val anthropicFallbacks: Boolean = true,
    val rateLimitAiPerMinute: Int = 20,
    /** Upper bound on model calls per minute across all players, so free new accounts cannot multiply operator spend. */
    val rateLimitAiGlobalPerMinute: Int = 300,
    /** AI requests per minute per client address, summed over every account used from that address. */
    val rateLimitAiPerSourcePerMinute: Int = 60,
    val rateLimitRegisterPerMinute: Int = 10,
    /** Legacy clients sent the battle JWT as `?token=`; off by default because URLs end up in logs. */
    val allowWsQueryToken: Boolean = false,
    val battle: BattleSettings = BattleSettings(),
) {
    val aiEnabled: Boolean get() = aiProvider == "anthropic" && !anthropicApiKey.isNullOrBlank()

    /** Safe summary for logs: no secrets, no URLs with credentials. */
    fun describe(): String =
        "port=$port, db=${if (database == null) "memory" else "postgres"}, ai=$aiProvider" +
            (if (aiProvider == "anthropic") ", model=$anthropicModel, keyPresent=${!anthropicApiKey.isNullOrBlank()}" else "") +
            ", aiRateLimit=$rateLimitAiPerMinute/min (per source $rateLimitAiPerSourcePerMinute, global $rateLimitAiGlobalPerMinute)" +
            ", jwtSecret=${if (jwtSecretGenerated) "generated" else "provided"}"

    override fun toString(): String = "AppConfig(${describe()})"

    companion object {
        const val DEFAULT_ANTHROPIC_MODEL = "claude-opus-5-5"
        const val MIN_SECRET_LENGTH = 32
        private val log = LoggerFactory.getLogger("com.lexiflow.server.Config")

        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun value(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            fun positiveInt(name: String): Int? = value(name)?.toIntOrNull()?.takeIf { it > 0 }

            val port = value("PORT")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8080

            val providedSecret = value("JWT_SECRET")
            val secret: String
            val generated: Boolean
            if (providedSecret == null) {
                secret = randomSecret()
                generated = true
                log.warn("JWT_SECRET is not set: generated a random secret for this process. Issued tokens will NOT survive a restart; set JWT_SECRET (>= $MIN_SECRET_LENGTH chars) in production.")
            } else {
                require(providedSecret.length >= MIN_SECRET_LENGTH) { "JWT_SECRET must be at least $MIN_SECRET_LENGTH characters long" }
                require(!looksLikePlaceholder(providedSecret)) {
                    "JWT_SECRET looks like an example placeholder; generate a real one, e.g. openssl rand -base64 48"
                }
                require(providedSecret.toSet().size >= MIN_DISTINCT_SECRET_CHARS) { "JWT_SECRET has too little entropy; generate a random one" }
                secret = providedSecret
                generated = false
            }

            val database = value("DATABASE_URL")?.let { DatabaseConfig(it, value("DB_USER"), value("DB_PASSWORD")) }

            val aiProvider = when (val p = value("AI_PROVIDER")?.lowercase() ?: "none") {
                "anthropic", "none" -> p
                else -> {
                    log.warn("Unknown AI_PROVIDER value; AI features are disabled (allowed: anthropic, none).")
                    "none"
                }
            }
            val apiKey = value("ANTHROPIC_API_KEY")
            if (aiProvider == "anthropic" && apiKey == null) {
                log.warn("AI_PROVIDER=anthropic but ANTHROPIC_API_KEY is not set: AI features are disabled.")
            }

            return AppConfig(
                port = port,
                jwtSecret = secret,
                jwtSecretGenerated = generated,
                database = database,
                aiProvider = aiProvider,
                anthropicApiKey = apiKey,
                anthropicModel = value("ANTHROPIC_MODEL") ?: DEFAULT_ANTHROPIC_MODEL,
                anthropicBaseUrl = value("ANTHROPIC_BASE_URL")?.trimEnd('/') ?: "https://api.anthropic.com",
                anthropicFallbacks = value("ANTHROPIC_FALLBACKS")?.lowercase() != "off",
                rateLimitAiPerMinute = positiveInt("RATE_LIMIT_AI_PER_MINUTE") ?: 20,
                rateLimitAiGlobalPerMinute = positiveInt("RATE_LIMIT_AI_GLOBAL_PER_MINUTE") ?: 300,
                rateLimitAiPerSourcePerMinute = positiveInt("RATE_LIMIT_AI_PER_SOURCE_PER_MINUTE") ?: 60,
                rateLimitRegisterPerMinute = positiveInt("RATE_LIMIT_REGISTER_PER_MINUTE") ?: 10,
                allowWsQueryToken = value("ALLOW_WS_QUERY_TOKEN")?.lowercase() == "true",
            )
        }

        const val MIN_DISTINCT_SECRET_CHARS = 10
        private val placeholderMarkers = listOf("replace-with", "replace_me", "replaceme", "change-me", "changeme", "your-", "placeholder", "example", "secret-here")

        /** Values copied unchanged from documentation or `.env.example` are public knowledge, never a secret. */
        fun looksLikePlaceholder(value: String): Boolean {
            val v = value.lowercase()
            return placeholderMarkers.any { it in v }
        }

        fun randomSecret(): String {
            val bytes = ByteArray(48)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
