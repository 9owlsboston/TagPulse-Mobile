# Enrolment flow (handset ↔ tenant) — design

> **Summary.** The handset can't reach a live tenant today: `AppContainer` hard-codes
> `DEFAULT_BASE_URL` and there is no UI to obtain a `device_id` or store the ingest
> key — `provisionDevice()` and `KeystoreCredentialStore.store()` exist but have **no
> caller**. This adds the **handset↔tenant enrolment** flow (ledger `C-RYH7`,
> Increment 1): capture `baseUrl` + provisioning key + tenant `tp_` ingest key →
> `POST /devices/provision` → persist all enrolment facts in the Android Keystore →
> gate the "Scan vehicle" screen behind `isEnrolled`. It implements plan §5
> "Handset enrolment"; the **vehicle VIN-bind** half (OBD-II Mode 09 + VIN barcode +
> plate label) is **Increment 2**, deferred — it's backend-gated on the
> `binding_value = VIN` convention.

Scope owner: `C-RYH7`. Related: plan `obdii-mve-plan.md` §5; decisions OQ1–OQ3
(ledger). This is **product code + tests**; the real camera/Keystore/network paths
are **HIL** (mirrors the M1–M5 seam pattern), the coordinator logic is unit-tested
with fakes.

## Decisions carried in (from the `/explore C-RYH7` OQ discussion)

- **OQ1 — QR decoder:** ML Kit barcode (bundled, no Play Services) + CameraX, manual
  paste backup. **INC1 ships the seam + manual path only**; the ML Kit/CameraX impl
  is **Increment 1b** (HIL, un-runtime-verifiable here). iOS→VisionKit later (separate
  codebase).
- **OQ2 — QR payload:** carries `baseUrl` + provisioning key only; the sensitive
  `tp_` ingest key is entered via the manual (masked) paste field.
- **OQ3 — vehicle key:** VIN (Increment 2, backend-gated). Out of INC1 scope.

## What changes

### 1. Persisted, mutable `baseUrl` (core)

`CredentialStore.baseUrl` is read per request already; today `KeystoreCredentialStore`
takes it as an **immutable constructor arg**, so enrolment can't set it. Change:
persist `baseUrl` in the encrypted prefs (new `KEY_BASE_URL`); the constructor arg
becomes a **fallback default** (`prefs value ?: fallback`); extend `store()` to write
`baseUrl` alongside `device_id` + `apiKey`. `isEnrolled` stays `deviceId && apiKey`
(baseUrl always has a fallback). No interface break — `baseUrl` remains a `val` getter.

### 2. `EnrolmentCoordinator` (app, mirrors `ScanCoordinator`)

Pure orchestration exposing an `EnrolState` `StateFlow`. It depends on the real
`KeystoreCredentialStore` (for the atomic `store`) and a **provision seam** —
`provision: suspend (baseUrl, provisioningKey, name) -> ProvisionResult` — so it never
mutates persisted credentials before success (rubber-duck finding #1). One `enrol(input)`:

1. **Validate** `input`: non-blank fields **and** `baseUrl` parses as an **HTTPS**
   `HttpUrl` with a host (reject `http://`/malformed → keys must never travel in
   plaintext, finding #3) → else `EnrolState.Error(INPUT, …)`, no network.
2. `provision(baseUrl, provisioningKey, name)` — runs against the **candidate**
   `baseUrl` via an ephemeral client (AppContainer wires it as
   `OkHttpBackendClient(InMemoryCredentialStore(baseUrl))`), leaving the persisted
   store untouched.
3. On `ProvisionResult.Registered(deviceId, status)` → **atomically** persist all three
   facts in **one** prefs edit: `credentials.store(deviceId, ingestApiKey, baseUrl)` →
   `EnrolState.Enrolled(deviceId, status)`. On `Failed` → `EnrolState.Error(PROVISION,
   <secret-free>)`, **nothing persisted** (no half-enrolled tuple, finding #1).
4. Re-entrant calls serialized by a `Mutex`; an unexpected throw rethrows
   `CancellationException` else lands `EnrolState.Error(INTERNAL, …)`, lock released.

`EnrolmentInput` is **not** a plain data class: its `toString()` is **redacted** so the
provisioning key + `tp_` key can't leak through a log line (finding #4).

**Admin approval is out-of-band** (`POST /device-registry/{id}/approve`, admin-only)
and **not** automated here. Provision success (a `device_id`) is treated as enrolled:
per the OQ3 exploration finding, ingest authenticates with the tenant `tp_` key and
the Map link keys on `tag_id`, **not** `device_id`, so an unapproved (`pending`) device
can still relay. The returned `status` is surfaced for operator visibility.

### 3. `ProvisioningScanner` seam (app)

`interface ProvisioningScanner { suspend fun scan(): ProvisioningPayload? }` returning
a parsed `{ baseUrl, provisioningKey }`. INC1 provides the interface + a test fake; the
UI's QR affordance is shown **only when a scanner is wired** (`AppContainer` passes
`null` in INC1 → manual-only, no dead button). The ML Kit/CameraX impl is INC1b.

### 4. `EnrolScreen` (app, Compose) + routing

A single-screen form: `baseUrl`, provisioning key, `tp_` ingest key (masked), a
**Provision** button, and a state/result area (idle → provisioning → enrolled/error).
Routing is **reactive** (rubber-duck finding #2): the top composable **collects
`EnrolmentCoordinator.state`** (and initial `isEnrolled`) and branches to `ScanScreen`
on `Enrolled`, so a successful enrolment recomposes into scanning without an
`AppContainer` rebuild — the `Drainer` reads `credentials.deviceId` **at drain time**
(lazy), and `OkHttpBackendClient` reads `baseUrl` per call, so the shared credential
store surfaces the new facts to the already-constructed scan path.

### 5. `AppContainer` wiring

Expose `enrolmentCoordinator` + an `isEnrolled` read; drop `DEFAULT_BASE_URL` as the
source of truth (it becomes the `KeystoreCredentialStore` **fallback** only). The
`DEFAULT_VEHICLE_BINDING_VALUE` placeholder stays (Increment 2 owns it).

## Security (AGENTS §2)

`tp_` key masked in the field, never logged, never in an `EnrolState.Error` message
or `toString()`; persisted only in `EncryptedSharedPreferences`. All enrolment facts
land in the Keystore. Error messages are secret-free and operator-actionable.

## Tests (JVM, fakes — runs in the gate)

`EnrolmentCoordinatorTest` (Robolectric only if the real Keystore is needed; prefer a
`FakeCredentialStore` capturing `store()` args): happy path (provision called with the
provisioning key + right baseUrl; `store()` gets deviceId+`tp_`+baseUrl; state
`Enrolled`); blank inputs → `Error(INPUT)`, **zero** backend calls; provision `401`/
network `Failed` → `Error(PROVISION)`, nothing persisted; baseUrl-set-before-provision
ordering; unexpected throw → `Error(INTERNAL)`, lock released. Plus a
`KeystoreCredentialStore` persisted-baseUrl unit (Robolectric, matching the existing
store test boundary) if feasible, else documented HIL.

## Verification & HIL boundary

Gate: `./gradlew :app:lintDebug :app:testDebugUnitTest :gateway-core:testDebugUnitTest
assembleDebug`. **HIL (not here — no emulator/device):** real Keystore persistence, a
live provision→approve against a dev tenant, and the end-to-end enrol→scan→Map via
`scripts/e2e/a7-map-check.py`. INC1b adds the ML Kit QR scanner (also HIL).

## Out of scope (staged)

- **Increment 1b:** ML Kit/CameraX `ProvisioningScanner` impl (QR).
- **Increment 2:** vehicle VIN-bind — OBD-II **Mode 09** auto-read (new multi-frame
  parsing; today `PidCodec` is single-frame Mode 01), VIN barcode (reuses INC1b
  scanner), plate display label. **Backend-gated:** `binding_value = VIN` convention +
  an asset plate-label field (ledger issue logged).

## Review attestations

- **Plan-stage rubber-duck:** **ran → 4 blocking findings**, all addressed before/at
  implementation: (1) **non-atomic credential tuple** — provisioning now runs against an
  ephemeral `InMemoryCredentialStore(candidateBaseUrl)` and the persisted store is written
  **only on success** via one atomic `store(deviceId, apiKey, baseUrl)` (no half-enrolled
  state on failure/cancel); (2) **non-reactive gating** — `MainActivity.AppRoot` collects
  `EnrolmentCoordinator.state` and branches to `ScanRoute` on `Enrolled`/`isEnrolled`
  (recompose suffices; `Drainer` reads `device_id` at drain time); (3) **missing HTTPS
  validation** — `baseUrl` is parsed as an `HttpUrl` and required `https` (keys never travel
  plaintext); (4) **secret-leaking `toString()`** — `EnrolmentInput` is a plain class with a
  redacted `toString()`.
- **Diff-stage rubber-duck (code-review):** **ran → no blocking issues.** Independently
  verified all four fixes, the drain-time `device_id` flow (the construction-time
  `Source.gatewayDeviceId=null` is inert — the wire `device_id` comes from the drain-time
  param), the `Mutex` release on every path (the early `validate ?: return` sits inside the
  `try`, so `finally { unlock() }` always runs), `baseUrl` origin normalization vs
  `OkHttpBackendClient.url()`, secret hygiene (only status code / `deviceId` /
  exception-type ever logged), and test coverage. One non-blocking nit (a tautological
  `assertNull(null)`) removed.
- **Verification:** `:app:testDebugUnitTest` (**20**, incl. **+11** enrolment) +
  `:gateway-core:testDebugUnitTest` (**51**) green; `:app:lintDebug` clean; `assembleDebug`
  built. `failures=0 errors=0`.
- **HIL (not run here):** real Keystore persistence + a live `provision→approve` against a
  dev tenant + end-to-end enrol→scan→Map (`scripts/e2e/a7-map-check.py`). Increment 1b (ML
  Kit QR scanner) and Increment 2 (vehicle VIN-bind) are staged follow-ups.
- **current-state:** updated (the enrolment flow moves the "where we are now" snapshot — the
  handset can now be enrolled against a live tenant; the vehicle-bind placeholder remains).
