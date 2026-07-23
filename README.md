# TagPulse-Mobile

Native iOS + Android **edge client** for [TagPulse](https://github.com/9owlsboston/TagPulse) —
a phone becomes a mobile scan-and-sense device (camera + GPS + sensors).

> **Current state:** see `docs/current-state.md` for the dated "where we are now"
> snapshot (current → future → open gaps). This README holds the durable
> orientation; the snapshot holds the moving picture.

## What / why

TagPulse's first device type is fixed UHF **RFID readers**. This repo adds a **second
device type**: a field worker's **phone**. It has no UHF radio, so its "reads" come from
the **camera** (QR / barcode / DataMatrix, plus short-range NFC), and it adds **sensor
telemetry** a fixed reader never had — GPS, barometric altitude, accelerometer, battery.

It ships as a **hybrid app with a mode switch**:

- **Field-Tech mode** — foreground scan / lookup / guided tasks (cycle counts,
  inspections), on-demand GPS + photo.
- **Tracker mode** — background moving beacon: batched GPS + motion telemetry.

Design & rationale: **[`docs/design/mobile-client.md`](docs/design/mobile-client.md)**.

## Run / use

Two native codebases (Swift / Kotlin). Scaffolding lands in Phase 0 — until then this
repo holds the design + contract only.

```text
# iOS      — open the Xcode project (Phase 0)
# Android  — open in Android Studio / ./gradlew assembleDebug (Phase 0)
```

The API client is **generated from TagPulse `openapi.json`** and each generation records
the backend commit SHA it came from (contract-handoff rule — see AGENTS.md §API).

## Layout

- `AGENTS.md` — operating contract for AI agents (rules, run/test, docs map).
- `docs/design/mobile-client.md` — the design (what it is, decisions, endpoints, phases).
- `docs/current-state.md` — dated "where we are now" snapshot (README supplement).
- `docs/history/execution-log.md` — what was executed, when, and how verified.
