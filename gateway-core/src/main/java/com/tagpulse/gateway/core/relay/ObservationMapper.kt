package com.tagpulse.gateway.core.relay

import com.tagpulse.gateway.core.GeoLocation
import com.tagpulse.gateway.core.Observation
import com.tagpulse.gateway.core.api.model.Location
import com.tagpulse.gateway.core.api.model.TagReadCreate
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Pure mapping from the core's [Observation] onto the **generated** backend
 * contract model [TagReadCreate] (plan §4).
 *
 * The generated models are never hand-written (AGENTS §2); this object only
 * *populates* them. Field-by-field (plan §4 table):
 *
 * | `TagReadCreate` | source | note |
 * |---|---|---|
 * | `device_id` | the gateway's provisioned UUID | the *reporting device* (the phone) |
 * | `tag_id` | `Observation.subject.id` | the vehicle's `binding_kind='device'` value |
 * | `timestamp` | `Observation.timestamp` | ISO-8601 UTC instant |
 * | `sensor_data` | `Observation.payload` | the PID snapshot JSON |
 * | `location` | `Observation.location?` | GPS fix → `Location(source="gps")` |
 * | `identity` / `tag_data` / `reader_antenna` | — | left null (RFID-specific) |
 */
object ObservationMapper {

    /**
     * Build a [TagReadCreate] from [observation], reported by the gateway
     * [deviceId] (the phone's provisioned device UUID). Pure — no I/O.
     */
    fun toTagReadCreate(observation: Observation, deviceId: UUID): TagReadCreate =
        TagReadCreate(
            deviceId = deviceId,
            // ISO_INSTANT renders a UTC `...Z` timestamp the backend datetime parses.
            timestamp = DateTimeFormatter.ISO_INSTANT.format(observation.timestamp),
            tagId = observation.subject.id,
            sensorData = observation.payload.toSensorData(),
            location = observation.location?.toContractLocation(),
            // RFID-specific fields are unused by the OBD-II MVE (plan §4).
            identity = null,
            tagData = null,
            readerAntenna = null,
            signalStrength = null,
        )

    /**
     * `Observation.payload` is `Map<String, Any?>`; the generated `sensor_data`
     * is `Map<String, Any>?`. Drop null-valued top-level keys (the snapshot builder
     * already omits absent PIDs) and hand the rest through — Jackson serializes the
     * nested structure as-is.
     */
    private fun Map<String, Any?>.toSensorData(): Map<String, Any>? {
        val nonNull = LinkedHashMap<String, Any>(size)
        for ((k, v) in this) if (v != null) nonNull[k] = v
        return nonNull.ifEmpty { null }
    }

    /** [GeoLocation] → generated [Location] with `source = "gps"` (plan §4 note). */
    private fun GeoLocation.toContractLocation(): Location =
        Location(
            latitude = BigDecimal.valueOf(latitude),
            longitude = BigDecimal.valueOf(longitude),
            // Backend field is `accuracy_m` (not `accuracy_meters`) — plan §4 correction.
            accuracyM = accuracyMeters?.let { BigDecimal.valueOf(it) },
            source = Location.Source.GPS,
        )
}
