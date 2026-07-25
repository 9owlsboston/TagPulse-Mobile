# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **M4 (Enrolment + relay)** of the OBD-II MVE, in `:gateway-core` (new `relay`
  package): the durable outbox now **drains** — `PENDING` `Observation`s are mapped
  to the **generated** `TagReadCreate` and relayed as a batched
  **`POST /tag-reads/batch`**, moving rows `PENDING → SENT` on a `201`. A
  `CredentialStore` interface (`baseUrl` / `deviceId` / ingest `apiKey`) with a
  **Keystore-backed** impl (`androidx.security` `EncryptedSharedPreferences`, secrets
  encrypted at rest, redacting `toString()`) holds the enrolment facts; ingest
  authenticates with the out-of-band **tenant user API key** (`Authorization: Bearer
  tp_…`, Fix 1) — never the unwired `tpd_` device token. A thin OkHttp
  `BackendClient` (`OkHttpBackendClient`) wraps the generated models (`postTagReadsBatch`
  parses `{ingested, rejected}`; `provisionDevice` posts `X-Provisioning-Key`; approval
  stays manual/admin) and maps non-2xx to typed outcomes — `5xx`/network → retryable,
  `400` → terminal, `401` → a credential error (the key is never logged or echoed). A
  pure `ObservationMapper` performs the §4 field mapping (`device_id`=gateway UUID,
  `tag_id`=subject id, ISO-8601 `timestamp`, `sensor_data`=payload, `location` with
  `accuracy_m`/`source=gps`). A `Drainer` purges stale rows **before** sending, batches
  at ≤ **500**, and on retryable failure bumps `attempts` with **full-jitter exponential
  backoff**, parking a batch `FAILED` at `maxAttempts` (surfaced, not dropped); on a `401`
  it leaves rows `PENDING`. The `DrainReport` surfaces the backend's clock-`rejected` count
  (plan §7 "keep rejected for inspection"). **Delivery is at-least-once (Fix 4):** no client
  idempotency key, so a lost `201` re-sends and duplicates on the backend — documented and
  accepted.
  New runtime deps in `:gateway-core`: `okhttp` and `androidx.security:security-crypto`
  (`okhttp-mockwebserver` test-only). Tests (no network, no device): OkHttp `MockWebServer`
  backend-client tests, pure mapping tests, Robolectric `Drainer` tests
  (SENT/retry+backoff/FAILED/at-least-once/purge/batch-cap), and secret-hygiene asserts.
  Gate green (`./gradlew lintDebug testDebugUnitTest assembleDebug`).

### Fixed
- **`C-1TQZ` — outbox size cap is now atomic.** With M4 adding a second writer (the
  drainer), `Outbox.enforceSizeCap()` folds count + eviction into a single-statement
  DAO `evictToCap(maxItems)` (`DELETE … WHERE id NOT IN (SELECT … LIMIT :maxItems)`),
  closing the read-then-delete race the prior `count()`-then-`deleteOldest()` opened.


  first real core implementation beyond the M0 interfaces/generated client): a
  durable **Room** outbox that persists every `Observation` and survives a process
  restart, but does **not** yet send. `OutboxItem` entity mirrors plan §7
  (`{id, subject_kind/id, source_modality/gateway_device_id, captured_at,
  payload_json, location_json, state, attempts, created_at}` — the explicit
  subject+source is the design's "cheap hedge", never an implicit self);
  `OutboxDao` (suspend + `Flow`) covers insert, pending-by-state (oldest-first),
  count / count-by-state, state+attempts update, delete-by-id, age-purge
  (`deleteCapturedBefore`) and oldest-eviction (`deleteOldest`); `OutboxDatabase`
  + `OutboxDatabaseFactory` open a **file-backed** DB (restart-safe). The core-facing
  `Outbox` API `enqueue()`s an `Observation` write-through as a `PENDING`,
  `attempts = 0` row and returns its id immediately (no send), and exposes
  `pending()`/`observePending()`, `count()`/`observeCount()`, `countInState()`, and
  `purgeExpired(now)`. `OutboxState {PENDING, SENT, FAILED}` spans the full
  lifecycle but **M3 only ever produces `PENDING`** — the SENT/FAILED transitions
  and the drainer are M4. Payload (`Map<String, Any?>`) and `GeoLocation` serialize
  to JSON via Jackson (`OutboxJson`) and round-trip on read (nested `pids` map +
  fractional PID survive; decimals stay dot-formatted regardless of locale).
  **Footprint caps (plan §7):** a configurable size cap evicts the oldest rows on
  enqueue (bounded, logged data-loss protection against an unbounded queue) and a
  configurable age cap (`purgeExpired`, default 24 h to match the backend clock)
  drops stale items; both defaults are `unverified` pending Phase-0 field data. The
  Jackson serialization **runtime** (`jackson-databind` + `jackson-module-kotlin`)
  and Room (`room-runtime`/`room-ktx`, compiler via **KSP**) are added to
  `:gateway-core`; Room outbox tests run on the JVM under **Robolectric** (added as
  `testImplementation`) — including the A4 restart test (enqueue → close DB →
  reopen same file → item still `PENDING`), the faithful JVM analogue of the
  device-only instrumented test. Gate green
  (`./gradlew lintDebug testDebugUnitTest assembleDebug`).
- **M2 (Full snapshot + normalize)** of the OBD-II MVE: `PidCodec` now decodes all
  four MVE PIDs as pure functions — RPM (`010C`), speed (`010D`, `A` km/h), coolant
  temp (`0105`, `A−40` °C, may be negative) and fuel level (`012F`, `A*100/255` %,
  one-decimal float) — via a shared frame parser; each returns a typed
  `PidReading<T>` value or a clean `Failure` (never throws) on
  `NO DATA`/`?`/`UNABLE TO CONNECT`/malformed/wrong-header, spaces-on and spaces-off
  framing alike. `Elm327Session.readSnapshot()` requests the four PIDs in sequence,
  reassembles each to the `>` prompt, and assembles an `ObdSnapshot` — **per-PID
  failure is graceful** (that field goes null; the other PIDs still land; the
  session settles back on `Ready`). New `ObdSnapshot` model renders the plan §4
  `sensor_data` shape (`modality`/`protocol`/`captured_at`/`pids`/optional
  `dongle`/debug-gated `raw`) and round-trips across the string-typed seam.
  `ObdiiDriver.read()` now produces a `DriverReading` carrying the snapshot and
  `normalize()` maps it to an `Observation` — `subject`/`source` from an injected
  `ObdiiConfig`, `timestamp` = capture time, `location` stays null (GPS is M4/M5).
  Gate green (`./gradlew lintDebug testDebugUnitTest assembleDebug`).
- **M1 (BLE connect + one PID)** of the OBD-II MVE: a testable `BleTransport` seam
  (interface + real `AndroidBleTransport` over `android.bluetooth` [HIL-only] +
  scriptable in-memory `FakeBleTransport` for tests); an `Elm327Session` that runs
  the ELM327 init handshake (`ATZ→ATE0→ATL0→ATS0→ATSP0`), requests **RPM (`010C`)
  only**, reassembles notification fragments to the `>` prompt, and exposes an
  observable `StateFlow<ConnectionState>`; a pure `PidCodec.decodeRpm` (`010C` →
  `((A*256)+B)/4`, the seed of the future codec); `NO DATA`/`?`/`UNABLE TO
  CONNECT`/timeout treated as clean failures with bounded retry + one reconnect;
  `ObdiiDriver.discover()/read()` wired to exercise the RPM path (`normalize()`
  stays M2). BLE permissions declared (`BLUETOOTH_SCAN`+`neverForLocation`,
  `BLUETOOTH_CONNECT`; legacy perms `maxSdkVersion`-guarded). GATT UUIDs are
  configurable/discovered, never hard-coded. Gate green
  (`./gradlew lintDebug testDebugUnitTest assembleDebug`).
- **M0 (Scaffold)** of the OBD-II MVE: Android Gradle project (Kotlin DSL, wrapper
  pinned to Gradle 8.11.1, version catalog) with `:app`, `:gateway-core`, `:obdii`
  modules; the `GatewayDriver` seam (`discover → read → normalize`) + normalized
  `Observation` model; and the backend API client **generated** from the vendored
  TagPulse `openapi.json` (backend SHA `06dde2b`, spec `0.1.0`). Interfaces/models
  only — no BLE/PID/networking behavior. Gate green
  (`./gradlew lintDebug testDebugUnitTest assembleDebug`). (#4)
- Repo bootstrap (profile `xs`) and initial `docs/design/mobile-client.md` — native
  iOS+Android edge client design; decisions: separate repo, hybrid mode-switch, native
  Swift+Kotlin, HTTP-first.
### Changed
### Fixed
### Removed
### Security

<!--
HOW TO USE THIS FILE
====================
Every PR (except `noncodefix/*`, `spike/*`, `release/*` branches) must add at
least one row under `## [Unreleased]`. The `.githooks/pre-push` hook checks
this locally; CI re-checks it on the server.

Pick the right section:
  Added      — new features
  Changed    — changes in existing functionality
  Deprecated — soon-to-be-removed features
  Removed    — now-removed features
  Fixed      — bug fixes
  Security   — vulnerabilities

Format:  `- <one-line summary> (#<pr-number>)`
Example: `- JWT refresh tokens with 7-day TTL (#142)`

Merge conflicts on this file are handled by `.gitattributes`
(`CHANGELOG.md merge=union`) — parallel PRs' entries concatenate automatically.
-->
