package com.tagpulse.gateway.obdii

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Tests for the `sensor_data` snapshot model (plan
 * `docs/design/obdii-mve-plan.md` §4): the payload shape, null-PID omission, the
 * debug-gated `raw` block, and the flatten/reconstruct round-trip that carries the
 * snapshot across the string-typed core/driver seam.
 */
class ObdSnapshotTest {

    private val capturedAt = Instant.parse("2026-07-24T21:00:00Z")

    @Test
    fun `toPayload matches the plan §4 sensor_data shape`() {
        val payload = ObdSnapshot(
            capturedAt = capturedAt,
            rpm = 850,
            speedKph = 0,
            coolantTempC = 89,
            fuelLevelPct = 47.5f,
            dongle = ObdSnapshot.DongleInfo(bleName = "OBDII", adapter = "elm327", elmVersion = "1.5"),
        ).toPayload()

        assertEquals("obdii", payload["modality"])
        assertEquals("elm327/j1979", payload["protocol"])
        assertEquals("2026-07-24T21:00:00Z", payload["captured_at"])

        @Suppress("UNCHECKED_CAST")
        val pids = payload["pids"] as Map<String, Any?>
        assertEquals(850, pids["rpm"])
        assertEquals(0, pids["speed_kph"])
        assertEquals(89, pids["coolant_temp_c"])
        assertEquals(47.5f, pids["fuel_level_pct"])

        @Suppress("UNCHECKED_CAST")
        val dongle = payload["dongle"] as Map<String, Any?>
        assertEquals("OBDII", dongle["ble_name"])
        assertEquals("elm327", dongle["adapter"])
        assertEquals("1.5", dongle["elm_version"])
    }

    @Test
    fun `toPayload omits failed PIDs and, by default, the raw block`() {
        val payload = ObdSnapshot(
            capturedAt = capturedAt,
            rpm = 850,
            coolantTempC = null, // failed PID
            rawFrames = mapOf("010C" to "41 0C 0D 48"),
            includeRaw = false,
        ).toPayload()

        @Suppress("UNCHECKED_CAST")
        val pids = payload["pids"] as Map<String, Any?>
        assertTrue(pids.containsKey("rpm"))
        assertFalse(pids.containsKey("coolant_temp_c"))
        assertFalse(payload.containsKey("raw"))
    }

    @Test
    fun `toPayload emits the raw block only when the debug flag is set`() {
        val payload = ObdSnapshot(
            capturedAt = capturedAt,
            rpm = 850,
            rawFrames = mapOf("010C" to "41 0C 0D 48"),
            includeRaw = true,
        ).toPayload()

        @Suppress("UNCHECKED_CAST")
        val raw = payload["raw"] as Map<String, Any?>
        assertEquals("41 0C 0D 48", raw["010C"])
    }

    @Test
    fun `attributes round-trip reconstructs an equal snapshot`() {
        val original = ObdSnapshot(
            capturedAt = capturedAt,
            rpm = 850,
            speedKph = 50,
            coolantTempC = -20,
            fuelLevelPct = 49.8f,
            dongle = ObdSnapshot.DongleInfo(bleName = "OBDII", adapter = "elm327"),
            rawFrames = mapOf("010C" to "41 0C 0D 48", "010D" to "41 0D 32"),
            includeRaw = true,
        )

        val restored = ObdSnapshot.fromAttributes(original.toAttributes())

        assertEquals(original, restored)
    }

    @Test
    fun `attributes round-trip preserves absent PIDs as null`() {
        val original = ObdSnapshot(capturedAt = capturedAt, rpm = 850)

        val restored = ObdSnapshot.fromAttributes(original.toAttributes())

        assertEquals(850, restored.rpm)
        assertEquals(null, restored.speedKph)
        assertEquals(null, restored.coolantTempC)
        assertEquals(null, restored.fuelLevelPct)
        assertEquals(null, restored.dongle)
    }
}
