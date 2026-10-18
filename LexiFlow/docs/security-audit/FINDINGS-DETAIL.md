# Findings Detail (confirmed medium/high/critical)

## Committed .env.example JWT_SECRET placeholder is accepted as the production signing key, allowing forged tokens for any existing player

`lexiflow-backend-env-example-jwt-secret-placeholder-accepted` — **medium**

The documented docker deployment starts with `cp .env.example .env`. The committed example sets JWT_SECRET=replace-with-a-long-random-secret-at-least-32-chars, which is 51 characters. AppConfig.fromEnv checks only that a provided secret is at least 32 characters, so this public value becomes the HS256 key. The server logs only `jwtSecret=provided` and gives no warning. docker-compose.yml forces DB_PASSWORD to be present with ':?' but has no JWT_SECRET guard, and its `environment:` block does not override JWT_SECRET. Anyone who has read the repository can sign a token whose sub is any existing playerId and whose `name` claim is arbitrary. PlayerIds are public in GET /v1/leaderboard rows and in round_result PlayerResult entries. The server accepts such a token on every authenticated route. On the battle WebSocket the attacker joins, submits, reports and wins or loses as the victim. On the AI routes, the per-subject rate limit is charged to the victim. BattleRoutes takes displayName straight from the token's name claim, and BattleManager broadcasts it and stores it in BattleResultRecord without running DisplayNameValidator, so names that registration would reject reach co-players and stored results. A token for a subject that does not exist is still rejected, so the attacker can only impersonate existing players.

### Trace
1. `LexiFlow/backend/.env.example:9` (entrypoint, JWT_SECRET placeholder) — The committed example sets JWT_SECRET=replace-with-a-long-random-secret-at-least-32-chars (51 chars). README line 22 copies this file verbatim to .env. Anyone who reads the public repository learns the value.
2. `LexiFlow/backend/docker-compose.yml:26` (propagation, services.server.env_file) — `env_file: .env` passes JWT_SECRET to the server unchanged. The `environment:` block (lines 27-31) does not override it. Unlike DB_PASSWORD (line 11, ':?' guard), JWT_SECRET has no compose guard.
3. `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/Config.kt:73` (propagation, AppConfig.fromEnv) — The only check on a provided secret is require(providedSecret.length >= 32). The placeholder passes, generated is set to false, and nothing is logged beyond describe() printing 'jwtSecret=provided'.
4. `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/Application.kt:101` (propagation, lexiFlowModule) — JwtService(config.jwtSecret) builds the HMAC256 signer and verifier from the placeholder.
5. `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/Application.kt:131` (propagation, Authentication validateUser) — After the signature and the iss/aud/name-presence checks, the only remaining check is that sub names an existing user. A forged token for a known victim id passes.
6. `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/routes/BattleRoutes.kt:31` (sink, webSocket /v1/battle) — playerId comes from the forged sub (line 30) and displayName from the forged name claim (line 31), and both go into PlayerConnection. The connection acts as the victim in queue, submit, report and results.

### Evidence
- `LexiFlow/backend/.env.example:9` — Placeholder secret that passes the length check (51 chars).
- `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/Config.kt:68` — Random secret generation, the fail-closed path, applies only when the value is null or empty, not when it is the placeholder.
- `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/Config.kt:73` — Length-only validation. Sandbox: fromEnv accepts the placeholder (generated=false) and accepts 32 x 'a', so there is no entropy check. It rejects a 31-character value.
- `LexiFlow/backend/README.md:22` — Documented deploy step `cp .env.example .env` (with 'fill in the values'). Line 110 states the example holds only placeholders, but nothing enforces replacing them.
- `LexiFlow/backend/docker-compose.yml:11` — Compose forces DB_PASSWORD to be present but has no JWT_SECRET guard. Neither guard rejects placeholder values.
- `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/auth/JwtService.kt:11` — HMAC256 over the configured secret. Anyone who knows the secret can mint tokens accepted by the verifier (lines 13-18).
- `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/Application.kt:155` — The AI rate-limit key is the JWT subject, so forged-token AI calls use the victim's quota.
- `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/battle/BattleManager.kt:274` — The token-supplied displayName is stored in BattleResultRecord. It was also broadcast earlier (lines 90, 213, 266) and is never re-validated by DisplayNameValidator, which runs only at registration (PublicRoutes.kt line 38).

### Principals
- Attacker: Unauthenticated network client who has read the public repository, against a LexiFlow backend deployed with the .env.example JWT_SECRET left in place.
- Intended behaviour: When JWT_SECRET equals the committed placeholder or another known or low-entropy example value, the server should refuse to start, or fall back to a random per-process secret with a warning. Only an operator-generated secret should be able to sign accepted tokens.

### Native input / fixture
- `JWT_SECRET=replace-with-a-long-random-secret-at-least-32-chars`
- `HS256 JWT {iss:"lexiflow", aud:"lexiflow-app", sub:"<victim playerId>", name:"Mallory", iat, exp} signed with the placeholder (java-jwt Algorithm.HMAC256)`
- `GET /v1/leaderboard  Authorization: Bearer <forged>`
- `WS ws://127.0.0.1:18081/v1/battle?token=<forged>`

### Bounded instructions
1. Inside tools/sandbox.sh (loopback only, 2 GiB, 256 pids, CPU 120 s), call AppConfig.Companion.fromEnv(Map.of("JWT_SECRET", <placeholder>)) and observe that it returns without throwing and with jwtSecretGenerated=false.
2. Start the real server main com.lexiflow.server.ApplicationKt from /opt/lexiflow-audit-cp with env -i JWT_SECRET=<placeholder> PORT=18081 AI_PROVIDER=none (in-memory store).
3. POST /v1/auth/register {"displayName":"Victim"} and read playerId.
4. Sign three tokens with name=Mallory: a control token with a different secret and sub=victim, a forged token with the placeholder and sub=victim, and a forged token with the placeholder and a nonexistent sub.
5. Call GET /v1/leaderboard with each token. Then open /v1/battle with the forged token and read the first frame, and attempt the same with the control token.

### Observed output
fromEnv(placeholder) gave jwtSecretGenerated=false. fromEnv rejected a 31-character value and accepted 32 x 'a'. The server log showed 'Starting LexiFlow server: port=18081, db=memory, ai=none, aiRateLimit=20/min, jwtSecret=provided' and no JWT warning. Registering Victim returned 201 with playerId=a8855df0-c18d-4b75-b498-89f62b6fe1e2. On /v1/leaderboard: control token -> 401; placeholder-forged token for the victim -> 200; placeholder-forged token for a nonexistent sub -> 401. The WebSocket first frame for the forged token was {"type":"welcome","playerId":"a8855df0-c18d-4b75-b498-89f62b6fe1e2","displayName":"Mallory","onlinePlayers":1}. The control-token WebSocket handshake was refused. Not exercised: the AI routes (AI_PROVIDER=none) and full battle submit/report as the victim. Those effects come from source only.

### Conditions and containment
- [system_configuration] The operator follows the documented `cp .env.example .env` path and leaves the JWT_SECRET line unchanged, despite the 'replace the placeholders' comments in .env.example line 1, docker-compose.yml line 2 and README line 22. Nothing at startup flags it.
- [data_state] The victim's playerId is known and the victim exists in the store. Ids are returned by GET /v1/leaderboard and in round_result PlayerResult entries to co-players. Tokens for nonexistent subjects are rejected.
- [authentication_level] None beyond the public repository contents and network reach to the server. To read leaderboard ids, an attacker can also register a free account.

### Remediation
Stop shipping a secret value that passes validation. Leave JWT_SECRET empty in .env.example, which makes the server generate a random secret and log a warning. In Config.fromEnv, reject known placeholders and obviously low-entropy values. Optionally add a compose guard (e.g. JWT_SECRET: ${JWT_SECRET:?Set JWT_SECRET}) together with the code check. Regression test: AppConfig.fromEnv(mapOf("JWT_SECRET" to "replace-with-a-long-random-secret-at-least-32-chars")) must throw, and so must fromEnv(mapOf("JWT_SECRET" to "a".repeat(32))).

`LexiFlow/backend/src/main/kotlin/com/lexiflow/server/Config.kt`
```kotlin
private val KNOWN_PLACEHOLDER_SECRETS = setOf("replace-with-a-long-random-secret-at-least-32-chars")
...
} else {
    require(providedSecret.length >= MIN_SECRET_LENGTH) { "JWT_SECRET must be at least $MIN_SECRET_LENGTH characters long" }
    require(providedSecret !in KNOWN_PLACEHOLDER_SECRETS && !providedSecret.startsWith("replace-with")) { "JWT_SECRET is the example placeholder; generate one with: openssl rand -base64 48" }
    require(providedSecret.toSet().size >= 10) { "JWT_SECRET has too little entropy" }
    secret = providedSecret
    generated = false
}
```

`LexiFlow/backend/.env.example`
```kotlin
# HS256 signing secret, at least 32 random characters: openssl rand -base64 48
# The server refuses example values. Leave empty only for throwaway local runs (random per-process secret).
JWT_SECRET=
```

## Operator-paid AI budget escapes the per-player limit through free, unlimited identity minting

`lexiflow-backend:RATE_LIMIT_AI:per-subject-quota-escape-via-free-registration` — **medium**

The only control on operator-paid Claude calls is a 20/min token bucket keyed on the JWT subject (Application.kt:154-155). Subjects are minted anonymously by POST /v1/auth/register (gated only by a 10/min per-remoteHost register limit, no proof of personhood), and each new token is valid for 90 days and immediately gets its own fresh 20/min AI budget. There is no per-source, global, daily or spend ceiling in Application.kt's install(RateLimit) block (only RATE_LIMIT_AI and RATE_LIMIT_REGISTER) or in Config.kt. One anonymous requester from a single source address can therefore grow its sustained AI throughput by ~200 calls/min for each minute spent registering (10 new identities/min x 20 calls/min each), against the operator's ANTHROPIC_API_KEY, with up to 2 billed provider calls per counted request (corrective retry). The result is unbounded operator spend, or exhaustion of the operator's shared provider quota so AI is denied to every legitimate player.

### Trace
1. `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/routes/PublicRoutes.kt:34` (entrypoint, authRoutes POST /v1/auth/register) — Anonymous registration, gated only by RATE_LIMIT_REGISTER (10/min keyed on origin.remoteHost).
2. `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/routes/PublicRoutes.kt:41` (propagation, authRoutes POST /v1/auth/register) — Issues a JWT for a fresh random UUID subject per call.
3. `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/auth/JwtService.kt:37` (propagation, JwtService.TOKEN_TTL_MS) — Each minted token stays valid for 90 days, so identities accumulate.
4. `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/Application.kt:155` (propagation, install(RateLimit) register(RATE_LIMIT_AI) requestKey) — The AI token bucket (20/min) is keyed only on the JWT subject, so every minted subject gets an independent full budget.
5. `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/routes/AiRoutes.kt:22` (propagation, aiRoutes rateLimit(RATE_LIMIT_AI)) — The only accounting wrapper around /v1/ai/{story,evaluate,chat,enrich}.
6. `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/ai/AiService.kt:61` (propagation, AiService.generate) — Each counted request runs up to 2 billed provider.complete calls (corrective retry on invalid output).
7. `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/ai/AnthropicProvider.kt:86` (sink, AnthropicProvider.complete) — POST to the provider's /v1/messages with the operator's x-api-key, retried up to 2 more times on 408/409/429/5xx and network errors.

### Evidence
- `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/Application.kt:154` — rateLimiter(limit = config.rateLimitAiPerMinute, refillPeriod = 60.seconds) is the only AI limiter; no global or per-source limiter is registered.
- `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/Application.kt:159` — Registration is keyed only on origin.remoteHost at 10/min, so it does not tie identities to an AI budget.
- `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/Config.kt:102` — RATE_LIMIT_AI_PER_MINUTE is the only spend knob, and it is per user; no aggregate or spend ceiling exists in config.
- `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/ai/AnthropicProvider.kt:118` — Retryable statuses (408/409/429/5xx) are retried up to maxRetries=2 per call, uncounted by the limiter.
- `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/Application.kt:129` — validateUser accepts any subject that exists in the store; no account age, activity or per-source binding.

### Principals
- Attacker: Anonymous internet client with no prior account, scripting plain HTTP requests from a single source address.
- Intended behaviour: Operator AI spend should be bounded per real requester and in aggregate. A requester that creates N identities should not get N times the AI budget, and total upstream calls should stay under an operator-configured global ceiling regardless of how many subjects exist.

### Native input / fixture
- `POST /v1/auth/register {"displayName":"Player A"} (repeated with distinct display names from one source IP)`
- `POST /v1/ai/enrich with header Authorization: Bearer <token_i> and body {"lemma":"apple","meaningTr":"elma","cefr":"B1"} (21 times for each of several tokens)`

### Bounded instructions
1. Inside tools/sandbox.sh (loopback-only netns, mem 2 GiB, pids 256, CPU 120 s, wall<=300 s), start a Python stub on 127.0.0.1:19011 that answers /v1/messages with a valid enrich JSON and counts hits.
2. Start com.lexiflow.server.ApplicationKt from /opt/lexiflow-audit-cp with PORT=18090, JWT_SECRET of 40 'x', AI_PROVIDER=anthropic, ANTHROPIC_API_KEY=dummy-not-a-real-key, ANTHROPIC_BASE_URL=http://127.0.0.1:19011, ANTHROPIC_FALLBACKS=off, and no DATABASE_URL.
3. Register several identities from 127.0.0.1, then send 21 enrich calls with each of 3 tokens, recording statuses and stub hit counts.

### Observed output
Independently reproduced by verifier v3-12 (evidence-1.txt): from one source IP, 6 registrations returned 201 x6 (6<10/min register limit). Each of 3 identities got 200 x20 and 429 x1 on /v1/ai/enrich, i.e. 60 billed upstream stub hits from one source in under a minute against a 20/min per-subject limit, using only 3 of the free identities minted that minute. Matches hunter h-spend evidence-1. Hunter h-spend evidence-2 separately showed up to 2 billed provider calls per counted request (corrective retry) and 3 upstream calls per request when the upstream always errors (maxRetries), a bounded contributing multiplier.

### Conditions and containment
- [system_configuration] AI_PROVIDER=anthropic with ANTHROPIC_API_KEY set (the default is none, under which AI routes return 503).
- [authentication_level] None beyond self-service anonymous registration; any internet client can mint tokens.
- [network_routing] Per-IP registration rate applies when the server sees client IPs directly (compose publishes 8080). Behind a reverse proxy without forwarded-header handling, registration is limited globally at 10/min, which still yields 14,400 new full-budget identities per day. Additional source IPs (e.g. IPv6) multiply the per-IP figure.
- [third_party_dependency] Actual monetary loss depends on the operator's provider spend limits outside the repository. If a provider-side cap exists, the same path instead exhausts it and denies AI to all players. Either outcome is a meaningful shared effect; the quota-escape mechanism itself is fully source-proven and does not depend on any unknown deployed control.

### Remediation
Add aggregate accounting that new identities cannot reset. Use an operator-configured global AI ceiling and a per-source (trusted client IP) AI budget, layered over the existing per-subject RATE_LIMIT_AI bucket. Count every upstream provider attempt, including the corrective retry and the HTTP retries, against the global ceiling. Do NOT implement the extra limits as nested rateLimit(...) blocks. In Ktor 3.0.3, Route.rateLimit installs the route-scoped RateLimitInterceptors with a single provider name. Core's addAllInterceptors runs a route-scoped interceptor only when findPluginInRoute(call.route) returns that same instance, which is the innermost one, so outer nested limiters are silently skipped. Enforce the extra budgets in one explicit guard instead. Optionally add a persisted daily per-subject quota and a minimum account age or activity before AI access. Make the registration limit configurable and keyed on a trusted forwarded client address behind proxies. Bound or evict the per-source map.

`LexiFlow/backend/src/main/kotlin/com/lexiflow/server/ai/AiBudget.kt`
```kotlin
package com.lexiflow.server.ai

import java.util.concurrent.ConcurrentHashMap

/** Aggregate AI budget that minting new identities cannot reset. */
class AiBudget(
    private val globalUpstreamPerMinute: Int,
    private val perSourcePerMinute: Int,
    private val nowMs: () -> Long,
) {
    private class Window(var startMs: Long, var count: Int)
    private val global = Window(0, 0)
    private val sources = ConcurrentHashMap<String, Window>()

    private fun take(w: Window, limit: Int): Boolean = synchronized(w) {
        val now = nowMs()
        if (now - w.startMs >= 60_000) { w.startMs = now; w.count = 0 }
        if (w.count >= limit) false else { w.count++; true }
    }

    /** One per counted AI request, keyed on the trusted client address. */
    fun tryAcquireSource(source: String): Boolean {
        if (sources.size > 100_000) sources.entries.removeIf { nowMs() - it.value.startMs >= 60_000 }
        return take(sources.computeIfAbsent(source) { Window(nowMs(), 0) }, perSourcePerMinute)
    }

    /** One per billed upstream attempt (initial, corrective retry and HTTP retries). */
    fun tryAcquireUpstream(): Boolean = take(global, globalUpstreamPerMinute)
}
```

`LexiFlow/backend/src/main/kotlin/com/lexiflow/server/routes/AiRoutes.kt`
```kotlin
/** Keep the single per-subject rateLimit(RATE_LIMIT_AI); nested rateLimit blocks would only apply the innermost limiter in Ktor 3.0.3. */
fun Route.aiRoutes(ai: AiService, budget: AiBudget) {
    rateLimit(RATE_LIMIT_AI) {
        route("/v1/ai") {
            intercept(ApplicationCallPipeline.Call) {
                if (!budget.tryAcquireSource(call.request.origin.remoteHost)) {
                    throw ApiException(HttpStatusCode.TooManyRequests, "rate_limited", "Too many requests, please slow down")
                }
            }
            /* existing story/evaluate/chat/enrich handlers unchanged */
        }
    }
}
// Regression: register 3 identities from one source. With a per-source limit S, the (S+1)th AI call across all
// identities within a minute must return 429, and the stub hit count must stay <= the global upstream ceiling.
```

`LexiFlow/backend/src/main/kotlin/com/lexiflow/server/ai/AnthropicProvider.kt`
```kotlin
// constructor gains: private val budget: AiBudget? = null
// in complete(), inside while (true), before client.post("$baseUrl/v1/messages") { ... }:
if (budget != null && !budget.tryAcquireUpstream()) {
    throw AiUpstreamException("AI budget exhausted", 429)
}
// Application.kt: build one AiBudget from new config values (RATE_LIMIT_AI_GLOBAL_PER_MINUTE,
// RATE_LIMIT_AI_PER_SOURCE_PER_MINUTE), pass it to AnthropicProvider and to aiRoutes(aiService, aiBudget).
// Keep install(RateLimit) { RATE_LIMIT_AI; RATE_LIMIT_REGISTER } as is, with the register limit read from env.
```

