# Current state — TagPulse-Mobile

> **Snapshot:** 2026-07-24. The single, always-current answer to *"where is this
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
and Phase-0 nearly complete — **M0 (scaffold) + M1 (BLE + RPM) + M2 (4-PID snapshot +
normalize) + M3 (durable Room outbox) + M4 (enrolment + relay) merged**. The `:obdii` driver
reads a 4-PID snapshot and `normalize()`s it into an `Observation`; `:gateway-core` persists
each to a file-backed Room outbox and now **drains it as a batched `POST /tag-reads/batch`**
(Keystore-backed tenant-key auth, at-least-once, retry/backoff/FAILED). Unit-tested via a BLE
fake + Robolectric + OkHttp MockWebServer; real BLE + the Keystore are manual HIL checks.
**M5 (wire the "Scan vehicle" UI + GPS end-to-end → Map confirmation)** is the last milestone.

## Diagram

See the context diagram in the design doc:
[`docs/design/mobile-client.md`](design/mobile-client.md#context) (Mermaid, hand-drawn).
A dedicated `docs/diagrams/` source will be added when the app architecture warrants it.

## Current state

One line per area, each linking to the doc that owns the detail.

- **Design** — drafted; decisions D1–D4 locked, endpoints mapped, phased plan set. See
  [`docs/design/mobile-client.md`](design/mobile-client.md).
- **App code** — **Phase-0 M0 + M1 + M2 + M3 + M4 merged**: Android Gradle project (`:app`,
  `:gateway-core`, `:obdii`), the `GatewayDriver` seam + `Observation` model, a **generated**
  backend client (openapi.json SHA `06dde2b`), an `obdii` driver (BLE → 4-PID snapshot →
  `PidCodec` → `normalize()`), a `:gateway-core` **durable Room outbox**, and the **relay**:
  a Keystore-backed `CredentialStore`, an OkHttp `BackendClient`, `Observation→TagReadCreate`
  mapping, and a `Drainer` that drains the outbox → `POST /tag-reads/batch` (at-least-once,
  retry/backoff, `PENDING→SENT`/`FAILED`). Next: **M5** (wire the "Scan vehicle" UI + GPS
  end-to-end + the A7 Map E2E) per
  [`docs/design/obdii-mve-plan.md`](design/obdii-mve-plan.md#8-milestones--phased-steps).
- **Backend contract** — consumed as-is from TagPulse `openapi.json`; zero backend change
  needed for Phase 0.

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
