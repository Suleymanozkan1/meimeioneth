package com.lexiflow.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date
import java.util.concurrent.TimeUnit

/** HS256 tokens for anonymous accounts. `sub` = playerId, `name` = display name. */
class JwtService(secret: String, private val nowMs: () -> Long = System::currentTimeMillis) {
    private val algorithm = Algorithm.HMAC256(secret)

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .withClaimPresence(CLAIM_NAME)
        .acceptLeeway(30)
        .build()

    fun issue(playerId: String, displayName: String): String {
        val now = nowMs()
        return JWT.create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withSubject(playerId)
            .withClaim(CLAIM_NAME, displayName)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + TOKEN_TTL_MS))
            .sign(algorithm)
    }

    companion object {
        const val ISSUER = "lexiflow"
        const val AUDIENCE = "lexiflow-app"
        const val CLAIM_NAME = "name"
        const val REALM = "lexiflow"
        val TOKEN_TTL_MS: Long = TimeUnit.DAYS.toMillis(90)
    }
}
