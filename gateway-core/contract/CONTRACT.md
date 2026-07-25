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
hard rule. No HTTP-client runtime (okhttp/retrofit) is generated — **models
only**.

Serialization: generated models carry **Jackson annotations** (kotlinx.serialization
was tried first but its compiler plugin ICEs on the generated `Map<String, Any>`
fields — see the execution-log). At **M0** the only added dependency was
`jackson-annotations` (annotations only, no runtime). The Jackson **runtime**
(`jackson-databind` + `jackson-module-kotlin` — the kotlin module is required for
Jackson to (de)serialize Kotlin data classes) was originally deferred to M4 (the
HTTP client), but landed **early at M3**: the durable outbox (plan §7) genuinely
needs JSON at rest — it serializes `Observation.payload` / `location` to its
`payload_json` / `location_json` columns and reconstructs them on read. The
generated HTTP-client wiring (endpoint bindings, auth) is still **M4**; M3 only
consumes the same Jackson runtime for the outbox codec (`OutboxJson`).

> **Footprint note (M3).** `jackson-databind` + `jackson-module-kotlin` (~2 MB of
> classes pre-shrink) were pulled forward one milestone. This is not extra
> footprint versus the plan — M4 needs them regardless — only earlier, and it
> avoids a leaner-but-throwaway serializer for the outbox. R8 shrink applies the
> same as for the generated models (ledger `C-ZVMF`).

> **Footprint — R8 tree-shaking is now load-bearing (`C-ZVMF`, verified).** All 145
> component schemas are generated but only three (`TagReadCreate`/`Location`/`Identity`)
> are used by the MVE. R8 now strips the unused superset: `:app` release enables
> `isMinifyEnabled = true` (+ `isShrinkResources`), with reflective-Jackson keep-rules
> shipped as `:gateway-core` **consumer rules** (`consumer-rules.pro`). Build-verified —
> `:app:assembleRelease` R8 `usage.txt` shows **145/148 generated model files removed**
> and the used models + `GeoLocation` (+ their members) retained; release APK ~2.3 MB.
> `-keepclassmembers` (not `-keep`) is deliberate so unused model *classes* are still
> shrunk. Runtime Jackson-post-R8 correctness is covered by `JacksonR8ContractTest`
> (JVM, unminified contract) + `JacksonR8SmokeTest` (instrumented, `testBuildType =
> "release"` → minified variant; the emulator/CI run is the remaining gate). Debug
> builds don't run R8 and are unaffected.

The HTTP client / API surface (endpoint bindings, auth) is deferred to later
milestones (plan §8, M4).

## M4 transport decision — thin OkHttp wrapper over the generated *models*

M4 (`:gateway-core` `relay` package) needs a real northbound HTTP client for
`POST /tag-reads/batch` and `POST /devices/provision`. Two options:

1. **Generate the full OpenAPI kotlin *api-client*** (endpoint bindings + an
   `ApiClient` + auth scaffolding), or
2. **Hand-write a thin transport** (`OkHttpBackendClient`) that serializes the
   **generated models** and calls the two endpoints directly.

**Decision: option 2** — a thin OkHttp transport over the generated models.

- The AGENTS §2 hard rule is about **models**, not transport: "the API client is
  generated from `openapi.json`; **do not hand-write request/response models**."
  M4 uses the generated `TagReadCreate` / `Location` **verbatim** (Jackson-annotated)
  and never hand-writes a wire model — the rule is honored.
- Generating the full api-client is a poor fit here: the selective generation this
  spec needs is broken on **OpenAPI 3.1** (same reason the model gen emits the full
  set — see above), and a full api-client pulls its own HTTP + serialization wiring
  and multi-endpoint auth scaffolding far beyond the **two** endpoints the MVE calls
  — against the footprint budget.
- The thin transport is ~1 OkHttp `Request` per endpoint; it reads `baseUrl`/`apiKey`
  from the `CredentialStore` per call and maps status codes to typed outcomes. Auth is
  the tenant user API key as `Authorization: Bearer tp_…` (plan §5, Fix 1) — **not**
  the `tpd_` device token, which the backend never verifies on ingest (§5 🚩).

### M4 runtime dependencies added to `:gateway-core`

- **`com.squareup.okhttp3:okhttp`** — the northbound HTTPS transport
  (`OkHttpBackendClient`). `okhttp3:mockwebserver` is **test-only** (drives the client
  against a loopback server — no network, no device).
- **`androidx.security:security-crypto`** — `EncryptedSharedPreferences` for the
  Keystore-backed `CredentialStore` (ingest API key + `device_id` encrypted at rest,
  never in source/resources/logs; AGENTS §2). The real `AndroidKeyStore` path is a
  **HIL** check (Robolectric doesn't implement it faithfully); the unit gate drives a
  `FakeCredentialStore`.
- Jackson (already present since M3) serializes the generated models — no new
  serialization dep.

### Increment 2a — VIN→asset lookup (`GET /assets/by-binding`)

The vehicle VIN-bind (ledger `C-RYH7` Increment 2a) adds a **third** thin-transport
call: `GET /assets/by-binding?value=<canonical VIN>` with `Authorization: Bearer <tp_ key>`,
mapping the result to a typed `AssetLookupResult` (`docs/design/vehicle-bind-flow.md`). This
consumes backend endpoint + field added by **`I-P923`** (TagPulse migration `062`, backend
SHA **`71ed1e6`** — the new `assets.display_label` (plate) + `binding_kind='vin'`). Consistent
with the M4 transport decision, the response is parsed minimally (`{id, display_label}`) rather
than via the generated `AssetResponse`; **re-vendoring `openapi.json` to `71ed1e6`** to pick up
the generated `AssetResponse.display_label` + the endpoint is the proper follow-up (not blocking
2a). The provenance SHA above (`06dde2b…`) still reflects the **generated models** in use.

