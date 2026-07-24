# Contract handoff — vendored TagPulse API spec

This directory vendors the TagPulse backend OpenAPI spec that the `:gateway-core`
API client is **generated** from. Per `AGENTS.md` §2 (hard rule), the client is
generated from `openapi.json` — models are **never hand-written** — and the
backend commit SHA is recorded on every regeneration (contract handoff, mirrors
TagPulse-UI).

## Provenance

| Field | Value |
|---|---|
| Source repo | `9owlsboston/TagPulse` (`~/ws/TagPulse`) |
| Backend commit SHA | `06dde2b9b875f6f66ee817a2760439f7d3f400b0` |
| Spec `info.version` | `0.1.0` |
| Vendored file | [`openapi.json`](openapi.json) |
| Vendored on | 2026-07-24 |
| Milestone | M0 (Scaffold) |

The same SHA is exposed to build tooling in
[`contract.properties`](contract.properties) so it is machine-readable.

## How to regenerate

Generation is wired into the Gradle build via the `org.openapi.generator`
plugin (task `openApiGenerate` in `gateway-core/build.gradle.kts`). Kotlin models
are generated into `gateway-core/build/generated/openapi/` and compiled as part
of the module — there is nothing to check in beyond this spec.

To re-vendor against a newer backend:

1. Copy the new spec: `cp ~/ws/TagPulse/openapi.json gateway-core/contract/openapi.json`
2. Update the SHA/version in this file **and** in `contract.properties`.
3. Rebuild: `./gradlew :gateway-core:openApiGenerate assembleDebug`.

Do **not** reach across to `~/ws/TagPulse` at build time — the spec is vendored
on purpose so builds are hermetic.

## M0 generation scope

M0 generates the **full model set** from the vendored spec (all component
schemas → Kotlin data classes under `com.tagpulse.gateway.core.api.model`). This
is a **superset** of what the MVE ingest path needs — `TagReadCreate`,
`Location`, `Identity` (the `POST /tag-reads/batch` body).

Why the full set rather than a hand-picked subset: the generator's selective
`models=<names>` filter is **broken against this OpenAPI 3.1 spec** — it silently
emits zero files (3.1 support is flagged "in development" upstream). Generating
all models is the robust path that still honors the "generated, not hand-written"
hard rule. Footprint is held by R8 tree-shaking (unused models are stripped from
release builds); no HTTP-client runtime (okhttp/retrofit) is generated — models
only, so the only added runtime dependency is `kotlinx-serialization-json`.

The HTTP client / API surface (endpoint bindings, auth) is deferred to later
milestones (plan §8, M4).
