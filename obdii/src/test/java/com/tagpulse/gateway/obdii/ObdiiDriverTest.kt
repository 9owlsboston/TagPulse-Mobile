package com.tagpulse.gateway.obdii

import com.tagpulse.gateway.core.DiscoveredDevice
import com.tagpulse.gateway.core.DriverReading
import com.tagpulse.gateway.core.GatewayDriver
import com.tagpulse.gateway.core.Modality
import com.tagpulse.gateway.core.Source
import com.tagpulse.gateway.core.Subject
import com.tagpulse.gateway.core.SubjectKind
import com.tagpulse.gateway.obdii.ble.FakeBleTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * Wiring + M2 read/normalize tests for the OBD-II driver.
 *
 * The smoke test proves the module depends on the `:gateway-core` seam; the read
 * test proves `discover → read` runs connect → handshake → four-PID snapshot over a
 * scriptable [FakeBleTransport]; the normalize test proves the pure snapshot →
 * [com.tagpulse.gateway.core.Observation] mapping (plan
 * `docs/design/obdii-mve-plan.md` §3/§4/§6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObdiiDriverTest {

    private val fullScript = mapOf(
        "ATZ" to listOf("ELM327 v1.5\r\r>"),
        "ATE0" to listOf("OK\r\r>"),
        "ATL0" to listOf("OK\r\r>"),
        "ATS0" to listOf("OK\r\r>"),
        "ATSP0" to listOf("OK\r\r>"),
        "010C" to listOf("41 0C 0D 48\r\r>"), // 850 rpm
        "010D" to listOf("41 0D 32\r\r>"), // 50 km/h
        "0105" to listOf("41 05 7B\r\r>"), // 83 °C
        "012F" to listOf("41 2F 7F\r\r>"), // ~49.8 %
    )

    @Test
    fun `driver implements the core seam and declares OBDII modality`() {
        val driver: GatewayDriver = ObdiiDriver()
        assertEquals(Modality.OBDII, driver.modality)
    }

    @Test
    fun `read connects, handshakes, and returns a DriverReading carrying the snapshot`() = runTest {
        val driver = ObdiiDriver.create(FakeBleTransport(fullScript))

        val device = driver.discover().single()
        val reading = driver.read(device)

        // The DriverReading carries the flattened snapshot on the neutral seam.
        assertEquals("obdii", reading.attributes["modality"])
        assertEquals("850", reading.attributes["pids.rpm"])
        assertEquals("50", reading.attributes["pids.speed_kph"])
        assertEquals("83", reading.attributes["pids.coolant_temp_c"])
        assertEquals("49.8", reading.attributes["pids.fuel_level_pct"])
    }

    @Test
    fun `normalize maps the snapshot onto an Observation with the configured subject and source`() {
        val config = ObdiiConfig(
            subject = Subject(SubjectKind.VEHICLE, id = "vehicle-binding-123"),
            source = Source(Modality.OBDII, gatewayDeviceId = null),
        )
        val driver = ObdiiDriver(config = config)

        val capturedAt = Instant.parse("2026-07-24T21:00:00Z")
        val snapshot = ObdSnapshot(
            capturedAt = capturedAt,
            rpm = 850,
            speedKph = 50,
            coolantTempC = 83,
            fuelLevelPct = 49.8f,
        )
        val reading = DriverReading(
            device = DiscoveredDevice("obdii-dongle", "OBD-II dongle", ""),
            attributes = snapshot.toAttributes(),
        )

        val observation = driver.normalize(reading)

        assertEquals(config.subject, observation.subject)
        assertEquals(config.source, observation.source)
        assertEquals(capturedAt, observation.timestamp)
        assertNull(observation.location) // GPS is a later layer (M4/M5)
        assertEquals("obdii", observation.payload["modality"])

        @Suppress("UNCHECKED_CAST")
        val pids = observation.payload["pids"] as Map<String, Any?>
        assertEquals(850, pids["rpm"])
        assertEquals(50, pids["speed_kph"])
        assertEquals(83, pids["coolant_temp_c"])
        assertEquals(49.8f, pids["fuel_level_pct"])
    }

    @Test
    fun `normalize drops PIDs that failed and never emits a location`() {
        val driver = ObdiiDriver(
            config = ObdiiConfig(subject = Subject(SubjectKind.VEHICLE, id = "v1")),
        )
        val snapshot = ObdSnapshot(
            capturedAt = Instant.parse("2026-07-24T21:00:00Z"),
            rpm = 850,
            coolantTempC = null, // this PID failed (NO DATA)
        )
        val reading = DriverReading(
            device = DiscoveredDevice("obdii-dongle", "OBD-II dongle", ""),
            attributes = snapshot.toAttributes(),
        )

        val observation = driver.normalize(reading)

        @Suppress("UNCHECKED_CAST")
        val pids = observation.payload["pids"] as Map<String, Any?>
        assertEquals(850, pids["rpm"])
        assertEquals(false, pids.containsKey("coolant_temp_c"))
        assertNull(observation.location)
    }
}
