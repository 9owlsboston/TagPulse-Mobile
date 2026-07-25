# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- **`C-ZVMF` — R8 footprint tree-shaking is now load-bearing.** The `:app` release
  build previously had `isMinifyEnabled = false`, so R8 never ran and the full
  ~145-schema generated OpenAPI **model superset** shipped even though the MVE uses
  only `TagReadCreate`/`Identity`/`Location`. Release now enables `isMinifyEnabled`
  + `isShrinkResources`, with the reflective-Jackson **keep-rules shipped as
  `:gateway-core` consumer rules** (`consumer-rules.pro`): `-keepattributes` for
  annotations/signatures, `-keepclassmembers` (deliberately not `-keep`, so unused
  model *classes* are still stripped) for the generated `api.model.**` + `GeoLocation`,
  a `kotlin.Metadata` keep for jackson-module-kotlin, a `TypeReference`-subclass keep,
  and `-dontwarn`s for JDK/Bean + Tink/errorprone classes absent on Android.
  **Build-verified:** `:app:assembleRelease` R8 `usage.txt` shows **145/148 generated
  model files removed** and the used models + `GeoLocation` (with all members) retained;
  release APK ~2.3 MB. Runtime Jackson-post-R8 correctness is covered by a JVM
  `JacksonR8ContractTest` (unminified contract) and a ready-to-run instrumented
  `JacksonR8SmokeTest` (`app` sets `testBuildType = "release"` so it targets the
  minified variant; `connectedReleaseAndroidTest` on an emulator/CI is the remaining
  gate — this repo has no emulator). Debug builds don't run R8 and are unaffected.

### Added
- **Handset↔tenant enrolment flow** (ledger `C-RYH7`, Increment 1) — the app can now be
  enrolled against a live tenant instead of relying on the `AppContainer` `baseUrl`
  placeholder. Implements plan §5 "Handset enrolment":
  - **`EnrolmentCoordinator`** (`app`, mirrors `ScanCoordinator`) exposes an `EnrolState`
    `StateFlow` (`Idle → Provisioning → Enrolled | Error`). One `enrol(input)`: validate
    (non-blank fields + a well-formed **`https://`** `baseUrl`) → `POST /devices/provision`
    against the **candidate** host via an ephemeral client → on success **atomically**
    persist `{device_id, tp_ key, baseUrl}` in the Keystore. Nothing is written on failure
    (no half-enrolled state); `Mutex`-serialized; secret-free error messages.
  - **`EnrolScreen`** (Compose) — backend URL + provisioning key + masked `tp_` ingest key
    + device name + **Provision** button; `MainActivity.AppRoot` **reactively** gates the
    "Scan vehicle" screen behind enrolment.
  - **Persisted, mutable `baseUrl`** — `KeystoreCredentialStore` now stores `baseUrl`
    alongside `device_id`/`apiKey` (`store(deviceId, apiKey, baseUrl)`); the constructor arg
    is a fallback default. New `InMemoryCredentialStore` carries a base URL only, for the
    ephemeral provisioning client.
  - **`ProvisioningScanner`** seam (interface + `ProvisioningPayload{baseUrl, provisioningKey}`)
    for the enrolment QR; the ML Kit/CameraX camera impl is **Increment 1b** (manual entry
    is the shipped path). Per **OQ2**, the QR carries only `baseUrl` + provisioning key; the
    `tp_` key is pasted, keeping the broad tenant secret off the printed artifact.
  - +11 `EnrolmentCoordinator` unit tests (fakes). **HIL** (not run here): real Keystore, a
    live `provision→approve`, and end-to-end enrol→scan→Map. **Increment 2** (vehicle
    VIN-bind — OBD-II Mode 09 + VIN barcode + plate label) is staged and backend-gated.

  proving the slice end-to-end. In `:app`:
  - **Jetpack Compose "Scan vehicle" screen** (`ui/ScanScreen.kt`) — a single-screen
    flow: a **Scan vehicle** button + a status/result area rendering the pipeline
    state (idle → connecting → handshaking → reading → relaying → done/error), the
    decoded PID snapshot, whether a GPS fix was attached, and the relay outcome.
    Compose is added via the BOM + `activity-compose` + `material3` (Kotlin 2.0
    Compose-compiler Gradle plugin).
  - **`ScanCoordinator`** (`scan/ScanCoordinator.kt`) composes the end-to-end flow and
    exposes a sealed `ScanState` `StateFlow`: on scan → `GatewayDriver.discover()` →
    `read()` → `normalize()` → **attach the GPS fix to `Observation.location`** →
    `Outbox.enqueue()` → `Drainer.drain()` (via a `Relay` seam) → reflect the
    `DrainReport` in the UI. Modality-agnostic (depends only on the core seam + app
    abstractions), so it is fully unit-testable with fakes. It **surfaces
    `DrainReport.credentialError`** as a `CREDENTIAL` error telling the operator to
    re-enrol / check the key — **closing ledger `C-5EHY`** (the read stays `PENDING`
    for a re-drain once the credential is fixed).
  - **`LocationProvider`** (`location/`) — an interface returning a single `GeoLocation`
    fix, with an Android **`LocationManager`** impl (HIL; no Google Play Services, per
    the footprint budget) + an in-memory `FixedLocationProvider` for tests. The
    coordinator maps the fix → `Observation.location`, which the M4 mapper renders to
    `Location(accuracyM, source="gps")`.
  - **Composition root** (`di/AppContainer.kt`) — manual DI (no Hilt): constructs the
    real `ObdiiDriver.forAndroid` + `KeystoreCredentialStore` + `OkHttpBackendClient` +
    Room `Outbox` + `Drainer` + `AndroidLocationProvider` and hands them to the
    coordinator.
  - **Runtime permissions** — `MainActivity` requests `ACCESS_FINE_LOCATION` +
    (API 31+) `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` at the point of use; `:app` now
    declares `ACCESS_FINE_LOCATION` (GPS is actually used, unlike M4).
  - **A7 end-to-end fixture (HIL, runnable)** — `scripts/e2e/a7-map-check.py`
    (stdlib-only) seeds a vehicle asset + a **`binding_kind='device'`** binding
    (`binding_value` = the reported `tag_id`; plan §5 Fix 3), relays a
    `POST /tag-reads/batch` exactly as the app does (`sensor_data` + `location`), and
    asserts `GET /assets/current-locations` returns the vehicle at the read's location.
    Endpoints/fields validated against `~/ws/TagPulse` @ `06dde2b`; **not run here**
    (no Docker in this distro) — see `scripts/e2e/README.md` for run steps.
  - Tests (gate; no device, no Docker): `ScanCoordinatorTest` (Robolectric, real
    Room-backed `Outbox` + fakes for driver/GPS/relay) covers the happy path (scan →
    snapshot → GPS attached to `Observation.location` → enqueue → drain → `SENT`, UI
    ends **Done** with the PID values), the credential-error surface (`C-5EHY`),
    driver/no-dongle failures, a relay-failed surface, the null-fix (still relays)
    path, and the live driver-link state mirror.
  - **MVE acceptance after M5:** A1–A5 code-complete (real creds/backend are HIL),
    A6/A7 delivered as HIL + the runnable A7 E2E script, **A8 gate green**.
  - **Round-2 hardenings** (post-review, same PR): the `ScanCoordinator.scan()`
    `enqueue → drain` tail is now wrapped so an unexpected exception (e.g. a
    catastrophic Room write) rethrows `CancellationException` first, else lands as a
    terminal `ScanState.Error(INTERNAL, …)` with the mutex still unlocked — no stranded
    non-terminal state (`ErrorKind.INTERNAL` added; +2 coordinator tests). The
    `report.failed > 0` relay message was corrected — `FAILED` rows are terminal (not
    retried), so it now reads "Relay failed: N read(s) could not be delivered after
    retries (check connectivity / the backend)."
  Gate green (`./gradlew lintDebug testDebugUnitTest assembleDebug`).

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
- **`C-4T93` — HTTP `429`/`408` from the ingest backend are now retryable, not
  terminal.** `OkHttpBackendClient` previously mapped every non-`401`/non-`5xx`
  status (including `429 Too Many Requests` and `408 Request Timeout`) to
  `BatchResult.Terminal`, so a rate-limited or timed-out batch was parked `FAILED`
  instead of retried. Both now map to `BatchResult.Retryable`. A `429` **honors the
  server's `Retry-After`** (delta-seconds form) via a new optional
  `Retryable.retryAfterMillis`: the `Drainer` waits exactly that long (≤ `maxBackoff`)
  in place of its computed backoff, and when the directive **exceeds `maxBackoff`** it
  **defers** — the batch stays `PENDING` (no attempt counted, no rows failed) and the
  drain stops for a later pass (surfaced via `DrainReport.retryAfterMillis`), avoiding
  premature retries that would otherwise exhaust `maxAttempts` and fail deliverable
  rows.
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
