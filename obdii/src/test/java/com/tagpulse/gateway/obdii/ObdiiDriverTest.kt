package com.tagpulse.gateway.obdii

import com.tagpulse.gateway.core.GatewayDriver
import com.tagpulse.gateway.core.Modality
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M0 smoke test: the obdii driver wires against the `:gateway-core` seam and
 * declares its modality. No BLE/PID behavior yet — `discover`/`read`/`normalize`
 * are unimplemented until M1–M2.
 */
class ObdiiDriverTest {

    @Test
    fun `driver implements the core seam and declares OBDII modality`() {
        // Typed as the core seam interface: proves obdii depends on gateway-core.
        val driver: GatewayDriver = ObdiiDriver()
        assertEquals(Modality.OBDII, driver.modality)
    }
}
