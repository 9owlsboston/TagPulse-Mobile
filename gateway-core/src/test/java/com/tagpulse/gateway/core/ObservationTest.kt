package com.tagpulse.gateway.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * M0 smoke tests: prove the module graph + test wiring compile and run, and that
 * the core domain seam types behave. No behavior under test yet (M1+).
 */
class ObservationTest {

    @Test
    fun `observation keys an explicit subject and source`() {
        val obs = Observation(
            subject = Subject(kind = SubjectKind.VEHICLE, id = "vehicle-42"),
            source = Source(modality = Modality.OBDII, gatewayDeviceId = null),
            timestamp = Instant.parse("2026-07-24T21:00:00Z"),
            payload = mapOf("modality" to "obdii"),
            location = GeoLocation(latitude = 42.36, longitude = -71.06, accuracyMeters = 5.0),
        )

        // The design's "cheap hedge": never an implicit self — subject + source present.
        assertEquals(SubjectKind.VEHICLE, obs.subject.kind)
        assertEquals(Modality.OBDII, obs.source.modality)
        assertNull("gateway device id is unset until enrolment (M4)", obs.source.gatewayDeviceId)
        assertNotNull(obs.location)
        assertEquals(-71.06, obs.location!!.longitude, 0.0)
    }
}
