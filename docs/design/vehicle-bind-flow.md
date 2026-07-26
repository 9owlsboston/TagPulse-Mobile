# Vehicle bind flow (VIN → asset) — design

> **Summary.** After enrolment, the handset must be **bound to one vehicle** so its reads
> Map-link to the right asset. This adds the **vehicle VIN-bind** flow (ledger `C-RYH7`,
> Increment 2a): capture a **VIN**, validate it, **resolve it against the backend**
> (`GET /assets/by-binding?value=<VIN>`) to fetch the vehicle asset + its **license plate**
> (`display_label`) for operator confirmation, persist the binding, and stamp the canonical
> VIN as the reads' `tag_id`. It replaces the `AppContainer` `DEFAULT_VEHICLE_BINDING_VALUE`
> placeholder and gates the "Scan vehicle" screen behind a bound vehicle.

Scope owner: `C-RYH7`. Backend contract: **shipped** (`I-P923`, TagPulse migration 062 /
SHA `71ed1e6`; design `TagPulse:docs/design/asset-display-label-vin-lookup.md`). This is
**product code + tests**; the live backend resolve + Map assertion are **HIL**.

## Backend contract (verified against `~/ws/TagPulse` @ `71ed1e6`)

- **`binding_kind='vin'`** is a **pure lookup handle** — matched by **none** of the
  telemetry/Map SQL. The Map link (current-locations view) still resolves
  `binding_kind='device' AND tr.tag_id = b.binding_value`. So a vehicle Map-links from the
  handset's reads via an admin-set **`device`** binding whose value = the VIN, and the
  handset reports **`tag_id = canonical VIN`**. (Admin setup of that binding is a
  prerequisite, plan §5 — not the app's job.)
- **`GET /assets/by-binding?value=<VIN>`** → `AssetResponse` (`200` with `id` +
  `display_label` = plate) or `404`. **Kind-agnostic**, active bindings only, tenant-scoped,
  `require_role("admin","editor","viewer")` — the handset calls it with its tenant `tp_` key.
- **VIN canonicalization:** the backend canonicalizes a VIN (`strip().upper()`) and matches
  raw + canonical; the handset sends the canonical form for stability.

## Scope split

- **Increment 2a (this doc):** capture VIN via **manual entry**, validate, resolve+confirm
  (plate), persist, wire `tag_id`. Gate-green with fakes; live resolve/Map are HIL.
- **Increment 2b (done):** OBD-II **Mode 09** VIN auto-read — `PidCodec.decodeVin`
  (multi-frame ISO-TP parser, CAN-scoped) + `Elm327Session.readVin()` + `ObdiiDriver.readVin()`;
  a `VinReader` seam feeds the same bind flow (auto-read → resolve → confirm plate). The
  zero-touch capture tier; the live read is HIL.
- **Increment 2c (done):** VIN **barcode** capture — a **Scan VIN barcode** button reads a
  door-jamb Code 39 / Code 128 / Data Matrix VIN label (reusing the Increment 1b ML Kit
  scanner, generalized to a format-parameterized `BarcodeScanActivity`) and funnels the
  decoded VIN into the same resolve→confirm flow. `VinBarcode.extract` (pure) strips the AIAG
  `I` data-identifier and validates the 17-char shape; the scanner's accept-pattern skips
  non-VIN codes on a busy label. Windshield OCR remains deferred (OQ3).

## What changes (Increment 2a)

### 1. `Vin` — validation + canonicalization (pure)

`Vin.canonical(raw)` = `raw.trim().uppercase()`. `Vin.isValid(canonical)` = **17 chars + the
VIN alphabet (no `I`/`O`/`Q`)** — a **hard** gate. The **ISO-3779 check digit** (position 9)
is computed as an **advisory** (`Vin.checkDigitValid`) only — it is **not** enforced, because
it is mandatory only in North America and many legitimate non-NA VINs carry another character
there; enforcing it would reject real vehicles (rubber-duck finding #2). The UI may *warn* on
a failed check digit but still resolve; the backend resolve + plate confirmation is the
authoritative check.

### 2. `BackendClient.resolveAssetByBinding(value)` (core)

New transport method — `GET {baseUrl}/assets/by-binding?value=<VIN>` with
`Authorization: Bearer <tp_ key>`. Returns a typed outcome:
`Resolved(assetId, displayLabel?)` (`200`), `NotFound` (`404`),
`CredentialError` (`401`/`403`), `Retryable` (`5xx`/`408`/`429`/network), `Terminal`
(other `4xx`). `404` is **not** retryable (a genuinely unknown VIN); `403` maps to
credential, **not** network (rubber-duck contract fix). Thin-transport parse of a minimal
`{id, display_label}` body (mirrors `parseProvisionBody`; the full `AssetResponse` is a
generated model — re-vendoring `openapi.json` to `71ed1e6` is the proper follow-up, noted in
`CONTRACT.md`). MockWebServer-tested.

### 3. `VehicleBindingStore` (app)

Persists the current binding — **canonical VIN**, **plate** (`display_label`), and `assetId`
— in plain `SharedPreferences` (none are secrets; the secret `tp_` key stays in the Keystore).
`current: VehicleBinding?`, `store(...)`, `clear()`.

### 4. `VehicleBindingCoordinator` + `BindState` (app, mirrors `EnrolmentCoordinator`)

`resolve(rawVin)`: canonicalize → `Vin.isValid` (else `Error(INPUT)`, no network) →
`resolveAssetByBinding` → on `Resolved`: **require a non-blank plate** (`display_label`) —
the plate *is* the operator's confirmation signal, so a blank one is an
`Error(NO_PLATE)` telling the operator to ask an admin to set the plate (rubber-duck
finding #4) — else `Confirming(vin, plate, assetId)`. `404` → `Error(NOT_FOUND)`;
`401`/`403` → `Error(CREDENTIAL)`; retryable/terminal → `Error(NETWORK)`. `confirm()`:
persist to `VehicleBindingStore` → `Bound`. `Mutex`-serialized; secret-free errors. Fakes-tested.

> **Map-link warning (`I-WAPN`, closed).** A successful resolve confirms the vehicle's
> **identity** (the plate), but only a `binding_kind='device'` binding (value = the canonical
> VIN) Map-links the handset's `tag_id = VIN` reads. Since `I-WAPN`, `/assets/by-binding`
> returns the **matched `binding_kind`** (via `AssetByBindingResponse`), so the coordinator
> **warns** whenever the resolved binding is **not** `device` (a lookup-only `vin`, or `epc`/
> `tid`). The warning is **advisory, not a block**: the endpoint returns the *earliest-bound*
> binding, so `vin` is ambiguous (vin-only vs. both-with-vin-first) — the operator can still
> confirm, with uncertainty-aware wording. The definitive Map link is still verified in HIL
> (`a7-map-check.py`); a device-first vehicle resolves clean (no warning).

### 5. `BindScreen` (Compose) + routing

VIN field + **Resolve** → shows the returned **plate** + a **Confirm this vehicle** button
(operator visually confirms the plate matches). `MainActivity.AppRoot` gates:
`!enrolled → EnrolRoute` → `!bound → BindRoute` → `ScanRoute`. Reactive on the coordinators'
state (recompose, like enrolment).

### 6. `ScanCoordinator` — stamp the bound VIN as `tag_id`

Inject `boundSubject: () -> Subject?` (reads `VehicleBindingStore`). The bound subject is
captured **once at scan start** (right after acquiring the scan `Mutex`); if it is **absent**
the scan **fails** with `Error(CREDENTIAL)` ("no vehicle bound") and **nothing is enqueued** —
there is **no fallback to the driver placeholder** (rubber-duck finding #3, which also removes
the mid-scan-rebind relabel race). Otherwise the enqueue tail does
`observation.copy(subject = capturedBoundSubject, location = fix)` so the relayed `tag_id` is
the canonical VIN. Keeps the binding concern in the app layer (`:obdii` untouched).

### 7. `AppContainer`

Wire `VehicleBindingStore` + `VehicleBindingCoordinator`; pass `boundSubject` to
`ScanCoordinator`; expose `isBound`. `DEFAULT_VEHICLE_BINDING_VALUE` becomes a fallback only.

## Security

VIN + plate are not secrets (plain prefs OK). The resolve call carries the `tp_` key in the
`Authorization` header only (never logged, never in a `BindState.Error`). Errors are
secret-free and operator-actionable.

## Tests (JVM, fakes — gate)

`VinTest` (valid/invalid alphabet, length, check digit, canonicalization); `resolveAssetByBinding`
MockWebServer (`200` parse, `404`, `401`, `5xx`); `VehicleBindingCoordinatorTest` (resolve→confirm
persists; invalid VIN → INPUT no network; 404 → NOT_FOUND; credential/network errors); a
`ScanCoordinator` test that the bound VIN overrides the subject on enqueue.

## Verification & HIL

Gate: `./gradlew :app:lintDebug :app:testDebugUnitTest :gateway-core:testDebugUnitTest
assembleDebug`. **HIL:** the live `by-binding` resolve + the end-to-end
enrol→bind→scan→Map against a dev tenant (`scripts/e2e/a7-map-check.py`, extended for the VIN
binding). **Increment 2b/2c** (Mode 09, barcode) are staged.

## Review attestations

- **Plan-stage rubber-duck:** **ran → 4 blocking findings + 1 contract fix**, all folded in:
  (1) a successful resolve does **not** prove Map-linkability (kind-agnostic endpoint) →
  documented limitation + a backend follow-up ask (return the matched `binding_kind`);
  (2) the **ISO-3779 check digit is advisory, not enforced** (would reject legitimate
  non-NA VINs) — `Vin.isValid` is length + alphabet only; (3) the `ScanCoordinator` captures
  the bound subject **once at scan start** and **fails** if absent (no placeholder fallback,
  no mid-scan-rebind relabel); (4) a resolved vehicle with a **blank plate** is an
  `Error(NO_PLATE)` (the plate is the confirmation signal); + the contract fix (`403`→credential,
  `404` non-retryable).
- **Diff-stage rubber-duck (code-review):** **ran → no blocking issues.** Independently
  verified the `by-binding` URL-encoding (single-encoded, `Bearer` auth), the status mapping,
  the `AppContainer` initializer order (`backendClient` precedes `vehicleBindingCoordinator` +
  `drainer` — no forward reference), the `Mutex` release on every path, the `confirm()` no-op
  safety, the VIN check-digit (`1HGCM82633A004352` → `3`), secret hygiene, and the Map-link
  honesty.
- **Verification:** `:app:testDebugUnitTest` (**47**, incl. **+7** `Vin` + **+7**
  `VehicleBindingCoordinator` + **+2** `ScanCoordinator` bound/unbound) +
  `:gateway-core:testDebugUnitTest` (**58**, incl. **+7** `resolveAssetByBinding`) green;
  `:app:lintDebug` clean; `assembleDebug` built. `failures=0 errors=0`.
- **HIL (not run here):** the live `by-binding` resolve + the end-to-end
  enrol→bind→scan→Map against a dev tenant (`scripts/e2e/a7-map-check.py`, to be extended for
  the VIN binding + the admin-set `binding_kind='device'` binding). **Increment 2b** (OBD-II
  Mode 09 auto-read) and **2c** (VIN barcode) are staged.
- **current-state:** updated (the handset can now bind a vehicle by VIN; the placeholder is gone).

### Increment 2b — OBD-II Mode 09 VIN auto-read

- **Plan-stage rubber-duck:** **ran → 2 blocking findings**, folded in: (1) the parser
  only handled CAN/ISO-TP — **scoped to CAN**, and legacy J1850/ISO multi-packet responses
  (repeated `49 02 <seq>` headers) cleanly decode to `MALFORMED` → manual-entry fallback
  (documented); (2) blindly taking the first `4902` was unsafe — the decoder now evaluates
  **every `490201` candidate** (validating the NODI byte) and accepts only when **exactly one
  distinct** 17-char alphanumeric VIN emerges (multi-ECU/junk/ambiguous → `MALFORMED`).
- **Diff-stage rubber-duck (code-review):** **ran → no blocking issues.** Traced the parser
  (line-index strip handles `10:`; candidate loop can't miss/loop/overlap; distinct-set →
  `Value` iff size 1), the shared `resolveCore` under the `Mutex` (no re-lock/deadlock, unlock
  on every path), the lazy `driver` capture in `AppContainer` (read at invocation — no
  null-at-init), the `readVin` session path (mirrors `readRpm`; one `exchange` gets all ISO-TP
  frames), and the auto-read UX (a garbage VIN can't silently bind — `Vin.isValid` + backend
  resolve + plate confirmation guard it).
- **Verification:** `:obdii:testDebugUnitTest` (**55**, incl. **+9** `decodeVin` + **+3**
  `readVin` session + **+1** driver) + `:app:testDebugUnitTest` (**50**, incl. **+3**
  coordinator `readVin`) + `:gateway-core:testDebugUnitTest` (**58**) green; `:app:lintDebug`
  clean; `assembleDebug` built. `failures=0 errors=0`.
- **HIL (not run here):** the real dongle Mode 09 read (not all ECUs support `0902`; CAN
  vehicles only — legacy falls back to manual entry). **Increment 2c** (VIN barcode) staged.

### Increment 2c — VIN barcode capture

- **Plan-stage rubber-duck:** **ran → 3 findings**, addressed: (1) a busy door label has
  multiple barcodes — the scanner takes an optional **accept-pattern** (`I?[A-Z0-9]{17}`) and
  **keeps scanning past** non-matching codes; (2) a VIN barcode may carry a leading AIAG `I`
  data-identifier (18-char payload) — pure `VinBarcode.extract` strips it and validates the
  17-char shape; (3) format coverage scoped to **Code 39 / Code 128 / Data Matrix** (the
  standard door-jamb VIN symbologies; QR VIN tags out of scope, documented).
- **Diff-stage rubber-duck (code-review):** **ran → no blocking issues.** Verified the
  enrolment QR flow is **byte-identical** after generalizing `QrScanActivity` →
  `BarcodeScanActivity` (accept-pattern absent ⇒ "first non-null rawValue"), the accept-pattern
  and `VinBarcode.extract` accept the **identical** set (no silent no-op), the ML Kit
  `setBarcodeFormats(first, *rest)` shape + empty-guard, the preserved `ImageProxy`/atomics, and
  the two-launcher/one-Activity ActivityResult safety.
- **Verification:** `:app:testDebugUnitTest` (**56**, incl. **+6** `VinBarcode`) + `:app:lintDebug`
  + `assembleDebug` + **`assembleRelease` (R8)** + **`assembleReleaseAndroidTest`** green
  (the package move didn't disturb the R8 keep-rules or the instrumented smoke test).
  `failures=0 errors=0`.
- **HIL (not run here):** the real camera Code-39 decode. With 2c all three OQ3 capture tiers
  (Mode 09 auto → barcode → manual) are built; windshield OCR stays deferred.

### I-WAPN — warn on lookup-only VIN bindings (+ contract re-vendor)

- **Plan-stage rubber-duck:** **ran → 2 findings**, folded in: (1) the backend resolves
  `by-binding` by **earliest `bound_at`** (not device-first), so `binding_kind=='vin'` is
  **ambiguous** → **warn, don't block**; (2) suppress the warning **only when
  `binding_kind == "device"`** (the sole kind that Map-links a `tag_id = VIN` read — `epc`/
  `tid`/`vin`/`null` all warn) with **uncertainty-aware wording**.
- **Diff-stage rubber-duck (code-review):** **ran → no blocking issues.** Verified the
  null-safe `== "device"` compare, `confirm()` unaffected by the warning, the thin-parse
  safe-default (absent/blank → warn), all 8 `Resolved(...)` construction sites updated, the
  `Confirming.warning = null` default keeping existing assertions valid, and the **additive**
  spec swap.
- **Change:** re-vendored `openapi.json` `06dde2b → 8033d64` (which added `AssetByBindingResponse`
  carrying the matched `binding_kind`; `CONTRACT.md` + `contract.properties` bumped);
  `AssetLookupResult.Resolved` gains `bindingKind` (thin-parsed); `BindState.Confirming` gains an
  optional `warning`; `BindScreen` renders it. Closes ledger **`I-WAPN`**.
- **Verification:** `:gateway-core:testDebugUnitTest` (**58**) + `:app:testDebugUnitTest`
  (**59**, incl. +3 device/vin/epc-tid-null warning tests) + `:app:lintDebug` + `assembleDebug`
  + **`assembleRelease` (R8)** green — the re-vendor didn't disturb the **C-ZVMF** tree-shaking
  (**150/153** generated model files stripped; the used relay models kept; `AssetByBindingResponse`
  stripped-unused). `failures=0 errors=0`.
