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
