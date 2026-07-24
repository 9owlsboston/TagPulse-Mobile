package com.tagpulse.gateway.core

import java.time.Instant

/**
 * The normalized unit a [GatewayDriver] hands to the gateway core.
 *
 * `Observation` is the single, modality-agnostic shape the core relays: every
 * driver — OBD-II today, camera / NFC / BLE-beacon later — normalizes its raw
 * read into this type, and only this type crosses the core/driver seam
 * (plan `docs/design/obdii-mve-plan.md` §3).
 *
 * The design's "cheap hedge" is encoded structurally: every observation keys an
 * explicit [subject] **and** [source] — never an implicit "self" — so a reading
 * always says *what* was observed and *by which modality on which gateway*.
 *
 * This is a pure data holder. Mapping an `Observation` onto the backend contract
 * (`TagReadCreate`, generated under `com.tagpulse.gateway.core.api.model`) is the
 * core's job at relay time (milestone M4) — the driver never sees HTTP.
 *
 * @property subject what was observed (e.g. the vehicle asset).
 * @property source the reporting modality + gateway that produced this reading.
 * @property timestamp capture time (UTC); the outbox drops stale items before relay.
 * @property payload the normalized, self-describing modality payload (e.g. the OBD
 *   PID snapshot) as a JSON-shaped map. Maps directly onto `TagReadCreate.sensor_data`
 *   (itself a `Map<String, Any>` in the generated contract).
 * @property location an optional geographic fix for the reading (e.g. the phone's
 *   GPS). Maps onto `TagReadCreate.location`.
 */
data class Observation(
    val subject: Subject,
    val source: Source,
    val timestamp: Instant,
    val payload: Map<String, Any?>,
    val location: GeoLocation? = null,
)

/**
 * What an [Observation] is *about* — the observed thing, never the gateway.
 *
 * For the OBD-II MVE this is the vehicle asset; [id] carries the value the core
 * relays as `TagReadCreate.tag_id` (the vehicle's `binding_kind='device'` binding
 * value — plan §4).
 */
data class Subject(
    val kind: SubjectKind,
    val id: String,
)

/** Coarse classification of an observation [Subject]. */
enum class SubjectKind {
    /** A vehicle (the OBD-II MVE subject). */
    VEHICLE,

    /** A generic tracked asset. */
    ASSET,

    /** A raw tag/identifier with no bound asset. */
    TAG,
}

/**
 * *Who/what* produced an [Observation]: the modality plus the reporting gateway.
 *
 * @property modality the sensing modality that produced the reading.
 * @property gatewayDeviceId the reporting gateway's provisioned device UUID
 *   (this phone), relayed as `TagReadCreate.device_id`. Null until enrolment (M4).
 */
data class Source(
    val modality: Modality,
    val gatewayDeviceId: String? = null,
)

/**
 * A gateway sensing modality. New modalities are added as new [GatewayDriver]
 * implementations, not new SDKs (plan §3) — this enum grows one entry per driver.
 */
enum class Modality {
    /** ELM327-over-BLE OBD-II dongle (the MVE's first and only driver). */
    OBDII,
}

/**
 * A normalized geographic fix attached to an [Observation].
 *
 * This is the driver/core-side location value object; it is deliberately distinct
 * from the generated backend `Location` model. The core maps this onto the
 * contract at relay time and stamps `source = "gps"` for the MVE (plan §4 — note
 * the backend field is `accuracy_m`, and `source` is a fixed enum).
 *
 * @property latitude WGS-84 latitude, −90..90.
 * @property longitude WGS-84 longitude, −180..180.
 * @property accuracyMeters horizontal accuracy in metres (≥ 0), if known.
 */
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double? = null,
)
