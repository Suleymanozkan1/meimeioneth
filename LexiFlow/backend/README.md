# LexiFlow Backend

Ktor 3 (Kotlin/JVM) sunucusu: anonim hesaplar, AI proxy (Claude), gerçek zamanlı kelime düellosu (WebSocket) ve liderlik tablosu.
Ktor 3 (Kotlin/JVM) server: anonymous accounts, AI proxy (Claude), real-time word battle (WebSocket) and leaderboard.

Uygulama çevrimdışı çalışacak şekilde tasarlandı; sunucu yalnızca isteğe bağlı çevrimiçi özellikler içindir.
The app is offline-first; the server only powers optional online features. Wire types come from `:core`
(`com.lexiflow.core.ai.*`, `com.lexiflow.core.competition.*`), serialised with `LexiJson` (`"type"` discriminator).

---

## Çalıştırma / Running

```bash
# Repo kökünden / from the repository root
./gradlew :backend:run                      # in-memory store, AI disabled, random JWT secret
./gradlew :backend:test                     # unit + integration tests
./gradlew :backend:installDist              # backend/build/install/lexiflow-server/bin/lexiflow-server
./gradlew :backend:fatJar                   # backend/build/libs/lexiflow-server-all.jar

# Docker (PostgreSQL 16 + server)
cd backend && cp .env.example .env          # değerleri doldurun / fill in the values
docker compose up --build
curl localhost:8080/health
```

Gerçek bir PostgreSQL ile depo testi (isteğe bağlı) / optional Postgres store test:
`LEXIFLOW_TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/lexiflow_test LEXIFLOW_TEST_DB_USER=... ./gradlew :backend:test`

## Ortam değişkenleri / Environment

| Değişken / Variable | Varsayılan / Default | Açıklama / Description |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `JWT_SECRET` | random (WARNING logged) | HS256 secret, **≥ 32 chars**. Yoksa her açılışta yeni üretilir; tokenlar yeniden başlatmada geçersiz olur. |
| `DATABASE_URL` | – | `jdbc:postgresql://host:5432/db` (or `postgres://…`). Yoksa bellek içi depo / absent → in-memory store |
| `DB_USER`, `DB_PASSWORD` | – | DB credentials |
| `AI_PROVIDER` | `none` | `anthropic` or `none` |
| `ANTHROPIC_API_KEY` | – | Required when `AI_PROVIDER=anthropic` (otherwise AI stays disabled) |
| `ANTHROPIC_MODEL` | `claude-opus-5-5` | Model id |
| `ANTHROPIC_FALLBACKS` | `default` | `off` disables server-side refusal fallback (`fallbacks: "default"`) |
| `ANTHROPIC_BASE_URL` | `https://api.anthropic.com` | Optional (proxy/gateway) |
| `RATE_LIMIT_AI_PER_MINUTE` | `20` | Per-user limit for `/v1/ai/*` |
| `LOG_LEVEL` | `INFO` | Logback root level |

## HTTP uç noktaları / Endpoints

Tüm hatalar / all errors: `{"code":"...","message":"..."}`.

| Method & path | Auth | Body → Response |
|---|---|---|
| `GET /health` | public | `{"status":"ok","ai":false,"db":"memory"\|"postgres"}` |
| `POST /v1/auth/register` | public (10/min/IP) | `{"displayName":"Ayşe"}` → **201** `{"token","playerId","displayName"}` · 400 `invalid_display_name` |
| `POST /v1/ai/story` | Bearer | `StoryRequest` → `StoryResponse` |
| `POST /v1/ai/evaluate` | Bearer | `EvaluateRequest` → `AiFeedback` (≤ 3 corrections) |
| `POST /v1/ai/chat` | Bearer | `ChatRequest` → `ChatResponse` |
| `POST /v1/ai/enrich` | Bearer | `EnrichRequest` → `EnrichResponse` (senses with `examples`; ≥ 10 distinct examples in total, each `answer` an exact substring of `en`) |
| `GET /v1/leaderboard?mode=FIVE_WORD_SENTENCE` | Bearer | JSON array `[{"rank","playerId","displayName","wins","games","averageScore"}]`, top 50 by wins then average score; `[]` when no games. `mode` optional (all modes). |
| `GET /v1/battle` (WebSocket) | `?token=` or `Authorization: Bearer` | see protocol below |

- `displayName`: 2–24 karakter, harf/rakam/boşluk/`_`/`-`, küfür ve “admin” gibi isimler engellenir.
- JWT: HS256, issuer `lexiflow`, audience `lexiflow-app`, `sub` = playerId, claim `name`, 90 gün. Token'ın kullanıcısı depoda yoksa (ör. bellek içi depo yeniden başladıysa) **401** döner → uygulama yeniden kayıt olmalı.
- AI hata kodları / AI error codes: **503** `ai_unavailable` (AI kapalı), **502** `ai_invalid_output` (model çıktısı bir düzeltme denemesinden sonra da geçersiz), **502** `ai_upstream_error`, **422** `ai_refused`, **429** `rate_limited`, **400** `invalid_request` / `bad_request`, **413** `payload_too_large`.
- AI girdi sınırları / input limits: text/userMessage ≤ 4000 chars, history ≤ 20 turns, targets ≤ 12, word lists ≤ 200, CEFR ∈ A1–C2, body ≤ 128 KB. Limit aşımı 400 döner (sessizce kesilmez).

AI akışı / flow: sabit sistem promptu (yalnızca JSON + örnek şema) → `AiValidation.extractJsonObject` → `LexiJson` → `AiValidation.validate*` (enrich: toplam ≥ 10 farklı örnek, `answer` cümlede birebir geçmeli; lemma eşleşmeli) → geçersizse hatalar eklenerek **bir kez** tekrar → yine geçersizse 502.

## WebSocket protokolü / Battle protocol

`wss://<host>/v1/battle?token=<JWT>` — her çerçeve tek bir JSON mesajıdır / one JSON message per text frame.

İstemci → sunucu / client → server (`ClientMessage`):
```json
{"type":"join_queue","mode":"FIVE_WORD_SENTENCE"}
{"type":"leave_queue"}
{"type":"submit","roomId":"8d1c…","text":"I was reluctant to ignore the obvious opportunity…"}
{"type":"rejoin","roomId":"8d1c…"}
{"type":"report","roomId":"8d1c…","playerId":"<opponent id>","reason":"offensive language"}
{"type":"ping"}
```

Sunucu → istemci / server → client (`ServerMessage`):
```json
{"type":"welcome","playerId":"…","displayName":"Ayşe","onlinePlayers":12}
{"type":"queued","mode":"FIVE_WORD_SENTENCE","waiting":2}
{"type":"match_found","roomId":"8d1c…","mode":"FIVE_WORD_SENTENCE","words":["reluctant","ignore","obvious","opportunity","manage"],"roundSeconds":120,"endsAtEpochMs":1792345678901,"players":["Ayşe","Mehmet"]}
{"type":"submission_ack","roomId":"8d1c…"}
{"type":"player_left","roomId":"8d1c…","displayName":"Mehmet"}
{"type":"round_result","roomId":"8d1c…","words":[…],"results":[{"playerId":"…","displayName":"Ayşe","text":"…","scores":{"grammar":92,"wordUsage":100,"coherence":90,"naturalness":100,"diversity":88,"taskCompletion":100},"overall":95,"corrected":"…","keyPoints":[],"rationaleTr":["…"],"submitted":true}],"ranking":["<playerId>","<playerId>"]}
{"type":"error","code":"late","message":"…"}
{"type":"pong"}
```

Kurallar / rules:
- `onlinePlayers` gerçek bağlı soket sayısıdır. **Sahte oyuncu asla oluşturulmaz**; tek kişi kuyrukta bekler.
- Eşleştirme mod başına: 4 kişi olunca hemen; 2–3 kişi olunca 3 sn bekleyip başlar (3./4. oyuncuya fırsat).
- `match_found.players` = görünen adlar; `round_result.ranking` = playerId listesi (sonuçlar da bu sırada).
- `submit`: yalnızca oda üyeleri, oyuncu başına bir kez, ≤ `CompetitionRules.MAX_CHARS` (1500), `endsAt + 3 sn` sonrası `late`. Tur herkes gönderince veya süre dolunca biter; puanlama sunucuda `CompetitionRules.score` + `rank` ile deterministiktir; göndermeyenler `submitted=false` ve 0 puan alır. Sonuçlar (metin değil, puanlar) kalıcı olarak saklanır.
- Bağlantı koparsa diğerlerine `player_left` gider; tur bitmeden `rejoin` ile dönülür (kalan süreyle `match_found` tekrar gönderilir). Biten odaya `rejoin` → kayıtlı `round_result`.
- Hata kodları / error codes: `bad_message`, `rate_limited`, `in_room`, `unknown_room`, `not_member`, `late`, `round_over`, `already_submitted`, `too_long`, `empty`, `invalid_report`, `replaced`, `internal`.
- Hız sınırı: bağlantı başına 10 sn'de 20 mesaj; sürekli aşımda soket kapatılır (1008). `report` için ayrı onay mesajı yoktur (protokolde tip yok); hatalı raporlar `error` alır.

## Güvenlik notları / Security notes

- **Veri minimizasyonu:** e-posta/telefon/gerçek ad yok; yalnızca rastgele UUID + görünen ad. Düello metinleri saklanmaz; yalnızca şikâyet edilen oyuncunun metni moderasyon için şikâyet kaydına kopyalanır.
- **Loglar:** gizli anahtarlar, tokenlar, sorgu dizeleri (WebSocket token'ı) ve kullanıcı metinleri loglanmaz; yalnızca yöntem, yol, durum, görev adı, süre ve istek kimliği.
- **AI:** API anahtarı yalnızca sunucuda; istemci asla görmez. Kullanıcı girdisi prompt içinde `<request>` etiketinde *veri* olarak işaretlenir (prompt injection'a karşı). Model çıktısı şemaya göre doğrulanır; geçersiz içerik istemciye gönderilmez. Kullanıcı başına hız sınırı ve girdi boyutu sınırları maliyeti korur.
- **Auth:** tüm `/v1` uçları (kayıt hariç) JWT ister; `JWT_SECRET` ≥ 32 karakter olmalı ve üretimde mutlaka ayarlanmalı. Sunucuyu TLS arkasında (reverse proxy) çalıştırın; `?token=` sorgu parametresi yalnızca HTTPS/WSS üzerinden kullanılmalı.
- **Konteyner:** çok aşamalı imaj, root olmayan kullanıcı (uid 10001), compose'da salt-okunur dosya sistemi, `no-new-privileges`, `cap_drop: ALL`; veritabanı host'a açılmaz.
- `.env` dosyasını asla commit etmeyin; `.env.example` yalnızca yer tutucular içerir.

---

### English summary

Run `./gradlew :backend:run` for a local server (in-memory, AI off) or `docker compose up --build` in `backend/` for
Postgres 16 + server. Configure through the environment variables above. All `/v1` routes except registration need a
Bearer JWT; the battle WebSocket also accepts `?token=`. AI routes proxy Claude through the Messages API, ask for JSON
only, validate with `AiValidation`, retry once with the validation errors and answer 502 `ai_invalid_output` if the
output is still invalid (503 `ai_unavailable` when AI is disabled). Battles only ever match real connected players,
score deterministically with `CompetitionRules`, persist scores (not texts) and feed the leaderboard. No secrets or
user text are logged.
