# HIL runbook — enrol → bind → scan → Map (C-RYH7)

> **Summary.** The OBD-II MVE is **code-complete and gate-green** but its device paths (BLE,
> GPS, Keystore, camera, live backend) are **hardware-in-the-loop (HIL)** — they cannot run in
> the offline unit gate. This is the step-by-step checklist to validate the whole slice on a
> **real Android handset against a live TagPulse tenant**: enrol the handset, bind a vehicle by
> VIN, scan, and confirm the vehicle appears on the Map. It maps to plan acceptance criteria
> **A1–A8** (`docs/design/obdii-mve-plan.md` §1).

This runbook covers what the automated unit gate **cannot**. The backend half can be
auto-checked with [`scripts/e2e/a7-map-check.py`](../../scripts/e2e/a7-map-check.py); this doc
adds the **device half** (build → install → enrol → bind → scan) and the **admin setup** that
makes the Map link resolve.

## Prerequisites

| Need | Detail |
|---|---|
| **Android handset** (physical) | API 26+. A physical device is required — an emulator can't pair the **BLE ELM327 dongle** and has limited camera. |
| **ELM327 BLE dongle** in a **CAN vehicle** | 2008+ light vehicle (Mode 09 VIN auto-read is CAN-scoped; older vehicles fall back to barcode/manual). |
| **Dev host** | Android SDK + `adb`; this repo checked out. (iOS is out of scope — no `ios/` codebase yet.) |
| **Live TagPulse backend** | `docker compose up` in `~/ws/TagPulse` → API on `http://<host>:8000`. |
| **Tenant credentials** (out-of-band) | a tenant user **`tp_{slug}_…` API key** with **admin/editor** role (for setup + ingest) and a tenant **provisioning key**. |

> **Network:** the handset must reach the backend host. Use the host's LAN IP (not
> `localhost`) as the `baseUrl`, and ensure the phone and backend are on the same network /
> the port is reachable. HTTPS is required by the app (`baseUrl` must be `https://…`) — front
> the dev backend with a TLS terminator or a tunnel (e.g. an `https` reverse proxy) for the
> handset, even though `a7-map-check.py` can hit plain `http://localhost` from the host.

## Part A — Backend + tenant setup (admin)

1. **Start the backend:** in `~/ws/TagPulse`, `docker compose up` (serves the API on `:8000`).
2. **Register the vehicle asset + its bindings.** This is the **critical prerequisite** — the
   Map link only resolves if the vehicle carries a `binding_kind='device'` binding whose value
   is the **VIN** the handset reports (I-P923). Using the tenant `tp_` key:
   - `POST /categories` `{name, category_type:"object"}` → `category_id`
   - `POST /assets` `{name, category_id, display_label:"<PLATE>"}` → `asset_id`
     *(`display_label` = the licence plate — the handset shows it for operator confirmation.)*
   - `POST /assets/{asset_id}/bindings` `{binding_value:"<VIN>", binding_kind:"device"}`
     → **the Map join** (`tr.tag_id = binding_value`).
   - `POST /assets/{asset_id}/bindings` `{binding_value:"<VIN>", binding_kind:"vin"}`
     → **the `GET /assets/by-binding` lookup handle** the handset resolves for the plate.
   > ⚠️ Both bindings use the **same canonical (upper-case) VIN**. Without the **`device`**
   > binding the reads will **not** Map-link even though the bind "succeeds" (the handset can't
   > now **warns** at bind-confirm when the resolved binding isn't `device` — ledger **I-WAPN**,
> closed). `a7-map-check.py` seeds the `device` binding for you
   > if you only want to test the backend half.
3. *(Optional)* **Make an enrolment QR** for Part C: encode the URI
   `tagpulse://enrol?base=<url-encoded https baseUrl>&pkey=<provisioning key>` as a QR image.
   *(The QR carries the base URL + provisioning key only — never the `tp_` key; OQ2.)*

## Part B — Build + install the app

```bash
# From the repo root, on the dev host with the handset connected (adb devices):
./gradlew :app:installDebug        # or: assembleDebug, then adb install the APK
```
Launch **TagPulse**. Grant **Camera**, **Location (fine)**, and **Bluetooth scan/connect**
when prompted (requested at point of use).

## Part C — Enrol the handset (A1)

1. The app opens on the **Enrol** screen (it's gated until enrolled).
2. **Scan the enrolment QR** (Part A.3) — or enter the **Backend URL** (`https://…`) and
   **Provisioning key** manually.
3. **Paste the tenant `tp_` ingest key** into the masked field and a **device name**.
4. Tap **Provision** → expect **Enrolled** (a `device_id` + `pending` status).
5. *(Optional, completeness)* an admin approves the device
   (`POST /device-registry/{device_id}/approve`). **Not required for ingest** in Phase 0 — the
   handset authenticates with the tenant `tp_` key, and the Map link keys on `tag_id`, not
   `device_id`.

✅ **A1 pass:** `Enrolled`; the `device_id` + `tp_` key + `baseUrl` are in the Keystore (a
process restart keeps you past the Enrol screen).

## Part D — Bind the vehicle by VIN (A5)

On the **Bind** screen, capture the VIN by **any** tier (all three are built):

- **Read VIN from vehicle** — dongle connected → OBD-II Mode 09 auto-read (zero-touch; CAN only).
- **Scan VIN barcode** — the door-jamb Code 39 / Code 128 / Data Matrix VIN label.
- **Type the VIN** — manual entry (always available; fallback for legacy vehicles / unreadable labels).

Then: the app resolves the VIN (`GET /assets/by-binding`), shows the **plate**, and you tap
**Confirm this vehicle (<PLATE>)**.

✅ **A5 pass:** `Bound`; the canonical VIN + plate are persisted; the scan screen is now reachable.

## Part E — Scan + relay (A6)

1. On the **Scan** screen, tap **Scan vehicle**.
2. The app: discovers the dongle → ELM327 handshake → reads the 4-PID snapshot → attaches a
   **GPS fix** → durable outbox → **`POST /tag-reads/batch`**.
3. Expect **Done** with `sent=1` and *GPS fix: attached*.

✅ **A6 pass:** the read relays `201 {ingested:1, rejected:0}` (`sensor_data` PID snapshot +
`location{source:"gps"}`), reported with the **VIN as `tag_id`**.

## Part F — Verify on the Map (A7)

**Auto-check the backend half** (from the dev host):
```bash
python3 scripts/e2e/a7-map-check.py --base-url http://localhost:8000 --api-key tp_<slug>_XXXX
```
→ `A7 PASS`. This seeds its own `device`-binding + relays a synthetic read; use it to validate
the backend independently of the handset.

**Verify the real handset read:** `GET /assets/current-locations` — the vehicle asset appears
at the read's GPS location with `kind='geo'`. (This resolves via the **`device`** binding =
VIN set in Part A.2.)

✅ **A7 pass:** the scanned vehicle is on the Map at the read's location.

## Acceptance matrix

| # | Criterion | Where | Auto |
|---|---|---|---|
| A1 | Enrol (provision → Keystore creds) | Part C | — (HIL) |
| A2–A4 | BLE connect / PID decode / durable outbox restart | Part E + unit gate | ✅ unit |
| A5 | Vehicle bound by VIN (+ plate confirm) | Part D | — (HIL) |
| A6 | Read relays `201` with GPS | Part E | — (HIL) |
| A7 | Vehicle on the Map at the read location | Part F | `a7-map-check.py` |
| A8 | Offline unit gate green | `./gradlew lintDebug testDebugUnitTest assembleDebug` | ✅ CI |

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Enrol / scan returns a **credential error** | Bad/expired `tp_` key, or `baseUrl` wrong → re-enrol (the app surfaces this, `C-5EHY`). |
| **Read VIN from vehicle** fails | Vehicle doesn't support Mode 09 `0902` (legacy/non-CAN) → use **Scan barcode** or **type** the VIN. |
| VIN **resolves** but the read **isn't on the Map** | The vehicle is missing the **`binding_kind='device'`** binding = VIN (only the `vin` lookup binding exists). Add it (Part A.2). The bind screen **warns** when the resolved binding isn't `device` (I-WAPN) — heed the warning. |
| **No vehicle bound** error on scan | Bind a vehicle first (Part D) — scanning requires a bound VIN (no placeholder). |
| Relay parks rows **FAILED** | Terminal `4xx` (bad payload) or exhausted retries; `429`/`408`/`5xx` are retried/deferred. Check backend logs. |
| Handset can't reach the backend | Use the host **LAN IP** + `https://`; confirm same network + reachable port. |

## Related

- Design: [`obdii-mve-plan.md`](../design/obdii-mve-plan.md) (§1 acceptance),
  [`enrolment-flow.md`](../design/enrolment-flow.md), [`vehicle-bind-flow.md`](../design/vehicle-bind-flow.md).
- Backend E2E: [`scripts/e2e/a7-map-check.py`](../../scripts/e2e/a7-map-check.py) + its
  [`README`](../../scripts/e2e/README.md).
- **I-WAPN (closed):** `GET /assets/by-binding` returns the matched `binding_kind`, so the
  bind screen warns when a VIN resolves via a non-`device` (lookup-only) binding.
