# Current state — TagPulse-Mobile

> **Snapshot:** 2026-07-23. The single, always-current answer to *"where is this
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

**Where it stands today:** repo just **bootstrapped** (profile `xs`) with the **design
drafted** and the four foundational decisions locked (separate repo · hybrid · native
Swift+Kotlin · HTTP-first). **No app code yet** — Phase 0 spike is the next step, and it
needs zero backend changes.

## Diagram

See the context diagram in the design doc:
[`docs/design/mobile-client.md`](design/mobile-client.md#context) (Mermaid, hand-drawn).
A dedicated `docs/diagrams/` source will be added when the app architecture warrants it.

## Current state

One line per area, each linking to the doc that owns the detail.

- **Design** — drafted; decisions D1–D4 locked, endpoints mapped, phased plan set. See
  [`docs/design/mobile-client.md`](design/mobile-client.md).
- **App code** — none yet. Phase 0 spike (enrol → camera scan → `POST /tag-reads` → Map)
  is the next deliverable.
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
