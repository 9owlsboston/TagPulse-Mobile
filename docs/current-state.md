# Current state — TagPulse-Mobile

> **Snapshot:** 2026-07-26. The single, always-current answer to *"where is this
> project right now?"* — a **supplement to the README**, not a design-doc rollup.
> Lead with a human summary (a short plain-English paragraph a newcomer reads
> top-to-bottom *without* opening links, then a diagram); keep the rest thin —
> one line per area + **links** to the authoritative topic docs. On any conflict,
> the linked topic doc wins. Update this doc and bump the snapshot date as the
> **last step** of any change that moves the current state.

## Summary

TagPulse-Mobile is the **native iOS + Android edge client** for TagPulse: a field worker's
phone acting as a second device type (camera scans + GPS/sensor telemetry) alongside fixed
RFID readers. It's a **hybrid app** with a mode switch — Field-Tech (foreground scan /
inspect) and Tracker (background moving beacon) — that is **offline-first** and
**HTTP-first**, POSTing batched observations to the existing TagPulse API.

**Where it stands today:** design drafted + the four foundational decisions locked (separate
repo · hybrid · native Swift+Kotlin · HTTP-first), the **OBDII-on-demand MVE plan** agreed,
and the **Phase-0 MVE is code-complete — M0–M5 all merged**. The Android app runs the full
green-zone slice: tap **"Scan vehicle"** → BLE-connect an ELM327 dongle → read a 4-PID
snapshot → `normalize()` → attach a GPS fix → durable Room outbox → drain as a batched
`POST /tag-reads/batch` (Keystore-backed tenant-key auth, at-least-once), surfaced on a
Compose screen. The handset is **enrolled** against a tenant, then **bound to one vehicle by
VIN** — the reads carry the canonical VIN as `tag_id`, and the vehicle's plate
(`display_label`) is confirmed at bind (ledger `C-RYH7` Increments 1 + 2a) — the scan screen
is gated behind enrolment + a bound vehicle. Fully unit-tested (BLE fake + Robolectric + OkHttp
MockWebServer + coordinator/enrolment/bind tests). **Remaining before it's demonstrably live:
hardware-in-the-loop (HIL)** — a real BLE dongle, real GPS, real Keystore creds, the live
VIN resolve, and the A6/A7 enrol→bind→scan→Map against a running dev tenant (a runnable,
backend-validated E2E script exists at `scripts/e2e/a7-map-check.py`). The OBD-II **Mode 09
VIN auto-read** (Increment 2b) and **VIN barcode** capture (Increment 2c) are built — all three
VIN capture tiers (auto → barcode → manual); the live camera/dongle reads are HIL.

## Diagram

See the context diagram in the design doc:
[`docs/design/mobile-client.md`](design/mobile-client.md#context) (Mermaid, hand-drawn).
A dedicated `docs/diagrams/` source will be added when the app architecture warrants it.

## Current state

One line per area, each linking to the doc that owns the detail.

- **Design** — drafted; decisions D1–D4 locked, endpoints mapped, phased plan set. See
  [`docs/design/mobile-client.md`](design/mobile-client.md).
- **App code** — **Phase-0 MVE code-complete: M0–M5 merged.** Android Gradle project (`:app`,
  `:gateway-core`, `:obdii`), the `GatewayDriver` seam + `Observation` model, a **generated**
  backend client (openapi.json SHA `8033d64`), an `obdii` driver (BLE → 4-PID snapshot →
  `PidCodec` → `normalize()`), a `:gateway-core` **durable Room outbox** + **relay**
  (Keystore `CredentialStore`, OkHttp `BackendClient`, `Observation→TagReadCreate` mapping,
  `Drainer` → `POST /tag-reads/batch`, at-least-once), and the `:app` **"Scan vehicle" Compose
  UI + GPS + composition root** wiring it end-to-end, plus the **handset↔tenant enrolment
  flow** (`app/enrol` `EnrolmentCoordinator` + `EnrolScreen`, Keystore-persisted `baseUrl`;
  ledger `C-RYH7` Increment 1) gating the scan screen, an enrolment **QR scanner**
  (Increment 1b — CameraX + ML Kit bundled; camera decode HIL), and a **vehicle VIN-bind**
  flow (`app/bind` `Vin` + `VehicleBindingCoordinator` + `BindScreen`; resolve VIN →
  `GET /assets/by-binding` → confirm plate → reads carry the canonical VIN as `tag_id`;
  ledger `C-RYH7` Increment 2a, backend `I-P923`). MVE acceptance: **A1–A5 code-complete**
  (real creds/backend HIL), **A6/A7 HIL** (+ runnable `scripts/e2e/a7-map-check.py`),
  **A8 gate green**. Next: real-device **HIL** end-to-end (ledger `C-RYH7` — enrol → bind →
  scan → Map on a handset; **prep** — dongle + live tenant:
  [`docs/guides/hil-prep.md`](guides/hil-prep.md); **run**:
  [`docs/guides/hil-runbook.md`](guides/hil-runbook.md));
  then the Phase-1+ roadmap. See
  [`docs/design/obdii-mve-plan.md`](design/obdii-mve-plan.md),
  [`docs/design/enrolment-flow.md`](design/enrolment-flow.md), and
  [`docs/design/vehicle-bind-flow.md`](design/vehicle-bind-flow.md).
- **Backend contract** — consumed as-is from TagPulse `openapi.json`; **zero backend change**
  needed for the Phase-0 MVE (backend asks `I-75YC`/`I-9HQA`/`I-K6D1` are post-MVE).

## Future state / vision

- A single native app both field techs (scan/inspect) and asset trackers (background GPS)
  use, feeding the existing TagPulse Map, Tag Reads, and geofencing surfaces.
- Phased to Phase 3 extensions: optional MQTT, mTLS / hardware-backed keys, BLE UHF-RFID
  sled.

## Open gaps

- **Q-A device principal/role** for `POST /telemetry/readings/ingest` (admin/editor-gated;
  a device is neither) — **backend decision** needed before Phase 1 telemetry.
- **Q-B** asset binding for Tracker-mode `external-position`.
- **Q-C** first platform for the Phase 0 spike (iOS, Android, or both).
- **Q-D** background tracking policy (battery vs fidelity).

See [`docs/design/mobile-client.md`](design/mobile-client.md#open-questions).

**Forward-looking (not v1 scope):** an exploration reframes the phone as a mobile edge
**gateway** (BLE/sensor aggregation + cellular backhaul). A **2026-07-24 design discussion**
walked its open questions (G-1…G-6, Q-A/Q-B) to decisions grounded in the backend code:
the gateway is **contract-compatible today** for reads + mixed-subject telemetry batches,
with **two hard backend asks** remaining — a **scoped gateway principal** for telemetry
ingest (ledger `I-75YC`, refines `I-F0PR`) and **generalizing external-position to all
subject kinds** (ledger `I-9HQA`). A candidate **MVE** is named — *OBDII-on-demand*
(BLE dongle → read PIDs → relay via `tag-reads` → Map; Android-first, green-zone, ledger
`D-M8XF`). See
[`docs/design/edge-gateway-exploration.md`](design/edge-gateway-exploration.md#mve-prospect--obdii-on-demand-candidate-first-gateway-modality).
