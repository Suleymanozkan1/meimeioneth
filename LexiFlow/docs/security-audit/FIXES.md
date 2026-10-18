# Security audit fixes

The audit (`REPORT.md`) reviewed commit `0cf6d494`. These are the changes made in response, with their regression tests.
The audit records in `findings.json` keep their original verdicts; they describe that commit, not this one.

## Confirmed findings

| Finding | Fix | Test |
|---|---|---|
| **Medium:** the `.env.example` JWT placeholder was accepted as the signing key | `AppConfig.fromEnv` refuses placeholder-looking values (`replace-with…`, `changeme`, `your-…`, `example`, …) and secrets with fewer than 10 distinct characters; the server will not start with them. `.env.example` now ships an empty `JWT_SECRET` (random per-process secret) with instructions. | `SecurityFixesTest.placeholder and low-entropy jwt secrets are refused` |
| **Medium:** operator AI spend grew with every free account | New `AiBudget`: a per-client-address limit (`RATE_LIMIT_AI_PER_SOURCE_PER_MINUTE`, default 60), summed over all accounts from that address, and a server-wide limit on model calls, retries included (`RATE_LIMIT_AI_GLOBAL_PER_MINUTE`, default 300). The per-player limit stays. The registration limit is now configurable (`RATE_LIMIT_REGISTER_PER_MINUTE`). | `ai budget per source is shared by every account from that address`, `global ai budget counts every model call including retries` |
| **Low:** the battle JWT in `?token=` reached logs at TRACE | The app sends the token in the `Authorization` header. The server ignores `?token=` unless `ALLOW_WS_QUERY_TOKEN=true` (only needed for app 1.0/1.1). Ktor loggers that print request URIs are pinned at INFO in `logback.xml`. | `battle socket ignores the query token unless legacy mode is enabled`, `legacy query token still works when explicitly allowed` |
| **Low:** leaderboard wins could be farmed with own accounts | A win counts only if at least one opponent actually attempted the task (task completion ≥ 60). Wins per day are capped at 3 against the same opponent group and 30 in total. | `wins against a trivial or repeated opponent are not credited` |
| **Low:** staff-name block bypassed with Turkish letters | Reserved names are compared after removing accents and folding Turkish letters, per word and for the whole name. "Sistem" and "Yönetici" were added. "I got it", "Ecosystem Fan" and "Admiral Ayşe" stay valid. | `staff names written with turkish letters or accents are rejected` |

## Needs-validation leads closed in code

These leads could not be confirmed without a device or a deployment. The risky behaviour has been removed anyway.

| Lead | Change |
|---|---|
| Deep-link extras could drive `NavController` past the router and onboarding | `MainActivity` keeps only the action and data of every launching intent. Caller-supplied `android-support-nav:controller:*` extras are dropped before the navigation graph is created. |
| Lock-screen "hide content" depended on a system setting | When the setting is on and a secure lock screen is showing, only the generic text is posted. Answer, rate and dismiss actions also require unlock on Android 12+. |
| Learner's own sentence shown publicly on the lock screen | Results that quote the learner's text are always private. On a secure lock screen they show only "Cevabın kaydedildi". The channel no longer forces PUBLIC visibility (new installs). |
| `.apkg` expansion without limit; leftovers after failure | The extracted collection is capped at 200 MB. The work directory is always cleaned, including after errors. |
| One huge CSV cell made a deck unreadable | Per-field limits: 2,000 characters for meaning, definition, example and notes; 200 for pos, IPA and deck; at most 30 tags. Oversized rows are reported as row errors and not stored. CSV and transcript files are read with a byte bound instead of `readBytes()`. |
| `build-theme.php` zipped the whole repository, including LexiFlow secrets | The packager now uses an allowlist of theme files and never packages `.env*`, keystores, `*.properties` secrets, logs, maps or zips. Symlinks are skipped. Tested on a scratch copy containing dummy `.env` and keystore files. |
| Leaderboard aggregation could hold the shared DB pool | `GET /v1/leaderboard` is rate limited per player (30/min). Every Postgres connection runs with `statement_timeout = 5s`, so no query can hold a pooled connection that authentication needs. |
| Theme option text echoed without escaping | `esc_html`, `esc_attr` and `esc_url` are applied to all theme-option output and to the site name in the header. |

## Still open (need an owner decision or a deployment)

- **Release signing:** APKs are signed with the build machine's debug key. Create a release keystore you control, add `keystore.properties`, and rebuild. This cannot be done safely from an ephemeral build container.
- **Register / AI limits behind a reverse proxy:** without trusted forwarded headers, every client behind one proxy shares the per-address limits. If you deploy behind a proxy, add Ktor `XForwardedHeaders` restricted to the proxy's address.
- **Leaderboard at scale:** with very large `battle_results` tables, consider an incrementally maintained per-player aggregate instead of re-aggregating on every read.
