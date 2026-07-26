# HIL prep — dongle selection + live test tenant

> **Summary.** Two things must be in hand **before** you run the hardware-in-the-loop
> checklist in [`hil-runbook.md`](hil-runbook.md): a compatible **ELM327 dongle** and a
> **live TagPulse tenant** with a registered test vehicle. This guide covers both — which
> dongle to buy (and why), and a ready-to-run prompt that stands the tenant up on the
> deployed **dev (Azure Container Apps)** backend. Once you have them, go to the runbook.
>
> **Why the choices are constrained:** they're dictated by the mobile client's code — it is
> **BLE-only** (`BluetoothLeScanner` + `connectGatt`; no Bluetooth Classic/SPP, no WiFi),
> speaks the ELM327 AT handshake with auto-protocol (`ATSP0`), reads PIDs
> `010C/010D/0105/012F` + **Mode 09 `0902`** for VIN, and defaults to a **Nordic-UART GATT
> profile** ([`BleUuidConfig.NORDIC_UART_LIKE`](../../obdii/src/main/java/com/tagpulse/gateway/obdii/ble/BleUuidConfig.kt)),
> with a runtime auto-discovery fallback.

## 1. ELM327 dongle selection

**Hard requirement (from the code):** a **Bluetooth Low Energy (BLE 4.0+) ELM327** with
**CAN (ISO 15765-4)** support for Mode 09 VIN auto-read. Classic-Bluetooth and WiFi dongles
**will not connect** — the app uses `BluetoothLeScanner` / `connectGatt` exclusively.

### Primary — cheap reference, zero-config: Vgate iCar Pro BLE 4.0  (~$25–35)
- Genuine BLE. Per vendor/community docs its GATT profile uses the **Nordic-UART UUIDs**
  (`6E400001/2/3-B5A3-F393-E0A9-E50E24DCCA9E`) that match the app's `NORDIC_UART_LIKE`
  default, so it should connect with **no code change** (`unverified` on a physical unit —
  confirm on first pairing). Supports CAN + Mode 09.
- Buy the one explicitly labelled **"BLE 4.0"**. Vgate also sells a Bluetooth 3.0/Classic
  "iCar Pro" — that one is **incompatible**.

### Optional premium — genuine chipset: OBDLink CX  (~$70)
- The **CX** is the BLE model in the OBDLink line (BLE 5.1); genuine chipset, best CAN /
  Mode-09 reliability. It may use a non-Nordic GATT layout — if so, the app's auto-discover
  ([`BleUuidConfig.DISCOVER_ALL`](../../obdii/src/main/java/com/tagpulse/gateway/obdii/ble/BleUuidConfig.kt))
  or a one-line per-dongle UUID override handles it.
- **Not the OBDLink MX+** — that is Bluetooth **Classic**, incompatible with the BLE-only
  transport.

### Avoid
- Anything "WiFi ELM327".
- Anything "Bluetooth 3.0 / Classic / SPP".
- Unbranded "ELM327 v1.5/v2.1" clones that don't clearly state **BLE 4.0+** (most are Classic).

## 2. Live test tenant + vehicle (deployed dev / ACA)

Open a Copilot CLI session **in the backend repo** (`~/ws/TagPulse`) and paste the prompt
below. It provisions (or reuses) the dev-env tenant, hands back the credentials the handset
needs, and registers one vehicle with the **two VIN bindings** the Map join requires.

> **Why ACA helps:** the app requires an `https://` `baseUrl`; the deployed dev env's ACA
> ingress is HTTPS out of the box, so you skip the local TLS-terminator / tunnel that the
> `docker compose` path in the runbook needs.
>
> **No "ELM327 reader type" to provision:** the backend has no reader-type concept.
> `device_type` is a free string (default `rfid_reader`), and in Phase 0 device approval
> isn't required for ingest — the Map link keys on `tag_id` (the VIN), not the device. So
> nothing new is needed backend-side for the ELM327 path.

### Prompt

> **Goal:** Stand up a live test tenant on our **deployed `dev` (ACA)** environment for
> hardware-in-the-loop testing of the TagPulse-Mobile OBD-II client, and register one vehicle
> so a phone-reported VIN will resolve and appear on the Map. Hand me back the exact values
> the handset needs.
>
> **Do this:**
> 1. Provision (or reuse) the demo tenant on the deployed **dev** env — use the existing
>    composer, don't hand-roll SQL: `make demo-tenant-dev` (ENV=dev; runs `seed_demo_tenant.py`
>    in the tools-job). Then retrieve the rotated admin key from Key Vault:
>    `scripts/azd-kv-get.sh dev tagpulse-demo-wm-dc-admin-key`.
> 2. Tell me the tenant's **`baseUrl`** = the deployed dev **HTTPS ingress FQDN** of the API
>    (the app requires `https://`), the **admin/editor `tp_…` API key**, the tenant **slug**,
>    and its **provisioning key** (the `X-Provisioning-Key` value the handset's enrol flow
>    needs — surface the current one or rotate it and give me the plaintext).
> 3. Register a test vehicle against that tenant using the `tp_` key. Pick a real 17-char VIN
>    from a 2008+ CAN vehicle I'll be testing with (ask me if you need it; call it `<VIN>`,
>    upper-cased canonical form) and a licence plate `<PLATE>`:
>    - `POST /categories {name, category_type:"object"}` → `category_id`
>    - `POST /assets {name, category_id, display_label:"<PLATE>"}` → `asset_id`
>    - `POST /assets/{asset_id}/bindings {binding_value:"<VIN>", binding_kind:"device"}`
>      ← the Map join (`tr.tag_id = binding_value`)
>    - `POST /assets/{asset_id}/bindings {binding_value:"<VIN>", binding_kind:"vin"}`
>      ← the `GET /assets/by-binding` lookup the handset resolves for the plate
>    ⚠️ **Both bindings must use the same upper-case VIN.** Without the **`device`** binding,
>    bind "succeeds" but nothing Map-links.
> 4. Verify the backend half end-to-end before I touch the phone: run the mobile repo's
>    fixture — `python3 ~/ws/TagPulse-Mobile/scripts/e2e/a7-map-check.py --base-url <baseUrl>
>    --api-key <tp_ key>` — and confirm it prints `A7 PASS`.
>
> **Output:** a single block with `baseUrl`, `tp_ key`, `provisioning key`, `slug`, `asset_id`,
> canonical `VIN`, `PLATE`, and the `A7 PASS` confirmation. Redact nothing to me; I'll move the
> secrets into the phone's Keystore via the enrol screen.

## Traceability

| Fact | Source |
|---|---|
| BLE-only transport (no Classic/WiFi) | [`AndroidBleTransport.kt`](../../obdii/src/main/java/com/tagpulse/gateway/obdii/ble/AndroidBleTransport.kt) (`BluetoothLeScanner`, `connectGatt`) |
| Nordic-UART default profile + auto-discover | [`BleUuidConfig.kt`](../../obdii/src/main/java/com/tagpulse/gateway/obdii/ble/BleUuidConfig.kt) (`NORDIC_UART_LIKE`, `DISCOVER_ALL`) |
| ELM327 handshake `ATSP0`; PIDs `010C/010D/0105/012F`; VIN `0902` | [`Elm327Session.kt`](../../obdii/src/main/java/com/tagpulse/gateway/obdii/elm/Elm327Session.kt) |
| Dual VIN bindings (`device` = Map join, `vin` = lookup) | [`hil-runbook.md`](hil-runbook.md) Part A.2; [`a7-map-check.py`](../../scripts/e2e/a7-map-check.py) |
| No backend reader-type; `device_type` free string default `rfid_reader` | `~/ws/TagPulse` `src/tagpulse/models/schemas.py`, `database.py` |
| Deployed-dev tenant provisioning tooling | `~/ws/TagPulse` `Makefile` (`demo-tenant-dev`), `scripts/{azd-job,azd-kv-get,seed_demo_tenant,smoke_setup}.*` |
| Vgate BLE Nordic-UART UUIDs; OBDLink CX = BLE / MX+ = Classic | Vendor/community docs |

## Related

- **The run itself:** [`hil-runbook.md`](hil-runbook.md) (enrol A1 → bind A5 → scan A6 → Map A7).
- **Backend E2E fixture:** [`scripts/e2e/a7-map-check.py`](../../scripts/e2e/a7-map-check.py).
- **Design:** [`obdii-mve-plan.md`](../design/obdii-mve-plan.md), [`vehicle-bind-flow.md`](../design/vehicle-bind-flow.md).
