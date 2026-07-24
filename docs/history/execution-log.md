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
