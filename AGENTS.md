# AGENTS.md — TagPulse-Mobile

Repo-specific operating contract for any AI coding agent (Copilot CLI, VS Code,
Claude, etc.) working here. This file is the **cross-tool source of truth** —
the sibling of `.github/copilot-instructions.md` (which stays thin and points
here).

> **Not here:** the SDLC (explore → plan → implement → verify → ship → **close-out**,
> the `planner`/`implementer`/`verifier` personas + the `explorer`/`rubber-duck`
> review capabilities, conventional commits, branching) lives in the **global**
> `~/.copilot/copilot-instructions.md` (SoT: `ai-tooling-config`) — this file does
> **not** repeat it. The full model + diagram is in
> [`dev-env-setup` `docs/guides/sdlc.md`](https://github.com/9owlsboston/dev-env-setup/blob/main/docs/guides/sdlc.md).
> Keep this file to what is *unique to this repo*.

## 1. What this repo is

Native **iOS (Swift) + Android (Kotlin)** mobile edge client for TagPulse — a phone as a
second device type (camera scans + GPS/sensor telemetry) alongside fixed RFID readers.
**Category:** mobile app (two native codebases). It speaks the existing TagPulse HTTP API;
it does **not** contain backend code. Design: [`docs/design/mobile-client.md`](docs/design/mobile-client.md).

## 2. Hard rules (repo-specific)

- **Native only, two codebases.** Swift (iOS) + Kotlin (Android). No cross-platform UI
  runtime — native was chosen for install-size / RAM footprint and background fidelity.
- **HTTP-first + offline-first.** Every observation lands in a durable local outbox and
  is drained as **batched HTTPS POSTs**; no persistent MQTT socket in v1.
- **The backend is the contract.** The API client is **generated from TagPulse
  `openapi.json`**; do not hand-write request/response models. Record the backend commit
  SHA each time the client is regenerated (contract-handoff, mirrors TagPulse-UI).
- **Footprint budget is a first-class constraint** — prefer platform-native
  camera/scan/sensor APIs over bundling engines; cap the outbox by size + age; store media
  as file refs, not inline blobs.
- **Secrets live in the platform secure store** (iOS Keychain / Android Keystore) — never
  in source, plists, resource files, or logs. **Never commit `.env`, keys, or tokens.**
- **No UHF-RFID assumptions** — phones read via camera/NFC only (see design §Non-goals).

## 3. Run / test

Scaffolding lands in Phase 0. Once present, the per-platform gate is:

```bash
# Android
./gradlew lintDebug testDebugUnitTest assembleDebug
# iOS (macOS runner)
xcodebuild -scheme TagPulseMobile -destination 'platform=iOS Simulator,name=iPhone 15' test
```

Until Phase 0, this repo is docs + contract only; validate doc changes with `docs-drift`.

## 4. Where to write (docs map)

Pick the destination by the **kind** of content, not the topic:

| Kind of content | Goes in |
|---|---|
| How to run / use this repo | `README.md` |
| Rules for agents working here | `AGENTS.md` (this file) |
| Dated "where we are now" snapshot (current → future → gaps) | `docs/current-state.md` |
| **What commands actually ran / how verified** (action trail) | `docs/history/execution-log.md` |
| Durable working memory (issues/chores/decisions/**routines**/memories) | the **agent ledger** (`repo:<name>` scope; promote to `execution-log.md` when it earns a commit, or to `/kb` when generalizable) |
<!-- Optional: declare cross-repo PROJECT membership so `ledger recall` / `profile`
     union open items + facts across EVERY repo that declares the SAME (lowercase)
     project name. Copy the line below, DROP the `-example` suffix so it goes live,
     put it on its own line, and set your name (the `-example` form is inert): -->
<!-- ledger-project: tagpulse -->
<!-- Uncomment the rows the repo has grown into (profile s+ / grow):
| Architecture, proposals, decisions — the *why* (Diátaxis *explanation* / ADRs) | `docs/design/` |
| How-to workflows and walkthroughs (Diátaxis *how-to* / *tutorial*) | `docs/guides/` |
| Stable technical reference — facts that don't expire (Diátaxis *reference*) | `docs/reference/` |
| Consumer-facing change log (content) | `CHANGELOG.md` |
-->

## 5. Drift-rules

Facts that **must stay true** in this repo. `docs-drift` flags any doc/code hit
against a bad-substring below. Use a **live/actionable pattern** (a command or
import used *as if current*), NOT a bare noun — nouns appear in explanatory prose
and history and would just create noise. Add a row whenever a live path moves/renames.

```drift-rules
# <live-pattern>       →   <why it's wrong / what's correct now>
# (example) python old/path/x.py  →   moved to new/path (invoke via $X); <when/why>
```

## 6. Doc-lifecycle (pre / post — agent-enforced)

- **Pre** (session start): read this file + the relevant plan/design doc; run
  `docs-drift` before changing code.
- **Agent memory (ledger):** at session start **recall** the ledger; **apply a
  relevant routine before planning**; **log a routine after a notable success**
  (a routine is a distilled how-to with the five fields
  `goal:/applies-when:/preconditions:/steps:/pitfalls:`). The exact commands (and
  the OS-specific `python3`/`python` invocation) live in the usage guide — see the
  engine + how-to pointer below.
- **During**: update `docs/history/execution-log.md` *as part of* the change
  (what ran, how verified) — not after; keep any plan status honest. Docs change
  *with* code: if a change alters behavior, config, CLI, API, or deployment, the
  closest doc changes in the same change.
- **Post** (session/PR end): **close out the change** — squash-merge, then ff
  `main` (primary worktree) → remove the worktree → delete the local branch (`-D`,
  since a squash-merged branch isn't an ancestor of `main`) → run `docs-drift` →
  update refs on any move/rename (and the drift-rules above) → note residuals. If
  the change moved the current state, reconcile `docs/current-state.md` and bump
  its snapshot date as the **last step**.
- **Wrap review (current-state rubber-duck):** at the end of any change that
  touched product code or config, read `git diff` + `docs/current-state.md` +
  `README.md` + any touched topic docs, then explicitly report one of
  `current-state: updated / not-affected / needs-human-decision`. A read-only
  judgment pass — not a script.
- **Rubber-duck termination:** rubber-duck loops on **blocking** findings only,
  then terminates by **acceptance** (plan-stage) or the stage-3 `verifier` gate
  (diff-stage); round cap 2–3, open blockers at the cap → Open Questions, never
  dropped. Full rule:
  [`dev-env-setup` `docs/guides/sdlc.md`](https://github.com/9owlsboston/dev-env-setup/blob/main/docs/guides/sdlc.md).
- **Rubber-duck enforcement:** rubber-duck is **required** (not optional) at
  plan-stage and diff-stage for code/config changes — record a **ran-or-waived**
  attestation in the design doc's `## Review attestations` (PR body mirrors the
  diff-stage line). Carve-outs (`noncodefix`/`spike`/`release`) are exempt **unless**
  the change touches deps/CI/IaC/security/behavioral config. Full rule:
  [`dev-env-setup` `docs/guides/sdlc.md`](https://github.com/9owlsboston/dev-env-setup/blob/main/docs/guides/sdlc.md).

Full lifecycle spec: global `~/.copilot/copilot-instructions.md`.

**Agent-memory engine + how-to.** The ledger is
[`ledger.py`](https://github.com/9owlsboston/kb-tools/blob/main/ledger.py) in
`kb-tools` — **not** executable and **not** on PATH. Set `KB` for your shell, then
call the interpreter on the script:
- **POSIX:** `KB=~/ws/kb-tools` → `python3 "$KB/ledger.py" <verb>`
- **PowerShell (Windows):** `$KB = "$env:USERPROFILE\ws\kb-tools"` → `python "$KB\ledger.py" <verb>`

How-to:
[agent-memory-usage.md](https://github.com/9owlsboston/dev-env-setup/blob/main/docs/guides/agent-memory-usage.md).
Design:
[agent-memory-ledger.md](https://github.com/9owlsboston/dev-env-setup/blob/main/docs/design/agent-memory-ledger.md).

## 7. Documentation output style

When writing or editing any doc, follow this output contract so a human can trust
and skim it (full rationale: the ecosystem's *AI documentation output contract*
design doc).

**Structure**

- **Summary first.** Open with a plain-English summary a non-author grasps in one
  read: *what this is, who it's for, when to use it.* For non-trivial topics, lead
  the summary with a high-level **contextual diagram** (source in `docs/diagrams/`,
  Mermaid/drawio/excalidraw) — the diagram *is* the summary. When present, prefer a
  **context-0** view in the hand-drawn Mermaid style (`%%{init: {"look": "handDrawn"}}%%`):
  ~5–9 nodes, external systems as stadium `([ ])` nodes, boundaries as
  ownership/trust zones, and a `<sub>` regen caption when committed.
- **Why before how.** State purpose/value before implementation detail.
- **One doc, one intent.** Route by Diátaxis (tutorial / how-to / reference /
  explanation) and the where-to-write map (§4); don't mix intents. *(Exception:
  `docs/current-state.md` is a deliberate rollup/index.)*
- **Link, don't duplicate.** Point to the source-of-truth doc instead of copying
  it; on conflict, the linked topic doc wins.

**Prose discipline (the anti-machine rules)**

- **Don't restate code.** If the code/signature already says it, link to it —
  don't narrate it.
- **No filler, no narration of the obvious.** Cut "In this section we will…" and
  ceremony.
- **Cite or flag.** Every non-obvious behavioral claim must trace to code, a test,
  an ADR, or a linked source — otherwise mark it **`unverified`**.
- **Mark assumptions explicitly.** Never present an assumption as a fact.
- **Length discipline (soft).** Summaries stay short (a few sentences / ≤ ~8
  lines); depth goes in the detail sections below.
