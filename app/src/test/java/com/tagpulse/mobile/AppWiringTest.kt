package com.tagpulse.mobile

import com.tagpulse.gateway.core.Modality
import com.tagpulse.gateway.obdii.ObdiiDriver
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M0 smoke test: the app module sees both library modules on its classpath
 * (`:gateway-core` + `:obdii`), proving the module graph is wired.
 */
class AppWiringTest {

    @Test
    fun `app can reach the obdii driver and core modality`() {
        assertEquals(Modality.OBDII, ObdiiDriver().modality)
    }
}
