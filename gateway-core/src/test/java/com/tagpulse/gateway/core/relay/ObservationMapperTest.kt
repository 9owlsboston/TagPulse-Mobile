package com.tagpulse.gateway.core.relay

import com.tagpulse.gateway.core.GeoLocation
import com.tagpulse.gateway.core.Modality
import com.tagpulse.gateway.core.Observation
import com.tagpulse.gateway.core.Source
import com.tagpulse.gateway.core.Subject
import com.tagpulse.gateway.core.SubjectKind
import com.tagpulse.gateway.core.api.model.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * `Observation` → generated `TagReadCreate` mapping (plan §4), field-by-field.
 * Pure JVM — no Android, no Robolectric.
 */
class ObservationMapperTest {

    private val deviceId = UUID.fromString("11111111-2222-3333-4444-555555555555")

    private fun observation(
        location: GeoLocation? = GeoLocation(latitude = 42.36, longitude = -71.06, accuracyMeters = 4.5),
        payload: Map<String, Any?> = linkedMapOf(
            "modality" to "obdii",
            "pids" to linkedMapOf("rpm" to 850, "fuel_level_pct" to 49.8),
        ),
    ) = Observation(
        subject = Subject(kind = SubjectKind.VEHICLE, id = "vehicle-42"),
        source = Source(modality = Modality.OBDII, gatewayDeviceId = deviceId.toString()),
        timestamp = Instant.parse("2026-07-24T21:00:00Z"),
        payload = payload,
        location = location,
    )

    @Test
    fun `device_id is the gateway UUID, tag_id is the subject id`() {
        val read = ObservationMapper.toTagReadCreate(observation(), deviceId)
        assertEquals(deviceId, read.deviceId)
        assertEquals("vehicle-42", read.tagId)
    }

    @Test
    fun `timestamp is ISO-8601 UTC`() {
        val read = ObservationMapper.toTagReadCreate(observation(), deviceId)
        assertEquals("2026-07-24T21:00:00Z", read.timestamp)
    }

    @Test
    fun `sensor_data carries the payload snapshot`() {
        val read = ObservationMapper.toTagReadCreate(observation(), deviceId)
        val sensor = read.sensorData!!
        assertEquals("obdii", sensor["modality"])
        @Suppress("UNCHECKED_CAST")
        val pids = sensor["pids"] as Map<String, Any>
        assertEquals(850, pids["rpm"])
        assertEquals(49.8, pids["fuel_level_pct"])
    }

    @Test
    fun `location maps with accuracy_m and source=gps`() {
        val read = ObservationMapper.toTagReadCreate(observation(), deviceId)
        val loc = read.location!!
        assertEquals(0, BigDecimal.valueOf(42.36).compareTo(loc.latitude))
        assertEquals(0, BigDecimal.valueOf(-71.06).compareTo(loc.longitude))
        assertEquals(0, BigDecimal.valueOf(4.5).compareTo(loc.accuracyM))
        assertEquals(Location.Source.GPS, loc.source)
    }

    @Test
    fun `null location maps to null`() {
        val read = ObservationMapper.toTagReadCreate(observation(location = null), deviceId)
        assertNull(read.location)
    }

    @Test
    fun `location without accuracy leaves accuracy_m null`() {
        val obs = observation(location = GeoLocation(latitude = 1.0, longitude = 2.0, accuracyMeters = null))
        val read = ObservationMapper.toTagReadCreate(obs, deviceId)
        assertNull(read.location!!.accuracyM)
    }

    @Test
    fun `RFID-specific fields are left null`() {
        val read = ObservationMapper.toTagReadCreate(observation(), deviceId)
        assertNull(read.identity)
        assertNull(read.tagData)
        assertNull(read.readerAntenna)
        assertNull(read.signalStrength)
    }

    @Test
    fun `null-valued top-level payload keys are dropped`() {
        val obs = observation(payload = linkedMapOf("modality" to "obdii", "raw" to null))
        val read = ObservationMapper.toTagReadCreate(obs, deviceId)
        assertEquals(setOf("modality"), read.sensorData!!.keys)
    }
}
