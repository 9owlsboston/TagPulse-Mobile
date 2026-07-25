package com.tagpulse.gateway.core.outbox

import com.tagpulse.gateway.core.GeoLocation
import com.tagpulse.gateway.core.Modality
import com.tagpulse.gateway.core.Observation
import com.tagpulse.gateway.core.Source
import com.tagpulse.gateway.core.Subject
import com.tagpulse.gateway.core.SubjectKind
import java.time.Instant

/**
 * Shared fixtures for the outbox tests.
 */
internal object OutboxFixtures {

    /** A representative OBD-II `sensor_data` snapshot (plan §4 shape). */
    fun sensorDataPayload(): Map<String, Any?> = linkedMapOf(
        "modality" to "obdii",
        "protocol" to "elm327/j1979",
        "captured_at" to "2026-07-24T21:00:00Z",
        // Nested pids map: whole-number PIDs + a fractional one (fuel %). The
        // coolant PID is intentionally absent (null-omitted, per toPayload()).
        "pids" to linkedMapOf(
            "rpm" to 850,
            "speed_kph" to 0,
            "fuel_level_pct" to 49.8,
        ),
    )

    /** A full observation: explicit subject + source, payload, and a GPS fix. */
    fun observation(
        subjectId: String = "vehicle-42",
        capturedAt: Instant = Instant.parse("2026-07-24T21:00:00Z"),
        payload: Map<String, Any?> = sensorDataPayload(),
        location: GeoLocation? = GeoLocation(latitude = 42.36, longitude = -71.06, accuracyMeters = 4.5),
    ): Observation = Observation(
        subject = Subject(kind = SubjectKind.VEHICLE, id = subjectId),
        source = Source(modality = Modality.OBDII, gatewayDeviceId = null),
        timestamp = capturedAt,
        payload = payload,
        location = location,
    )
}
