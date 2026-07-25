# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **M2 (Full snapshot + normalize)** of the OBD-II MVE: `PidCodec` now decodes all
  four MVE PIDs as pure functions — RPM (`010C`), speed (`010D`, `A` km/h), coolant
  temp (`0105`, `A−40` °C, may be negative) and fuel level (`012F`, `A*100/255` %,
  one-decimal float) — via a shared frame parser; each returns a typed
  `PidReading<T>` value or a clean `Failure` (never throws) on
  `NO DATA`/`?`/`UNABLE TO CONNECT`/malformed/wrong-header, spaces-on and spaces-off
  framing alike. `Elm327Session.readSnapshot()` requests the four PIDs in sequence,
  reassembles each to the `>` prompt, and assembles an `ObdSnapshot` — **per-PID
  failure is graceful** (that field goes null; the other PIDs still land; the
  session settles back on `Ready`). New `ObdSnapshot` model renders the plan §4
  `sensor_data` shape (`modality`/`protocol`/`captured_at`/`pids`/optional
  `dongle`/debug-gated `raw`) and round-trips across the string-typed seam.
  `ObdiiDriver.read()` now produces a `DriverReading` carrying the snapshot and
  `normalize()` maps it to an `Observation` — `subject`/`source` from an injected
  `ObdiiConfig`, `timestamp` = capture time, `location` stays null (GPS is M4/M5).
  Gate green (`./gradlew lintDebug testDebugUnitTest assembleDebug`).
- **M1 (BLE connect + one PID)** of the OBD-II MVE: a testable `BleTransport` seam
  (interface + real `AndroidBleTransport` over `android.bluetooth` [HIL-only] +
  scriptable in-memory `FakeBleTransport` for tests); an `Elm327Session` that runs
  the ELM327 init handshake (`ATZ→ATE0→ATL0→ATS0→ATSP0`), requests **RPM (`010C`)
  only**, reassembles notification fragments to the `>` prompt, and exposes an
  observable `StateFlow<ConnectionState>`; a pure `PidCodec.decodeRpm` (`010C` →
  `((A*256)+B)/4`, the seed of the future codec); `NO DATA`/`?`/`UNABLE TO
  CONNECT`/timeout treated as clean failures with bounded retry + one reconnect;
  `ObdiiDriver.discover()/read()` wired to exercise the RPM path (`normalize()`
  stays M2). BLE permissions declared (`BLUETOOTH_SCAN`+`neverForLocation`,
  `BLUETOOTH_CONNECT`; legacy perms `maxSdkVersion`-guarded). GATT UUIDs are
  configurable/discovered, never hard-coded. Gate green
  (`./gradlew lintDebug testDebugUnitTest assembleDebug`).
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
