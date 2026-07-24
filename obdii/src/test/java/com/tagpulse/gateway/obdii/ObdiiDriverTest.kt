package com.tagpulse.gateway.obdii

import com.tagpulse.gateway.core.GatewayDriver
import com.tagpulse.gateway.core.Modality
import com.tagpulse.gateway.obdii.ble.FakeBleTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wiring + M1 read-path tests for the OBD-II driver.
 *
 * The smoke test proves the module depends on the `:gateway-core` seam; the read
 * test proves `discover → read` runs the connect → handshake → RPM path end to end
 * over a scriptable [FakeBleTransport] (plan `docs/design/obdii-mve-plan.md` §3/§6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObdiiDriverTest {

    @Test
    fun `driver implements the core seam and declares OBDII modality`() {
        val driver: GatewayDriver = ObdiiDriver()
        assertEquals(Modality.OBDII, driver.modality)
    }

    @Test
    fun `read connects, handshakes, and returns the decoded RPM`() = runTest {
        val transport = FakeBleTransport(
            mapOf(
                "ATZ" to listOf("ELM327 v1.5\r\r>"),
                "ATE0" to listOf("OK\r\r>"),
                "ATL0" to listOf("OK\r\r>"),
                "ATS0" to listOf("OK\r\r>"),
                "ATSP0" to listOf("OK\r\r>"),
                "010C" to listOf("41 0C 0D 48\r\r>"),
            ),
        )
        val driver = ObdiiDriver.create(transport)

        val device = driver.discover().single()
        val reading = driver.read(device)

        assertEquals("850", reading.attributes["rpm"])
        assertEquals("obdii", reading.attributes["modality"])
    }
}
