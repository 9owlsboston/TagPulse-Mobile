# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **M0 (Scaffold)** of the OBD-II MVE: Android Gradle project (Kotlin DSL, wrapper
  pinned to Gradle 8.11.1, version catalog) with `:app`, `:gateway-core`, `:obdii`
  modules; the `GatewayDriver` seam (`discover → read → normalize`) + normalized
  `Observation` model; and the backend API client **generated** from the vendored
  TagPulse `openapi.json` (backend SHA `06dde2b`, spec `0.1.0`). Interfaces/models
  only — no BLE/PID/networking behavior. Gate green
  (`./gradlew lintDebug testDebugUnitTest assembleDebug`). (#4)
- Repo bootstrap (profile `xs`) and initial `docs/design/mobile-client.md` — native
  iOS+Android edge client design; decisions: separate repo, hybrid mode-switch, native
  Swift+Kotlin, HTTP-first.
### Changed
### Fixed
### Removed
### Security

<!--
HOW TO USE THIS FILE
====================
Every PR (except `noncodefix/*`, `spike/*`, `release/*` branches) must add at
least one row under `## [Unreleased]`. The `.githooks/pre-push` hook checks
this locally; CI re-checks it on the server.

Pick the right section:
  Added      — new features
  Changed    — changes in existing functionality
  Deprecated — soon-to-be-removed features
  Removed    — now-removed features
  Fixed      — bug fixes
  Security   — vulnerabilities

Format:  `- <one-line summary> (#<pr-number>)`
Example: `- JWT refresh tokens with 7-day TTL (#142)`

Merge conflicts on this file are handled by `.gitattributes`
(`CHANGELOG.md merge=union`) — parallel PRs' entries concatenate automatically.
-->
