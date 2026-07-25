# Execution log — TagPulse-Mobile

Chronological record of **what was executed** against this repo — commands run,
changes made, and how they were verified. Distinct from `CHANGELOG.md` (which
records *content* changes for consumers); this log records **action** — especially
important because AI agents execute on our behalf.

Append newest-last. Preserve dates, commands, and verification notes; use
completed-state language (record what happened, not what to do).

---

<!-- Template (copy per entry):

### YYYY-MM-DD — <short title>

<what was done + why, in a sentence or two>. Verified: <how — command output,
test, diff, byte-identity, etc.>.
-->

### 2026-07-23 — Repo bootstrap (profile xs) + mobile-client design draft

Stood up the repo: `git init` + `dev-env-setup/scripts/bootstrap-repo.sh --profile xs`
(seeded README, AGENTS.md, CHANGELOG.md, `.github/copilot-instructions.md`, git config,
`docs/current-state.md`, this log). Filled the README / AGENTS / current-state
placeholders and authored `docs/design/mobile-client.md` capturing the four locked
decisions (separate repo · hybrid mode-switch · native Swift+Kotlin · HTTP-first) and the
endpoint map. Endpoints in the design were confirmed against the TagPulse source
(`POST /tag-reads[/batch]`, `POST /assets/{id}/external-position`,
`POST /telemetry/readings/ingest`, `POST /devices/provision`,
`POST /device-registry/{id}/approve|rotate-token`). Verified: dry-run then applied
bootstrap (report showed 8 files added + agent contract seeded); `git status` clean-tree
review before commit.

### 2026-07-23 — Open-questions exploration + edge-gateway proposal

Captured a design conversation exploring Phase-1 open questions Q-A (device principal for
telemetry ingest) and Q-B (Tracker-mode asset binding), then a broader reframe of the
phone from an edge *device* to a mobile edge **gateway** (BLE/sensor aggregation + an
on-device ingest SDK for other apps) and a **generalized gateway core + per-modality
driver** pattern with industry prior art. New doc:
`docs/design/edge-gateway-exploration.md` (southbound/northbound split, mapping onto
existing `tag_reads`/`telemetry_readings`/`external-position`, references, new questions
G-1…G-6). Enriched Q-A (Option A lean + MQTT-impact prerequisite) and Q-B (B1→B3 lean)
in `mobile-client.md`. Logged the MQTT-impact prerequisite to the agent ledger as
`I-F0PR` (`repo:TagPulse-Mobile`). Verified: docs-only change; links between
`mobile-client.md` and the new exploration doc resolve; no code touched. Docs carve-out
(no deps/CI/IaC/security/behavioral config) — rubber-duck not required.

### 2026-07-24 — Edge-gateway design discussion: open questions resolved

Walked every edge-gateway open question (G-1…G-6, reshaped Q-A/Q-B) to a decision in a
design discussion, **grounded in the actual TagPulse backend code** (`~/ws/TagPulse`,
project `tagpulse`) rather than assumption. Key code verifications: `/tag-reads/batch`
(`list[TagReadCreate]`, per-row `device_id`) and `/telemetry/readings/ingest`
(`TelemetryReadingsBatch.readings`, per-row `subject_kind`+`subject_id`, `max_length=500`)
are **per-row subject-scoped** → mixed-subject batch confirmed (G-2); telemetry ingest is
`require_role("admin","editor")`-gated while tag-reads uses tenant auth → the Q-A blocker,
confirmed; `TelemetryReadingIngest.device_id` docstring already models "the gateway that
uplinked an external observation"; `external-position` (`ExternalLocationCreate`) is
asset-keyed with a free-form `source` but **no `priority`** field (`accuracy_meters` present)
and `require_role("admin","editor")`; RFID reader positions are derived read-side
(`asset_location.py` `'rfid'::text AS source`) then unioned with `external_locations`, so
generalizing external-position does **not** affect the reader client. Resolutions recorded
in a new `## Design-discussion outcomes (2026-07-24)` section of
`edge-gateway-exploration.md`; pointers added atop the two open-question sections; status
line updated. Reconciled `docs/current-state.md` forward-looking note + bumped snapshot to
2026-07-24. Ledger: logged 2 backend asks (`I-75YC` scoped gateway principal for telemetry
ingest — refines `I-F0PR`; `I-9HQA` generalize external-position to all subject kinds) and
3 decisions (`D-H855` gateway = reader + cellular-backhaul, Cap. B deferred; `D-AZ5E`
tiered downstream trust by provisioning channel; `D-CB5W` dual deployment-mode flag).
Verified: docs-only change; internal anchors (`#design-discussion-outcomes-2026-07-24`) and
cross-doc links resolve; no code touched. Docs carve-out (no deps/CI/IaC/security/behavioral
config) — rubber-duck not required.

### 2026-07-24 — MVE prospect: OBDII-on-demand (candidate first gateway modality)

Captured a candidate MVE for the gateway direction after an ideation thread (OBDII scanner
→ downstream sensor devices). Added a `## MVE prospect — OBDII-on-demand` section to
`edge-gateway-exploration.md`: the vertical slice (BLE-connect dongle → read PIDs → relay →
Map), why it's low-risk (green-zone via `tag_reads.sensor_data`/`location`, vehicle=asset so
sidesteps `I-9HQA`, opportunistic mode so no background/battery/iOS-suspension fight), the
hardware strategy (write to the public ELM327 command set; MVE on a ~$25-30 BLE dongle —
BLE mandatory for iOS; production upgrade to OBDLink MX+ as an ELM327 superset without
rewriting the driver), and platform-first = Android (resolving Q-C for this slice). Pricing
figures web-searched (OBDLink MX+ ~$100-140, Veepeak/Vgate BLE ~$25-50, generic ELM327
~$10-20 Android-only) and marked `unverified` in-doc as street prices. Reconciled
`current-state.md` forward-looking note. Ledger: `D-M8XF` (MVE prospect decision). Verified:
docs-only; anchors/links resolve. Docs carve-out (no deps/CI/IaC/security/behavioral
config) — rubber-duck not required.

### 2026-07-24 — Phase-0 MVE plan: OBDII-on-demand

Authored `docs/design/obdii-mve-plan.md` — a file-by-file / milestone build plan for the
OBDII-on-demand MVE (Android app → BLE ELM327 dongle → read 4 J1979 PIDs → outbox →
`POST /tag-reads` → Map), planning *within* the already-decided gateway direction, green-zone
scope, and Android-first call (no relitigation). Re-verified the backend contract in
`~/ws/TagPulse` (`main` @ `06dde2b`): `TagReadCreate`/`Location` field shapes
(`Location.accuracy_m`, `source ∈ {gps,fixed,inferred,reader_gnss}`), `tag-reads[/batch]` =
tenant auth (`get_current_tenant`), and the `provision → approve` flow. Surfaced one real
contract gap: `POST /devices/provision` returns `{device_id, status, message}` with **no
token**, while `tag-reads` auth is tenant-scoped — so how an approved handset authenticates
ingest is an open question (OQ-1, blocks milestone M4). Plan covers goal/acceptance,
scope in/out (mirrors I-75YC / I-9HQA / always-on / fan-in / DTC-VIN exclusions),
core/driver architecture, PID→`sensor_data` mapping, enrolment/binding, Android BLE
specifics (`unverified` where dongle-specific), offline outbox, 6 milestones (M0 scaffold …
M5 Map E2E) each with a verify signal + the `./gradlew lintDebug testDebugUnitTest
assembleDebug` gate, and risks/open questions. Verified: docs-only; `docs-drift` clean
(below). Docs/plan carve-out for *this* change; the plan itself gates Phase-0 implementation
(rubber-duck required there). Plan-stage rubber-duck: pending (recorded in the doc's
`## Review attestations`).

### 2026-07-24 — OBDII MVE plan: round-2 revision (plan-stage rubber-duck fixes)

Revised `docs/design/obdii-mve-plan.md` on branch `docs/obdii-mve-plan` (PR #3) to resolve
4 blocking plan-stage rubber-duck findings, each re-verified against `~/ws/TagPulse` and
cited file:line in the doc. **Fix 1 (ingest auth):** confirmed `tpd_` device tokens are
never verified — `get_current_user` (`user_auth.py:137-210`) only routes `tp_` user API
keys / JWT / `X-Tenant-ID`, and `tpd_` (mint at `user_auth.py:101`, `rotate-token`
`devices.py:116-144`, `token_hash` `database.py:147`) misroutes to JWT decode → 401.
Decided: Phase-0 authenticates ingest with an out-of-band tenant user API key
(`tp_{slug}_…`, Bearer); added the security caveat (tenant-scoped, no per-device
revocation) and a `## Backend dependencies (post-MVE)` note pointing at ledger `I-K6D1`
(distinct from `I-75YC`). **Fix 2 (batch):** switched every drain/acceptance ref to
`POST /tag-reads/batch` → `{ingested, rejected}` (`ingestion.py:38-52`); kept single
`POST /tag-reads` only for manual one-off testing. **Fix 3 (Map binding):** documented the
required `binding_kind='device'` binding (`tr.tag_id = b.binding_value`,
`migrations/versions/057_epc_binding_match_hex.py`; enum `schemas.py:890`) as a setup
prerequisite in scope-in + §5, and added acceptance A7 + an E2E fixture asserting
`GET /assets/current-locations` (`assets.py:130-145`) returns the vehicle. **Fix 4
(retry):** stated at-least-once explicitly (backend assigns fresh UUID per insert,
`ingestion/service.py:258`; no client event id), removed the exactly-once claim, moved
idempotency to a future backend ask. Updated milestones M4/M5, open questions (OQ-1/OQ-2
resolved, OQ-5 added), and `## Review attestations` (round 1 → 4 findings → this round-2
revision). Verified: docs-only; `docs-drift` clean. Docs/plan carve-out for this change;
the plan gates Phase-0 implementation (rubber-duck required there).

### 2026-07-24 — OBDII MVE M0 (Scaffold): Android project + core/driver seam + generated client

Implemented milestone **M0** of `docs/design/obdii-mve-plan.md` (§8) on branch
`feat/m0-scaffold` (PR #4). **Scaffolded** an Android Gradle project (Kotlin DSL) where the repo
previously had no app code: root `build.gradle.kts` + `settings.gradle.kts`, a Gradle
**wrapper pinned to 8.11.1**, a version catalog (`gradle/libs.versions.toml`), an `:app`
application module (compileSdk/targetSdk 35, minSdk 26, AGP 8.7.3, Kotlin 2.0.21, JDK 17),
and two library modules **`:gateway-core`** and **`:obdii`**. Defined the core/driver
**seam** (plan §3): `GatewayDriver { discover → read → normalize }` + the normalized
`Observation { subject, source, timestamp, payload, location }` model (with `Subject`,
`Source`, `Modality`, `GeoLocation`, `DiscoveredDevice`, `DriverReading`) in `:gateway-core`;
`:obdii` carries an `ObdiiDriver` stub implementing the seam with `TODO()` bodies (no
BLE/PID behavior — that lands M1–M2).

**Generated backend client (AGENTS §2 hard rule):** vendored the backend spec to
`gateway-core/contract/openapi.json` (from `~/ws/TagPulse` `openapi.json`, backend commit
**`06dde2b9b875f6f66ee817a2760439f7d3f400b0`**, spec `info.version 0.1.0`); recorded the
SHA in `gateway-core/contract/CONTRACT.md` **and** machine-readable
`gateway-core/contract/contract.properties`. Wired the `org.openapi.generator` Gradle plugin
(task `openApiGenerate`) to codegen Kotlin models from the vendored spec into
`gateway-core/build/generated/openapi/` (added to the module's source set; Kotlin/lint tasks
depend on it). Models only — no HTTP-client runtime (footprint budget). **Deviation:** the
generator's selective `models=<names>` filter silently emits zero files against this
**OpenAPI 3.1** spec (3.1 support is "in development" upstream), so all 145 component schemas
are generated (148 `.kt` files incl. inline-enum models) — a superset of the MVE ingest
models `TagReadCreate`/`Location`/`Identity` for `POST /tag-reads/batch`. Footprint mitigation
is **`unverified`/aspirational**: R8 would strip the unused models, but release
`isMinifyEnabled=false` today so it does NOT yet — tracked as ledger **`C-ZVMF`** (enable R8 +
keep-rules or trim the spec before any release footprint acceptance; out of M0 scope).
**Serialization:**
the kotlinx.serialization compiler plugin ICE'd on the generated `Map<String, Any>` fields
(`@Contextual` doesn't cover the `Any` element), so generated models use **jackson**
annotations (compiles against `jackson-annotations` alone — no databind/okhttp until M4);
`Observation.payload` is a plain `Map<String, Any?>` aligning with `TagReadCreate.sensor_data`.

**Toolchain setup (this session):** no Android SDK was present, so installed one — downloaded
Android commandline-tools (`commandlinetools-linux-11076708`), set `ANDROID_HOME=~/android-sdk`,
accepted licenses (`sdkmanager --licenses`), and installed `platform-tools`,
`platforms;android-35`, `build-tools;35.0.0`. Bootstrapped the wrapper with a downloaded
Gradle 8.11.1. `local.properties` (`sdk.dir`) is gitignored (never committed).

**Verified — gate GREEN locally:** `./gradlew clean lintDebug testDebugUnitTest assembleDebug`
→ `BUILD SUCCESSFUL`; unit tests pass (gateway-core 2, obdii 1, app 1 — all `failures=0
errors=0`), lint clean, `app-debug.apk` produced (~3.7 MB). `docs-drift` clean (below). No
behavior implemented (no BLE, ELM327, PID, networking, or outbox) — M0 is scaffold only.
Diff-stage rubber-duck: pending (post-implement gate + verifier next).

### 2026-07-24 — OBDII MVE M0: round-2 docs/comments cleanup (post-verify)

M0 passed the `verifier` ("M0 conforms") + code-review ("no blocking issues") on
`feat/m0-scaffold` (PR #4). Small **docs/comments-only** follow-up (no build-logic or
product-code behavior change; `isMinifyEnabled` untouched): (1) recorded the **diff-stage
rubber-duck attestation** for the M0 diff in `docs/design/obdii-mve-plan.md`
`## Review attestations` and mirrored it into the PR #4 body (replacing the "pending" note);
(2) corrected `gateway-core/contract/CONTRACT.md` — generated models use **jackson
annotations** (`jackson-annotations` at M0), with the jackson **runtime**
(`jackson-databind` + `jackson-module-kotlin`) added at **M4** — not `kotlinx-serialization-json`;
(3) fixed the schema count to **145 component schemas** (148 was the generated `.kt` file
count incl. inline enums); (4) marked the "R8 tree-shakes unused models" footprint claim as
**`unverified`/aspirational** (release `isMinifyEnabled=false`, so R8 does not strip yet),
tracked as ledger **`C-ZVMF`**, in `CONTRACT.md`, `gateway-core/build.gradle.kts`, and this
log. Verified: `docs-drift` clean.

### 2026-07-24 — OBDII MVE M1: BLE connect + one PID (RPM) behind a testable seam

Implemented milestone **M1** of `docs/design/obdii-mve-plan.md` (§8 M1 row) on branch
`feat/m1-ble-rpm` off `main` (M0 already merged). Built the OBD-II read path behind a
**testable `BleTransport` seam** (plan §3/§6):

- **`BleTransport`** interface (`obdii/.../ble/`): coroutine/`Flow` link — `connect()`,
  `write(bytes)`, `notifications: Flow<ByteArray>`, `connected: StateFlow<Boolean>`. Two
  impls: **`AndroidBleTransport`** (real `android.bluetooth`: `BluetoothLeScanner` filter →
  `connectGatt` → request MTU (best-effort, reassemble regardless) → `discoverServices` →
  resolve notify/write chars → write CCCD to enable notify; **HIL-only**, compiles + lints
  clean, not unit-tested) and **`FakeBleTransport`** (test source set: scriptable
  command→fragments map, records `writes`, can model a mid-session drop).
- **`Elm327Session`**: init handshake `ATZ→ATE0→ATL0→ATS0→ATSP0`, then `010C` (RPM) only;
  reassembles notification fragments to the `>` prompt (subscribe-before-write via
  `async(UNDISPATCHED)`); per-command `withTimeout` (default 4 s) + bounded retry; one
  reconnect on GATT drop; `NO DATA`/`?`/`UNABLE TO CONNECT`/timeout → clean
  `RpmReading.Failure`, never a crash. Observable `StateFlow<ConnectionState>`
  (Disconnected/Connecting/Handshaking/Ready/Reading/Error); logs the RPM on success.
- **`PidCodec.decodeRpm`** — pure/synchronous `010C → ((A*256)+B)/4` (seed of the future
  codec). Handles spaces-on/off framing, echo, whitespace, lowercase; error tokens →
  typed failures. Speed/coolant/fuel decode + `sensor_data` assembly deliberately deferred
  to M2.
- **`ObdiiDriver`**: `discover()` returns the one configured dongle (scan runs inside
  `AndroidBleTransport.connect()`); `read()` = connect → handshake → read RPM →
  `DriverReading(rpm)`; `normalize()` stays `TODO` (M2). Factories `create(transport)` /
  `forAndroid(context)`. No-arg ctor retained for the scaffold smoke test.
- **UUIDs not hard-coded**: `BleUuidConfig` (nullable fields → runtime discovery;
  `NORDIC_UART_LIKE` default marked `unverified`, dongle-specific; `DISCOVER_ALL`).
- **Permissions** (obdii manifest): `BLUETOOTH_SCAN`+`usesPermissionFlags="neverForLocation"`,
  `BLUETOOTH_CONNECT` (API 31+); legacy `BLUETOOTH`/`BLUETOOTH_ADMIN`/`ACCESS_FINE_LOCATION`
  (+`ACCESS_COARSE_LOCATION`, required by lint `CoarseFineLocation`) all `maxSdkVersion=30`;
  `uses-feature bluetooth_le required`. Runtime request flow is minimal/stubbed at M1.

Deps: added `kotlinx-coroutines-core` (impl) + `kotlinx-coroutines-test` (test) to `:obdii`
(catalog entry added). Tests use `runTest` virtual time — **no hardware**.

Verified — gate GREEN: `./gradlew clean lintDebug testDebugUnitTest assembleDebug` →
`BUILD SUCCESSFUL`. Unit tests all `failures=0 errors=0`: **obdii 17** (PidCodec 9 incl.
`41 0C 0D 48`→850, fragmented/whitespace/lowercase, spaces-off, `NO DATA`/`?`/`UNABLE`/
malformed; Elm327Session 6 incl. exact command order, fragment reassembly, RPM decode,
state transitions Disconnected→…→Ready→Reading, timeout + `NO DATA` clean-error; Obdii
driver 2), gateway-core 2, app 1 (**20 total**). Lint clean (one benign `UnusedAttribute`
warning on `neverForLocation`, expected — attribute only applies API 31+). `app-debug.apk`
produced. `docs-drift` clean.

**HIL note:** `AndroidBleTransport` needs a real ELM327-over-BLE dongle + granted runtime
permissions — the actual RPM-from-hardware check (plan M1 verification signal) is a manual
HIL step, not covered by CI. UUIDs/framing/MTU-grant remain `unverified` (plan §6) until
validated on the purchased adapter.

Diff-stage rubber-duck: pending (post-implement gate + verifier next).

### 2026-07-24 — OBDII MVE M1 round-2: hardware-path fixes (post-code-review)

`verifier` said "M1 conforms" (gate green) but code-review found 2 real hardware-path bugs
the unit gate missed (because `FakeBleTransport` only threw `BleDisconnectedException`).
Fixed on the same branch `feat/m1-ble-rpm` (PR #5), scope held to M1 (one PID, read+log):

- **Fix 1 (HIGH)** — generic `BleException` was uncaught. `AndroidBleTransport.write()` can
  throw a plain `BleException` ("characteristic write rejected" / "write characteristic not
  resolved"), not only `BleDisconnectedException`. Added `catch (BleException)` to both the
  `Elm327Session.connect()` handshake loop (→ `fail(LINK_ERROR)` + throw `Elm327Exception`;
  state no longer stuck at Handshaking) and `requestRpm()` (→ `RpmReading.Failure(LINK_ERROR)`,
  not retried; `readRpm()` never throws for a per-command failure — contract restored). New
  `ObdError.LINK_ERROR`.
- **Fix 2 (MED)** — `BluetoothGatt` leak on drop+reconnect. The `STATE_DISCONNECTED` branch
  now `gatt.close()`s and nulls the field (guarded to the callback's own gatt), and
  `connect()` closes any pre-existing gatt before assigning (guards re-entrant/reconnect).
  `disconnect()` stays idempotent.
- **Fix 3 (LOW)** — write type now derived from the resolved characteristic's properties
  (`PROPERTY_WRITE`→`WRITE_TYPE_DEFAULT`, else `WRITE_TYPE_NO_RESPONSE`); many ELM327/Nordic
  clones are write-no-response only. Marked `unverified` (HIL).
- **Fix 4** — `Elm327Session.reconnect()` now rethrows `CancellationException` before its
  broad catch (no swallowed structured-concurrency cancellation); the `connect()`
  transport-connect catch also rethrows cancellation.
- **Fix 5** — extended `FakeBleTransport` with a `throwOn: String?` hook (generic
  `BleException`) and added tests using the existing `dropAfter`/`connectCount`: +3
  `Elm327Session` tests — generic `BleException` on read → clean `LINK_ERROR` (no throw) +
  Error state; generic `BleException` in handshake → Error + `Elm327Exception`;
  drop→reconnect (`connectCount==2`)→re-handshake→recovered `Value(850)`.

Verified — gate GREEN: `./gradlew clean lintDebug testDebugUnitTest assembleDebug` →
`BUILD SUCCESSFUL`; unit tests all `failures=0 errors=0` (obdii **20** — PidCodec 9,
Elm327Session **9**, ObdiiDriver 2; gateway-core 2; app 1 = **23 total**); lint clean;
`app-debug.apk` built. `docs-drift` clean. Diff-stage rubber-duck attestation recorded
(ran → "M1 conforms"; 2 bugs fixed) in the plan `## Review attestations` + mirrored to PR #5.

### 2026-07-24 — OBDII MVE M2: full four-PID snapshot + normalize

Implemented milestone M2 of `docs/design/obdii-mve-plan.md` (§8 M2 row) on branch
`feat/m2-snapshot`: decode all four MVE PIDs, assemble the §4 `sensor_data` snapshot, and
map it to the core's `Observation` via a pure `normalize()`. Scope held to M2 — no
Room/outbox (M3), no HTTP/batcher (M4), no GPS (`location` stays null), no enrolment/UI.

- **PidCodec** — extended from RPM-only to all four PIDs behind a shared private
  `parseFrame(response, header, minDataBytes)` (strip echo/whitespace/prompt, reject
  `NO DATA`/`?`/`UNABLE TO CONNECT`, match the `41 <pid>` line, return its data bytes or a
  typed reason). New pure decoders return a parametric `PidReading<T>`: `decodeSpeed`
  (`010D`→`A` km/h, Int), `decodeCoolantTemp` (`0105`→`A−40` °C, Int, may be negative),
  `decodeFuelLevel` (`012F`→`A*100/255` %, Float rounded to one decimal). `decodeRpm` keeps
  its `RpmReading` return (M1 path/tests unchanged) but now delegates to the shared parser.
- **ObdSnapshot** (new model) — nullable per-PID fields + optional `DongleInfo` + `rawFrames`
  + `includeRaw` debug flag. `toPayload()` renders the plan §4 JSON shape (omits failed PIDs;
  emits `raw` only when `includeRaw`); `toAttributes()`/`fromAttributes()` are a symmetric
  round-trip that carries the snapshot across the string-typed `DriverReading` seam
  (gateway-core untouched).
- **Elm327Session.readSnapshot(includeRaw, dongle, capturedAt)** — requests `010C,010D,0105,
  012F` in order via a generic `readPid()` helper (mirrors `requestRpm`'s bounded retry +
  single reconnect, but returns `PidReading<T>` so a per-PID failure is a null field, never a
  throw). Ends `Ready` even on partial failure. `readRpm()` (M1) kept intact.
- **ObdiiDriver** — new injected `ObdiiConfig(subject, source, includeRawFrames, dongle)`;
  `read()` now connects → handshakes → `readSnapshot()` → `DriverReading(snapshot.toAttributes())`;
  `normalize()` (pure, no I/O) reconstructs the snapshot and emits `Observation(subject, source,
  timestamp=capturedAt, payload=toPayload(), location=null)`. Removed the M1 `TODO` and the now-
  unused `ObdReadException` (readSnapshot is graceful, so read no longer throws per-PID).

Verified — gate GREEN: `ANDROID_HOME=/home/velen/android-sdk ./gradlew lintDebug
testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL`; unit tests all `failures=0 errors=0`
(obdii **42** — PidCodec 21, Elm327Session 12, ObdiiDriver 4, ObdSnapshot 5; gateway-core 2;
app 1 = **45 total**); lint clean; `app-debug.apk` built. `docs-drift` clean. Diff-stage
rubber-duck: pending (verifier next).

### 2026-07-24 — OBDII MVE M4: enrolment + relay (drain outbox → POST /tag-reads/batch)

Implemented milestone M4 of `docs/design/obdii-mve-plan.md` (§8 M4 row, §5 enrolment, §7
drain/backoff/at-least-once/caps, §4 data mapping) on branch `feat/m4-relay`, all in
`:gateway-core` (new `relay` package). The durable outbox now **drains**: `PENDING` rows are
mapped to the **generated** `TagReadCreate` and relayed as a batched `POST /tag-reads/batch`
authenticated with the tenant user API key. Scope held to M4 — no app UI / "Scan vehicle"
button, no GPS capture (the mapping consumes `Observation.location` if present; capture is
M5), no live E2E/Map (A6/A7), no device-approval automation, no `tpd_` device-token path
(out of MVE per §5 🚩).

- **`CredentialStore` (interface) + `KeystoreCredentialStore`.** The interface exposes
  `baseUrl`, `deviceId` (gateway device UUID), and the ingest `apiKey` (`tp_…`), read fresh
  per request. `KeystoreCredentialStore` is the **Android Keystore-backed** impl via
  `androidx.security:security-crypto` `EncryptedSharedPreferences` (`AES256_SIV` keys /
  `AES256_GCM` values under a hardware `MasterKey`) — secrets encrypted at rest, `store()`
  /`clear()`, and a **redacting `toString()`** (`apiKey=***redacted***`). Fix 1 (DECIDED):
  ingest auth is the out-of-band **tenant user API key** as `Authorization: Bearer tp_…`,
  **not** the unwired `tpd_` device token (§5 🚩). **Keystore test boundary = HIL** (mirrors
  M1's real-BLE seam): the real `AndroidKeyStore` provider isn't faithfully implemented by
  Robolectric, so the impl is compile-only in the unit gate; the relay logic is fully driven
  by a `FakeCredentialStore` (in-memory, test source set).
- **`BackendClient` (interface) + `OkHttpBackendClient`.** Thin OkHttp transport over the
  **generated** models: `postTagReadsBatch(reads): BatchResult` → POST `{baseUrl}/tag-reads/
  batch`, `Authorization: Bearer <apiKey>`, jackson-serialized body, parses `201
  {ingested,rejected}`; `provisionDevice(key,name)` → POST `/devices/provision` with
  `X-Provisioning-Key` (approval stays manual/admin — **not** automated). Non-2xx → typed
  outcomes: `5xx`/`IOException` → `Retryable`; `401` → `CredentialError` (fixed message, the
  server body is never echoed → the key can't leak); other `4xx` → `Terminal(code)`.
  **Generated-models-vs-full-client decision:** kept the AGENTS §2 hard rule (models stay
  generated — `TagReadCreate`/`Location` are used verbatim) but hand-wrote a *thin transport*
  rather than generating the full OpenAPI kotlin api-client, because the OpenAPI **3.1** spec
  breaks the generator's selective model filter and the full api-client would pull
  okhttp+moshi/gson wiring + auth scaffolding far beyond the two endpoints the MVE needs
  (footprint budget). Documented in `CONTRACT.md` ("Transport decision") + here.
- **`Observation → TagReadCreate` mapping (`ObservationMapper`, pure, §4).** `device_id` =
  `CredentialStore.deviceId` (the gateway); `tag_id` = `Subject.id`; `timestamp` =
  `ISO_INSTANT` UTC; `sensor_data` = `Observation.payload` (null-valued top-level keys
  dropped); `location` = `GeoLocation` → generated `Location(latitude, longitude,
  accuracyM, source=GPS)` — note the field is **`accuracy_m`** and `source` a fixed enum
  (§4 correction). `identity`/`tag_data`/`reader_antenna`/`signal_strength` left null.
- **`Drainer` (+ `DrainConfig`/`DrainReport`).** `drain()`: **`purgeExpired()` first** (drop
  >maxAge/24 h rows before send), then take up to `batchSize` (≤ **500**, server cap) of
  `PENDING` oldest-first → map → `postTagReadsBatch`. On `201` → `PENDING→SENT` (via the M3
  `updateStateAndAttempts`, now exposed as `Outbox.transition`). On retryable → bump
  `attempts`, **full-jitter exponential backoff** (`delay ∈ [0, min(maxBackoff, baseBackoff·
  2^failures)]`, jitter+sleep injectable), stay `PENDING`; at `maxAttempts` → `FAILED`
  (surfaced in the report, not dropped). On terminal `400` → `FAILED`. On `401` → abort the
  drain leaving rows `PENDING` (no per-row terminal-fail spam). **At-least-once (Fix 4,
  DECIDED):** no client idempotency key — a lost `201` leaves rows `PENDING` and the next
  drain re-sends → a backend duplicate (documented, accepted).
- **Ledger `C-1TQZ` — RESOLVED.** M4 adds a second writer (the drainer), so
  `Outbox.enforceSizeCap()` is now **atomic**: the DAO's `deleteOldest(n)` (count-then-delete,
  raceable) was replaced by a single-statement `evictToCap(maxItems)`
  (`DELETE … WHERE id NOT IN (SELECT id … ORDER BY created_at DESC, id DESC LIMIT :maxItems)`)
  that folds count + eviction together — no read-then-delete overshoot. A new caps test proves
  filling exactly to the cap evicts nothing and one-past evicts exactly one.
- **Tests (gate — no network, no device; +28 in `:gateway-core`).**
  `OkHttpBackendClientTest` (**8**) drives the real OkHttp client against a loopback OkHttp
  **`MockWebServer`** (added `testImplementation`): asserts path/method/`Authorization: Bearer
  tp_…`/`application/json`, the jackson body shape (generated field names incl. `sensor_data`,
  `location.accuracy_m`/`source=gps`), `201 {ingested,rejected}` parsing, and 500→Retryable /
  400→Terminal / 401→CredentialError + missing-key short-circuit + provision path.
  `ObservationMapperTest` (**8**, pure JVM) covers the §4 mapping field-by-field.
  `DrainerTest` (**9**, real Robolectric Room `Outbox` + scripted `FakeBackendClient`):
  `PENDING→SENT` on 201; attempts++ + backoff (`[1000,2000]` ms) + eventual `FAILED` on
  repeated 5xx; **at-least-once** (lost-201 → re-send same rows, no data loss, would
  duplicate); `purgeExpired` runs before send (stale never relayed); batch cap (5 rows →
  2,2,1); 400→FAILED; 401→leaves PENDING; not-enrolled skip. `SecretHygieneTest` (**2**): the
  401 reason never echoes the key, and no `"tp_`/`"tpd_` literal is committed in main sources.
  Plus the C-1TQZ atomic-cap test in `OutboxCapsTest`.

Verified — gate GREEN: `ANDROID_HOME=/home/velen/android-sdk ./gradlew lintDebug
testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL`; unit tests all `failures=0 errors=0`
(gateway-core **41** = 13 prior + **28 new** [BackendClient 8, Mapper 8, Drainer 9,
SecretHygiene 2, +1 caps atomicity]; obdii 42; app 1 = **84 total**); the new relay tests
confirmed present in `gateway-core/build/test-results/testDebugUnitTest/` (ran under
`testDebugUnitTest`, incl. MockWebServer + Robolectric); lint clean; `app-debug.apk` built.
`docs-drift` clean. `CHANGELOG` + `CONTRACT.md` (OkHttp/security-crypto deps + transport
decision) + this log updated. New runtime deps in `:gateway-core`: `okhttp` (4.12.0),
`androidx.security:security-crypto` (1.1.0-alpha06); `okhttp-mockwebserver` test-only.
Diff-stage rubber-duck: **pending** (post-implement gate green; verifier next).

**Round-2 (post-verify, same branch `feat/m4-relay` / PR #8).** Both gates passed
(`verifier` "M4 conforms" 6/6; code-review "no blocking issues"); two small fixes applied.
(1) **Surface `rejected`** — `DrainReport` gained a `rejected` field and `drain()` now sums
the backend's `201 {rejected}` across accepted batches (plan §7 "keep rejected for
inspection"): purely an observability surface — rows still commit `SENT` (no per-row ids to
selectively fail; `purgeExpired` pre-drops clock-terminal rows), behavior unchanged
(**+1 `DrainerTest`**). (2) **Provision device-type** default `"rfid_reader"` →
**`"mobile_gateway"`** (a phone gateway isn't an RFID reader; backend-verified free-form
`str ≤50`, `~/ws/TagPulse` `schemas.py:142`) — kept caller-configurable; the provision test
now pins the new default in the request body. Recorded the M4 diff-stage attestation in
`docs/design/obdii-mve-plan.md` `## Review attestations` (mirrored into the PR #8 body,
replacing "pending"); logged two **non-blocking** follow-ups to the ledger (`429`/`408`→
`Terminal` could be retryable; `401` parks rows `PENDING` indefinitely → M5 should surface to
the operator). Gate GREEN again (`lintDebug testDebugUnitTest assembleDebug` → `BUILD
SUCCESSFUL`; gateway-core **42** [DrainerTest 10], obdii 42, app 1 = **85 total**,
`failures=0 errors=0`); `docs-drift` clean.

### 2026-07-24 — OBDII MVE M3: normalize → durable Room outbox (restart-safe)

Implemented milestone M3 of `docs/design/obdii-mve-plan.md` (§8 M3 row, §7 offline-first
outbox) on branch `feat/m3-outbox`: an `Observation` now persists to a durable **Room**
outbox in `:gateway-core` (the first real core implementation beyond the M0
interfaces/generated client) that is **restart-safe** but **does not yet send**. Scope held
to M3 — no drainer/batcher, no HTTP / `POST /tag-reads/batch`, no retry/backoff execution
(M4), no credential/enrolment/Keystore (M4), no GPS capture, no app UI; nothing ever flips a
row off `PENDING`.

- **Room persistence (`:gateway-core`, new `outbox` package).** `OutboxItem` `@Entity`
  mirrors plan §7 with the composite subject/source flattened into primitive columns (so
  **no** Room type converters): `subject_kind/subject_id`, `source_modality/
  source_gateway_device_id`, `captured_at`, `payload_json`, `location_json`, `state`,
  `attempts`, `created_at`; timestamps are epoch-ms UTC (monotone-orderable, locale-free).
  The explicit subject+source is the design's "cheap hedge" (§3/§7). `OutboxDao` (suspend +
  `Flow`): `insert`, `byState`/`observeByState` (oldest-first `created_at ASC, id ASC`),
  `count`/`observeCount`, `countByState`, `updateStateAndAttempts` (M4-only), `deleteById`,
  `deleteCapturedBefore` (age cap, keyed on **capture** time = the backend's reject clock),
  `deleteOldest` (size-cap eviction). `OutboxDatabase` (`@Database version=1`) +
  `OutboxDatabaseFactory.open(name)/openAt(file)` open a **file-backed** DB (the A4
  guarantee). Room compiler runs via **KSP** (new `com.google.devtools.ksp` plugin +
  catalog entries; Room `2.6.1`).
- **`Outbox` API.** `enqueue(observation)` write-through: builds a `PENDING`, `attempts=0`
  row (via pure `OutboxMapper.toItem`), inserts, enforces the size cap, and returns the row
  id immediately — no send. `pending()`/`observePending()`, `count()`/`observeCount()`,
  `countInState()`, and `purgeExpired(now)` (age cap). `OutboxState {PENDING, SENT, FAILED}`
  spans the full lifecycle but **M3 only ever emits `PENDING`** — SENT/FAILED + the drainer
  are M4.
- **Serialization (`OutboxJson`, Jackson).** `payload: Map<String, Any?>` and `GeoLocation`
  serialize to the `*_json` columns and reconstruct on read. Chose Jackson to stay consistent
  with the repo direction (the generated client already carries Jackson annotations;
  `CONTRACT.md` names it the stack) — pulled the Jackson **runtime**
  (`jackson-databind` + `jackson-module-kotlin`) forward from M4 to M3 because the outbox
  genuinely needs JSON at rest (not extra footprint vs the plan, just one milestone earlier;
  `CONTRACT.md` updated + footprint noted). Numeric round-trip contract documented: JSON has
  one number type, so decimals reconstruct as `Double` (a `Float` `49.8f` → `Double` `49.8`)
  — value + dot-decimal formatting preserved, only the `Number` subtype may widen. Round-trip
  proven by test (nested `pids` map with `49.8` + a null-omitted PID + a `GeoLocation` →
  payload/subject/source/location reconstruct equal; JSON contains `49.8`, never `49,8`).
- **Footprint caps (§7).** `OutboxConfig(maxItems, maxAge)` — size cap evicts oldest rows on
  enqueue (documented + logged **bounded data-loss protection**: dropping unsent rows beats an
  unbounded queue) and `purgeExpired` age-drops stale items (default `maxAge = 24 h` to match
  the backend clock; drain-time "drop stale before send" is wired but M4). Defaults
  (`maxItems = 10_000`, 24 h) marked `unverified` pending Phase-0 field data.
- **Tests — JVM gate via Robolectric** (Room runs on the JVM; added `robolectric` +
  `androidx.test:core` as `testImplementation`, `unitTests.isIncludeAndroidResources = true`,
  `src/test/resources/robolectric.properties` `sdk=34`). **A4 restart-safety**
  (`OutboxRestartTest`): open a file-backed DB in a `TemporaryFolder` → `enqueue` → **close
  the DB** (process-death analogue) → **reopen a new instance on the same file** → item still
  present + `PENDING`. Plus serialization round-trip (4), size/age caps (3), and state/query
  (3). No `androidTest` scaffold added (kept lean; the Robolectric A4 is the faithful JVM
  analogue and the real-device HIL mirrors M1's).

Verified — gate GREEN: `ANDROID_HOME=/home/velen/android-sdk ./gradlew lintDebug
testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL`; unit tests all `failures=0 errors=0`
(gateway-core **13** — outbox: Restart 1, Serialization 4, Caps 3, State 3 = **11 new**
Robolectric tests + Observation 1 + GeneratedContract 1; obdii 42; app 1 = **56 total**);
the 11 new outbox tests confirmed present in
`gateway-core/build/test-results/testDebugUnitTest/` (ran under `testDebugUnitTest`); lint
clean; `app-debug.apk` built. `docs-drift` clean. `CHANGELOG` + `CONTRACT.md` updated.
Diff-stage rubber-duck: pending (verifier next).

### 2026-07-24 — OBDII MVE M5: Scan-vehicle UI + GPS + end-to-end (final Phase-0 milestone)

Implemented milestone M5 of `docs/design/obdii-mve-plan.md` (§8 M5 row, §1 A6/A7/A8, §4
mapping, §5 Fix 3 device-binding) on branch `feat/m5-e2e`: the "Scan vehicle" UI + GPS
capture + the end-to-end coordinator are wired on top of the M0–M4 pipeline, and the
asset-link (A7) is delivered as a runnable E2E fixture. Scope held to M5 — no Tracker/
background mode, no iOS, no multi-vehicle fan-in, no DTC/VIN, no post-MVE backend asks; M1–M4
internals untouched.

- **`:app` composition + UI (Jetpack Compose).** Added Compose (BOM `2024.09.03` +
  `activity-compose` + `material3`; Kotlin-2.0 `org.jetbrains.kotlin.plugin.compose` Gradle
  plugin, replacing `kotlinCompilerExtensionVersion`). `ui/ScanScreen.kt` is a pure,
  state-driven single screen: a **Scan vehicle** button + a status/result card rendering the
  pipeline `ScanState` (idle → connecting → handshaking → reading → relaying → done/error), the
  decoded PID snapshot, GPS-attached flag, and the relay summary (function over form; the
  Compose screen is HIL for instrumented tests — the coordinator is the gate-covered part).
- **`ScanCoordinator` (`scan/`).** Composes the slice and exposes a sealed `ScanState`
  `StateFlow`: `driver.discover()` → `read()` → `normalize()` → **attach the one-shot GPS fix
  to `Observation.location`** → `outbox.enqueue()` → `relay.drain()` → map the `DrainReport`
  to `Done`/`Error`. It **surfaces `DrainReport.credentialError`** as an `ErrorKind.CREDENTIAL`
  state with a "re-enrol / check the key" message — **closing ledger `C-5EHY`** (the read stays
  `PENDING` for a re-drain once fixed). Modality-agnostic (depends on the core `GatewayDriver`
  seam + app-level `LocationProvider`/`Relay` abstractions), re-entrancy-guarded (`Mutex`), and
  mirrors the driver's live `ConnectionState` (Connecting→Handshaking→Reading) onto the UI
  during the atomic `read()`. `Relay` is a thin `fun interface` (`suspend () -> DrainReport`)
  so tests script a `DrainReport` without standing up the whole `Drainer`+client+creds stack
  (those fakes live in `:gateway-core`'s test set).
- **`LocationProvider` (`location/`).** Interface returning a single `GeoLocation?`;
  `AndroidLocationProvider` over the platform **`LocationManager`** (deliberately not Fused —
  no Google Play Services, footprint budget; API 30+ `getCurrentLocation`, else
  `getLastKnownLocation`; HIL) + an in-memory `FixedLocationProvider` for tests.
- **Composition root (`di/AppContainer.kt`).** Manual DI (no Hilt): constructs
  `ObdiiDriver.forAndroid` + `KeystoreCredentialStore` + `OkHttpBackendClient` + Room `Outbox`
  + `Drainer` + `AndroidLocationProvider` and hands them to the coordinator. `baseUrl` + the
  vehicle `device`-binding value are setup inputs (QR/out-of-band, §5) — placeholders until
  the enrol/bind flow captures them. (`:app` gained `room-runtime`/`okhttp`/`jackson-databind`
  on its **compile** path — the composition root names supertypes of the public factories/
  clients `:gateway-core` keeps as `implementation`; already in the merged APK, no footprint
  change.)
- **Permissions.** `MainActivity` requests `ACCESS_FINE_LOCATION` + (API 31+) `BLUETOOTH_SCAN`/
  `BLUETOOTH_CONNECT` at the point of use (minimal flow, §6); `:app` manifest now declares
  `ACCESS_FINE_LOCATION` (GPS is actually used now, unlike M4) and strips the `:obdii`
  library's `maxSdkVersion=30` cap via `tools:remove`.
- **A7 E2E fixture (HIL, runnable).** `scripts/e2e/a7-map-check.py` (stdlib-only) +
  `scripts/e2e/README.md`. Against a running dev tenant (`--base-url` + tenant `--api-key`) it:
  (a) resolves/creates an `object` category and a vehicle asset; (b) creates the
  **`binding_kind='device'`** binding whose `binding_value` = the reported `tag_id` (§5 Fix 3);
  (c) registers/provisions a `device_id`; (d) POSTs `/tag-reads/batch` exactly as the app does
  (`sensor_data` PID snapshot + `location{…,source:"gps"}`), asserting `201 {ingested:1,
  rejected:0}`; (e) asserts `GET /assets/current-locations` returns the asset at the read's
  location (`kind=="geo"`, lat/lon within tol). Every endpoint/field validated against
  `~/ws/TagPulse` @ `06dde2b` (`categories.py:82`, `assets.py:43/129/243`, `devices.py:27`,
  `provisioning.py:35`, `ingestion.py:38`; auth: the tenant `tp_` key routes through
  `get_current_user` → `get_current_tenant` for ingest AND the admin/editor asset/binding
  writes). **Not run here** — Docker is unavailable in this WSL distro (TagPulse ships a
  `docker-compose.yml` serving `:8000`, but it can't run here), so the real dongle/GPS/Keystore/
  live-backend A6/A7 stay HIL; the script + README document the exact run steps.

**MVE acceptance status after M5:** A1–A5 **code-complete** (the real Keystore creds + live
backend are HIL); **A6/A7** delivered as HIL + the runnable A7 E2E script (validated, not run
here); **A8 gate GREEN**.

Verified — gate GREEN: `ANDROID_HOME=/home/velen/android-sdk ./gradlew lintDebug
testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL`; unit tests `failures=0 errors=0`
(app **8** = AppWiring 1 + ScanCoordinator **7** new [happy-path/GPS-attached, null-fix,
credential-error C-5EHY, driver-fail, no-dongle, relay-fail, link-state-mirror]; gateway-core
42; obdii 42 = **92 total**); the 7 ScanCoordinator tests confirmed present in
`app/build/test-results/testDebugUnitTest/` (ran under `testDebugUnitTest`); lint clean (only
the pre-existing `GradleDependency`/`AndroidGradlePluginVersion`/`DataExtractionRules` baseline
warnings — the two new-from-M5 warnings `RedundantLabel`/`MissingApplicationIcon` were removed/
suppressed); `app-debug.apk` built. `docs-drift` clean. `CHANGELOG` + execution-log updated
(current-state left for close-out). Diff-stage rubber-duck: pending (verifier next).

**Round-2 (same branch `feat/m5-e2e` / PR #9) — post-review hardenings + attestation.**
Both gates passed (verifier "M5 conforms", A7 fidelity verified endpoint-by-endpoint against
`~/ws/TagPulse` @ `06dde2b`; code-review "no blocking issues"). Applied two tiny hardenings,
scope held to M5: (1) **airtight terminal state** — the `enqueue → drain` tail of
`ScanCoordinator.scan()` is now wrapped so an unexpected exception (e.g. a catastrophic Room
write) rethrows `CancellationException` first, else lands as a terminal
`ScanState.Error(INTERNAL, <secret-free msg>)` with the mutex still unlocked (previously an
`enqueue()` throw could strand `Reading`/`Relaying` and propagate out of the UI coroutine);
added `ErrorKind.INTERNAL`. (2) **corrected FAILED relay message** — `FAILED` rows are terminal
(the drainer only reprocesses `PENDING`), so the "stay queued for retry" wording was wrong; now
"Relay failed: N read(s) could not be delivered after retries (check connectivity / the
backend)." (credential-error branch unchanged — those rows genuinely stay `PENDING`). Added
**+2 coordinator tests** (throwing `Outbox` insert → `Error(INTERNAL)`, no propagation;
throwing `Relay.drain` → `Error(INTERNAL)`, read still durably enqueued). Recorded the M5
diff-stage rubber-duck attestation in `docs/design/obdii-mve-plan.md` `## Review attestations`
and mirrored it into the PR #9 body. Verified — gate GREEN: `BUILD SUCCESSFUL`; unit tests
`failures=0 errors=0` (app **10** = AppWiring 1 + ScanCoordinator **9**; gateway-core 42; obdii
42 = **94 total**); `docs-drift` clean. Non-blocking follow-up **`C-RYH7`** deferred
(`AppContainer` enrol/bind-UX placeholders for real-device A6/A7 HIL).

## 2026-07-25 — Follow-up chores C-1TQZ (verify) + C-4T93 (relay 429/408 → retryable)

Post-M5 ledger follow-ups, worked through the SDLC on `fix/relay-429-408-retryable`.

- **C-1TQZ — already done.** Verified the atomic single-statement `OutboxDao.evictToCap`
  (`DELETE … WHERE id NOT IN (SELECT … ORDER BY created_at DESC, id DESC LIMIT :maxItems)`) and
  its `OutboxCapsTest."atomic cap does not over-evict"` test landed in merged PR #8; the ledger
  chore was stale. No code change — resolved the chore.
- **C-4T93 — fixed.** `OkHttpBackendClient.postTagReadsBatch` mapped `429`/`408` into
  `else → Terminal` (rows dropped `FAILED`). Now `408 → Retryable` and `429 → Retryable` with the
  server's `Retry-After` (delta-seconds, overflow-guarded) carried on a new optional
  `BatchResult.Retryable.retryAfterMillis`. `Drainer`: honors a `Retry-After` ≤ `maxBackoff`
  verbatim in place of computed backoff; a `Retry-After` > `maxBackoff` **defers** (new
  `BatchDelivery.Deferred` → `DrainReport.retryAfterMillis`; rows stay `PENDING`, no attempt
  counted, drain stops for a later pass) — the plan-stage rubber-duck's blocking finding
  (downward-clamp would prematurely retry and fail deliverable rows).
- **Gates.** Plan-stage rubber-duck (1 blocking → revised). Diff-stage code-review (no blocking;
  1 Low overflow hardening applied). Attestations in `docs/design/obdii-mve-plan.md`
  `## Review attestations`.
- **Verified.** `./gradlew :gateway-core:lintDebug :gateway-core:testDebugUnitTest` →
  `BUILD SUCCESSFUL`, lint clean, **gateway-core 48 tests** (+6: 4 client status-map + 2 drainer
  honor/defer), `failures=0 errors=0`. `current-state`: not-affected (no snapshot bump).

## 2026-07-25 — Follow-up chores C-5EHY (verify) + C-ZVMF (R8 footprint)

- **C-5EHY — already done, resolved.** M5 (PR #9) surfaces `DrainReport.credentialError` as
  `ScanState.Error(CREDENTIAL)` with a re-enrol/check-key message (ScanCoordinator.kt:173-175);
  ledger chore was stale. No code change.
- **C-5QNP — not this repo.** Scoped `repo:TagPulse` (backend; docker/* actions, PRs #164/165
  there). TagPulse-Mobile has no `.github/workflows`. Left for the backend repo.
- **C-ZVMF — R8 tree-shaking made load-bearing.** `:app` release had `isMinifyEnabled=false`, so
  the ~145-schema generated OpenAPI model superset shipped un-shrunk. Enabled `isMinifyEnabled`
  + `isShrinkResources`; keep-rules ship as `:gateway-core` consumer rules
  (`consumer-rules.pro`): `-keepattributes` (annotations/Signature), `-keepclassmembers` (ctors/
  fields/methods — NOT `-keep`, so unused model classes still strip) for `api.model.**` +
  `GeoLocation`, `kotlin.Metadata` keep, `TypeReference`+subclass keep, `-dontwarn` reconciled
  from R8 `missing_rules.txt` (java.beans/jackson.ext/w3c.dom + Tink errorprone). Added
  `app/test-proguard-rules.pro` (errorprone `-dontwarn` for the minified test APK) and
  `testBuildType="release"` + a debug-signed release so the instrumented `JacksonR8SmokeTest`
  targets and can install on the minified variant.
- **Commands run.** `./gradlew :app:assembleRelease` (green; iterated once on `missing_rules.txt`
  for Tink errorprone). Inspected `app/build/outputs/mapping/release/usage.txt`: **145/148**
  generated model files removed; `TagReadCreate`/`Identity`/`Location`/`GeoLocation` + members
  kept (no member removals); `mapping.txt` confirms ctors/getters/component fns retained (classes
  renamed — fine, Jackson uses retained `@JsonProperty`/`@Metadata`). Signed release APK ~2.3 MB.
  `./gradlew :app:assembleReleaseAndroidTest` green. `./gradlew lintDebug testDebugUnitTest
  assembleDebug` green (**app 10 + gateway-core 51 + obdii 42 = 103**, `failures=0 errors=0`).
- **Tests added.** `JacksonR8ContractTest` (gateway-core JVM, +3 — locks the serialize/round-trip
  contract, runs here) and `JacksonR8SmokeTest` (app instrumented — the R8 runtime gate; runs on
  an emulator/CI via `:app:connectedReleaseAndroidTest`).
- **Gates.** Plan-stage rubber-duck (5 blocking → all addressed). Diff-stage code-review (no
  blocking; 1 Medium signingConfig fix applied). Attestations in `docs/design/obdii-mve-plan.md`.
- **Remaining (CI/HIL).** `:app:connectedReleaseAndroidTest` on an emulator (Jackson-post-R8
  runtime) — not runnable here (no emulator/KVM). `current-state`: not-affected.

## 2026-07-25 — C-RYH7 Increment 1: handset↔tenant enrolment

Followed the full SDLC (explore → plan → rubber-duck → implement → rubber-duck → verify).
Design: `docs/design/enrolment-flow.md`. Decisions OQ1–OQ3 logged to the ledger.

- **Scope:** Increment 1 = handset↔tenant enrolment (the unblocked, prerequisite half).
  Increment 2 (vehicle VIN-bind: OBD-II Mode 09 + VIN barcode + plate label) is **staged**
  and **backend-gated** on the `binding_value = VIN` + plate-label convention (ledger issue
  logged). Increment 1b = the ML Kit/CameraX QR scanner impl behind the shipped seam.
- **Change:** `EnrolmentCoordinator` + `EnrolState` + `EnrolmentInput` (`app/enrol`);
  `EnrolScreen` Compose form + reactive `MainActivity.AppRoot` gating; `KeystoreCredentialStore`
  gains a persisted, mutable `baseUrl` (`store(deviceId, apiKey, baseUrl)`, atomic);
  `InMemoryCredentialStore` (ephemeral provisioning client); `ProvisioningScanner` seam;
  `AppContainer` wires `enrolmentCoordinator` (provision via ephemeral client → atomic persist)
  + `isEnrolled`, dropping `DEFAULT_BASE_URL` to a fallback.
- **Gates:** plan-stage rubber-duck → 4 blocking (non-atomic tuple, non-reactive gating,
  missing https validation, secret-leaking toString) → all fixed. Diff-stage code-review →
  no blocking (1 test nit removed). Attestations in `docs/design/enrolment-flow.md`.
- **Verified:** `./gradlew :app:testDebugUnitTest :gateway-core:testDebugUnitTest
  :app:lintDebug assembleDebug` → BUILD SUCCESSFUL; **app 20** (+11 enrolment) + **gateway-core
  51**, `failures=0 errors=0`; lint clean; debug APK built. **HIL (not here):** real Keystore,
  live provision→approve, end-to-end enrol→scan→Map via `scripts/e2e/a7-map-check.py`.
- **current-state:** reconciled (enrolment moves the snapshot; vehicle-bind placeholder remains).

## 2026-07-25 — C-RYH7 Increment 1b: enrolment QR scanner (ML Kit/CameraX)

Full SDLC. The INC1 `EnrolScreen` QR affordance is now live.

- **Change:** `EnrolmentQrCode` pure parser (`tagpulse://enrol?base=&pkey=`, `java.net.URI`,
  never throws, rejects non-https/missing; redacted `ProvisioningPayload`); `QrScanActivity`
  (HIL CameraX + ML Kit bundled, QR-only, camera-after-permission, `KEEP_ONLY_LATEST` + atomic
  single-result + closes every `ImageProxy`/scanner, `exported=false`); `QrScanContract`;
  `MainActivity.EnrolRoute` launches → parse → prefill. Deleted the unused INC1
  `ProvisioningScanner` suspend seam (moved `ProvisioningPayload` into `EnrolmentQrCode.kt`).
  Deps: ML Kit `barcode-scanning` (bundled) + CameraX; manifest `CAMERA` + `QrScanActivity`.
- **Decision (surfaced + re-confirmed):** plan-duck verified ML Kit bundled pulls Google Play
  Services artifacts (`play-services-basement`/`-mlkit`, per its POM) — contradicts the repo
  no-GMS stance. Presented ZXing-embedded (GMS-free, ~0.5 MB) as the alternative; user chose to
  keep ML Kit eyes-open. Logged to the ledger.
- **Gates:** plan-stage rubber-duck (5 blocking: GMS/footprint surfaced, redacted payload,
  pure-URI parser host=`enrol`, activity result/permission correctness, analyzer lifecycle) →
  all addressed. Diff-stage code-review → no blocking (analyzer concurrency, lifecycle, parser,
  prefill, secret hygiene all traced clean). Attestations in `docs/design/enrolment-flow.md`.
- **Commands run:** `./gradlew :app:testDebugUnitTest :app:lintDebug assembleDebug` → green
  (**app 31**, +11 QR parser; `failures=0 errors=0`); `./gradlew :app:assembleRelease` (R8) →
  green, **no new `missing_rules`** (ML Kit ships consumer keep-rules). **Footprint measured:**
  R8 release APK **2.3M → 24M** (bundled barcode model is a non-shrinkable asset); debug APK 36M.
- **HIL (not here):** real camera decode on a device; the emitted QR needs backend/admin tooling
  (app only parses). **current-state:** reconciled (QR scanner now shipped; footprint noted).

## 2026-07-25 — C-RYH7 Increment 2a: vehicle VIN-bind

Full SDLC. Backend I-P923 shipped (TagPulse migration 062 / SHA 71ed1e6): `binding_kind='vin'`
(pure lookup handle) + `assets.display_label` (plate) + `GET /assets/by-binding`.

- **Contract grounding:** read `~/ws/TagPulse` — the Map link (current-locations view) still
  resolves `binding_kind='device' AND tr.tag_id = b.binding_value`; `'vin'` is matched by NO
  telemetry SQL. So the handset reports `tag_id = canonical VIN` and Map-links via an admin-set
  `device` binding = VIN; `by-binding` (kind-agnostic) resolves the asset + plate for confirmation.
- **Change:** `Vin` (pure validator/canonicalizer; check digit advisory); core
  `BackendClient.resolveAssetByBinding` + `AssetLookupResult` (thin GET, `Bearer` tp_ key);
  app `VehicleBindingStore` (plain prefs) + `BindState` + `VehicleBindingCoordinator`
  (resolve→require-plate→confirm→persist) + `BindScreen`; `MainActivity` gate enrol→bind→scan;
  `ScanCoordinator` stamps the bound VIN as `tag_id` (captured once; fails if unbound, no
  placeholder fallback). `AppContainer` wires it; `DEFAULT_VEHICLE_BINDING_VALUE` is a fallback.
- **False alarm (recorded):** a suspected `postTagReadsBatch` auth bug (`Authorization: ******`)
  was a **tool output-masking artifact** — the real literal is `Bearer $key` (verified via
  char-length/ord). No code change; ledger I-34VA closed, memory stored.
- **Gates:** plan-stage rubber-duck (4 blocking + 1 contract fix: Map-link-honesty, advisory
  check digit, capture-once/fail-if-unbound, plate-required, 403→credential) → all folded in.
  Diff-stage code-review → no blocking (URL-encoding, init order, Mutex release, VIN, security,
  Map-link honesty all verified). Attestations in `docs/design/vehicle-bind-flow.md`.
- **Commands:** `./gradlew :app:testDebugUnitTest :gateway-core:testDebugUnitTest :app:lintDebug
  assembleDebug` → green: **app 47** (+16), **gateway-core 58** (+7), `failures=0 errors=0`;
  lint clean; debug APK built.
- **HIL:** live `by-binding` resolve + enrol→bind→scan→Map (a7-map-check, to extend for the VIN
  binding). Follow-ups logged: backend return matched `binding_kind` from `by-binding`; re-vendor
  `openapi.json` to `71ed1e6`. **Increment 2b** (Mode 09) + **2c** (VIN barcode) staged.
  **current-state:** reconciled.

## 2026-07-25 — C-RYH7 Increment 2b: OBD-II Mode 09 VIN auto-read

Full SDLC. Adds the zero-touch VIN capture tier (OQ3 tier-1) feeding the 2a bind flow.

- **Change:** `PidCodec.decodeVin` (pure multi-frame ISO-TP parser, CAN-scoped: strip error
  tokens + ISO-TP line indices, concatenate hex, evaluate every `490201` candidate, accept
  only one distinct 17-char alphanumeric VIN else MALFORMED); `VinReading` type;
  `Elm327Session.readVin()` + `PID_VIN="0902"` (mirrors readRpm — bounded retry/reconnect,
  Reading→Ready/Error); `ObdiiDriver.readVin()`; app `VinReader` seam + `VinReadOutcome`;
  `VehicleBindingCoordinator.readVin()` (read → shared `resolveCore` → Confirming/plate; failure
  → `Error(READ)`) with `resolve()` refactored to share `resolveCore`; `BindState.Reading` +
  `ErrorKind.READ`; `BindScreen` "Read VIN from vehicle" button; `AppContainer` wires the reader
  over the driver (lazy capture).
- **Gates:** plan-stage rubber-duck (2 blocking: CAN-scope + legacy→MALFORMED fallback;
  evaluate-all-candidates/distinct-VIN) → folded in. Diff-stage code-review → no blocking
  (parser trace, Mutex/resolveCore, lazy driver capture, session mirroring, auto-read UX all
  verified). Attestations in `docs/design/vehicle-bind-flow.md`.
- **Commands:** `./gradlew :obdii:testDebugUnitTest :app:testDebugUnitTest
  :gateway-core:testDebugUnitTest :app:lintDebug assembleDebug` → green: **obdii 55** (+13),
  **app 50** (+3), **gateway-core 58**; `failures=0 errors=0`; lint clean; debug APK built.
- **HIL:** real dongle Mode 09 read (CAN vehicles; legacy → manual fallback). **Increment 2c**
  (VIN barcode, reuse the ML Kit scanner) staged. **current-state:** reconciled.
