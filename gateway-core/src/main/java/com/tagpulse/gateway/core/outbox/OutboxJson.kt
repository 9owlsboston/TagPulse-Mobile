package com.tagpulse.gateway.core.outbox

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.tagpulse.gateway.core.GeoLocation

/**
 * JSON (de)serialization for the outbox's `*_json` columns.
 *
 * Serializes `Observation.payload` (`Map<String, Any?>`) and [GeoLocation] to the
 * strings persisted in [OutboxItem], and reconstructs them on read. Backed by
 * Jackson — the serialization stack the repo already committed to (the generated
 * client carries Jackson annotations; see `contract/CONTRACT.md`). Introducing the
 * Jackson **runtime** here (ahead of the M4 HTTP client) is justified because the
 * outbox genuinely needs JSON at rest.
 *
 * **Numeric round-trip contract.** JSON has a single number type, so on read
 * whole numbers reconstruct as `Int`/`Long` and **fractional numbers reconstruct
 * as `Double`** (e.g. a `Float` `49.8f` in the payload comes back as the `Double`
 * `49.8`). The *value* is preserved and formatting is locale-independent (Jackson
 * always emits `.` decimals); only the concrete `Number` subtype may widen. This
 * is lossless for the backend's `sensor_data` JSON.
 */
class OutboxJson(
    private val mapper: ObjectMapper = defaultMapper(),
) {
    /** Serialize a payload map to its `payload_json` column value. */
    fun encodePayload(payload: Map<String, Any?>): String =
        mapper.writeValueAsString(payload)

    /** Reconstruct a payload map from a `payload_json` column value. */
    fun decodePayload(json: String): Map<String, Any?> =
        mapper.readValue(json, object : TypeReference<Map<String, Any?>>() {})

    /** Serialize a [GeoLocation] to its `location_json` column value. */
    fun encodeLocation(location: GeoLocation): String =
        mapper.writeValueAsString(location)

    /** Reconstruct a [GeoLocation] from a `location_json` column value. */
    fun decodeLocation(json: String): GeoLocation =
        mapper.readValue(json, GeoLocation::class.java)

    companion object {
        /** Kotlin-aware mapper so [GeoLocation] (a data class) round-trips cleanly. */
        fun defaultMapper(): ObjectMapper = ObjectMapper().registerKotlinModule()
    }
}
