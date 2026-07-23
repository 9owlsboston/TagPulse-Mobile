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
