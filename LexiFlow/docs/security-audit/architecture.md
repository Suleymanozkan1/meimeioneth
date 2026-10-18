# Architecture — suleymanozkan1/meimeioneth @ 0cf6d494 (profile: standard)

## 1. Product, principals, resources
- **LexiFlow** (`LexiFlow/`): offline-first Android vocabulary app for Turkish learners (FSRS reviews, Anki/CSV import, lock-screen questions, stories, speaking coach, online word battle), plus an optional self-hosted Ktor backend that proxies AI calls and runs the battle.
- **Principals:**
  - anonymous internet client (`/health`, `/v1/auth/register`);
  - registered player holding a 90-day HS256 JWT (AI proxy, leaderboard, `/v1/battle` WebSocket);
  - co-player in a battle room;
  - other Android apps on the device (exported `MainActivity` with the `lexiflow://` scheme);
  - a lock-screen user who has not unlocked the phone (notification actions);
  - authors of imported files (.apkg/.csv/.srt) and of model output;
  - the operator (env secrets);
  - a WordPress admin (legacy theme options).
- **Protected resources:**
  - the operator's `ANTHROPIC_API_KEY` spend and `JWT_SECRET`;
  - player identity, display names, battle texts, reports and leaderboard integrity;
  - the device-local learning DB and the stored JWT;
  - server availability.
- **Legacy root:** a "Mei Mei on ETH" WordPress theme plus a Vite/React landing page. Neither has user-input handlers.

## 2. Comparable baseline
- FSRS is ported from fsrs-rs. `.apkg` reading is interoperability with Anki (read-only, legacy collection only).
- The AI proxy follows the usual "server holds the key, app holds a per-user token" pattern.
- No comparable software defines the battle protocol; it is custom.

## 3. Stack, deployment paths, offline limits
- **Versions:** Kotlin 2.1, AGP 8.7.3, Compose; Room 2.6.1, WorkManager, DataStore, OkHttp 4.12.
- **Backend:** Ktor 3.0.3 on Netty, auth0 java-jwt, HikariCP and PostgreSQL. It binds to 0.0.0.0 and uses an in-memory store unless `DATABASE_URL` is set.
- **Deployment:**
  - `backend/Dockerfile`: runs as uid 10001.
  - `backend/docker-compose.yml`: postgres 16, `read_only`, `cap_drop: ALL`, DB port not published.
  - Prebuilt `LexiFlow/dist/*.apk`. Release signing falls back to the debug key.
- **Offline execution:**
  - Only the JVM backend and core run, inside `tools/sandbox.sh` (v2: pivot_root read-only root, no network apart from loopback, cgroup memory 2 GiB and pids 256, prlimit, scratch at `/sbx`).
  - Classpath: `/opt/lexiflow-audit-cp` (backend+core jars rebuilt from source identical to the reviewed commit).
  - Not available: test-host and MockEngine libraries, an Android runtime (app code is source-only), Gradle inside the sandbox, Postgres, WordPress and node_modules.

## 4. Entry surfaces and key paths
- **Register:** `POST /v1/auth/register` → `DisplayNameValidator` → store → `JwtService.issue`. Rate limit 10/min per `origin.remoteHost`.
- **AI proxy:** `POST /v1/ai/{story,evaluate,chat,enrich}`.
  - Flow: `RequestBody` (128 KiB) → `AiInputLimits` → `Prompts` (JSON inside `<request>`) → `AnthropicProvider` (retries; response read unbounded) → `AiService` validation → client re-validation → Room → Compose `Text`.
  - Rate limit 20/min per JWT subject.
- **Battle:** `/v1/battle` WebSocket. The token comes from `?token=` or the header; there is no Origin check.
  - `BattleManager`: join_queue, submit, rejoin, report and ping messages.
  - Results echo every player's text and id. Reports store a snapshot of the reported text.
- **Leaderboard:** `GET /v1/leaderboard?mode=` (enum) → parameterised SQL.
- **App imports:**
  - `.apkg`: `ApkgReader` keeps only the zip entry basename and extracts only the collection file, with no size limit, then opens it as a SQLite DB.
  - CSV/TSV: 20 MB cap after `readBytes`.
  - Transcripts: `readBytes` with no limit.
- **App IPC:**
  - `lexiflow://` deep links: `lockquestion/{id|new}`, `review` and `speaking`, acted on after onboarding.
  - `QuestionActionReceiver`: not exported, explicit PendingIntents (reply is mutable).
  - Widget broadcasts.
  - Lock-screen visibility setting, but the channel is set to PUBLIC.
- **App secrets:**
  - The JWT is stored in plain text in DataStore and placed in the WSS URL.
  - The backend URL is user-entered. Cleartext is allowed only for local hosts.
  - `data_extraction_rules` device transfer includes the file and database domains.
- **Build and release:** the Gradle wrapper has no checksum; `build-theme.php` zips the whole tree.

## 5. Trust boundaries and strongest controls
| Boundary | Strongest source-visible control |
|---|---|
| Anonymous → identity | Display-name validator plus IP rate limit. No proof of personhood. |
| Token → API | JWT issuer/audience/name checks plus a user-exists check. No revocation. |
| Player → room | `BattleManager` membership, deadline and single-submit checks. Scoring is server-side. |
| Player → operator spend | Per-subject rate limit and input caps. Registration is free. |
| Learner/file text → model | `<request>` data framing. Model output is validated by shape only. |
| Other app → app | Deep links are routed only after onboarding. The receiver and widget are not exported. |
| Device user/transfer → token | Backup is disabled; device-to-device transfer rules include the token. |
| Imported file → device resources | CSV size cap and entry-name filter. No decompressed-size cap. |
| WordPress admin → visitors | Admin trust (unescaped options). |

## 6. Starting paths
- `LexiFlow/backend/src/main/kotlin/com/lexiflow/server/{Application.kt,Config.kt,auth/,routes/,battle/,ai/,store/}`
- `LexiFlow/core/src/main/kotlin/com/lexiflow/core/{ai,competition,importer,language}/`
- `LexiFlow/app/src/main/{AndroidManifest.xml,res/xml/}`
- `LexiFlow/app/src/main/java/com/lexiflow/{MainActivity.kt,ui/LexiFlowRoot.kt,ui/screens/,notifications/,widget/,data/{remote,settings,importer,repo}/}`
- `LexiFlow/{gradle,app/build.gradle.kts,settings.gradle.kts}`
- `LexiFlow/backend/{Dockerfile,docker-compose.yml}`
- Repository root: `{functions.php,header.php,inc,template-parts,build-theme.php,src}`

## 7. Prior coverage
None. This is the first run for this repository, and no compatible prior ledger or findings exist.

## 8. Companion selection
| Companion | Selected for |
|---|---|
| WEB-PROTOCOL-AND-AUTH | JWT issuance and verification, token in the WS query string (API-key exposure). |
| PROTOCOLS-RPC-AND-MESSAGING | Custom WebSocket battle protocol: per-message authorization, rejoin, reports, replay. |
| RESOURCE-EXHAUSTION-AND-AVAILABILITY | Operator AI spend through free identities, unbounded provider/backend body reads, `.apkg` decompression, transcript reads. |
| AI-AND-LLM | Prompt assembly from learner/imported text, and model output stored and rendered. |
| DESKTOP-MOBILE-AND-LOCAL-IPC | Exported deep links, notification actions and lock screen, token storage and device-transfer rules. |
| DATA-ISOLATION-AND-LIFECYCLE | Leaderboard and result id exposure, report snapshots. |
| CLOUD-AND-DEPLOYMENT | docker-compose and env precedence (`JWT_SECRET` fallback, `ANTHROPIC_BASE_URL`). |
| SUPPLY-CHAIN-AND-RELEASE | Wrapper checksum, release signing fallback, `build-theme` context inclusion. |

Excluded:
- **CLIENT-SIDE:** the browser surfaces are static, with no messaging, storage or auth. The WebSocket client is the native app, which sends no ambient credentials.
- **MEMORY-SAFETY-AND-BINARY:** there is no native code in the repository. Android's SQLite parser is platform code outside the repository.
