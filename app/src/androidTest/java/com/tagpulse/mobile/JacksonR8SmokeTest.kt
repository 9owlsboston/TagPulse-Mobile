package com.tagpulse.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tagpulse.gateway.core.GeoLocation
import com.tagpulse.gateway.core.api.model.Identity
import com.tagpulse.gateway.core.api.model.Location
import com.tagpulse.gateway.core.api.model.TagReadCreate
import com.tagpulse.gateway.core.outbox.OutboxJson
import com.tagpulse.gateway.core.relay.OkHttpBackendClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.UUID

/**
 * R8 keep-rule smoke test (ledger `C-ZVMF`) — the runtime half of the footprint
 * gate.
 *
 * This is an **instrumented** test so it runs against the **minified `release`**
 * variant (the module sets `testBuildType = "release"`), i.e. against the app
 * *after* R8 has tree-shaken the ~145-schema generated-model superset and applied
 * the gateway-core consumer keep-rules. It exercises the exact reflective Jackson
 * paths those rules must preserve: the generated `@get:JsonProperty` models (incl.
 * the nested `Location.Source` enum + `Identity`), `GeoLocation`, and the anonymous
 * `TypeReference` map reads.
 *
 * **Runs on an emulator/device + a release signing config only** (the remaining
 * CI/HIL gate — this env has no emulator/KVM). Build-verify it compiles against the
 * R8 variant with:
 * ```
 * ./gradlew :app:assembleReleaseAndroidTest
 * ```
 * Then, on a signed emulator/CI:
 * ```
 * ./gradlew :app:connectedReleaseAndroidTest
 * ```
 * The unminified contract is separately locked by
 * `gateway-core` `JacksonR8ContractTest` (JVM).
 */
@RunWith(AndroidJUnit4::class)
class JacksonR8SmokeTest {

    private val deviceId = UUID.fromString("11111111-2222-3333-4444-555555555555")

    @Test
    fun generatedTagReadCreate_serializesWithJsonPropertyNames() {
        val mapper = OkHttpBackendClient.defaultMapper()
        val read = TagReadCreate(
            deviceId = deviceId,
            timestamp = "2026-07-24T21:00:00Z",
            tagId = "vehicle-42",
            identity = Identity(epc = "E280-1160-6000"),
            location = Location(
                latitude = BigDecimal.valueOf(42.36),
                longitude = BigDecimal.valueOf(-71.06),
                accuracyM = BigDecimal.valueOf(4.5),
                source = Location.Source.GPS,
            ),
            sensorData = mapOf("modality" to "obdii"),
        )

        val json = mapper.writeValueAsString(read)

        assertTrue(json, json.contains("\"device_id\""))
        assertTrue(json, json.contains("\"accuracy_m\""))
        assertTrue(json, json.contains("\"source\":\"gps\""))
        assertTrue(json, json.contains("\"tag_id\":\"vehicle-42\""))
        assertTrue(json, json.contains("\"epc\":\"E280-1160-6000\""))
    }

    @Test
    fun typeReferenceMapReads_roundTrip() {
        val mapper = OkHttpBackendClient.defaultMapper()

        val intMap: Map<String, Int> =
            mapper.readValue("""{"ingested":3,"rejected":1}""", INT_MAP_REF)
        assertEquals(3, intMap["ingested"])
        assertEquals(1, intMap["rejected"])

        val anyMap: Map<String, Any?> =
            mapper.readValue("""{"device_id":"abc-123","status":"pending"}""", ANY_MAP_REF)
        assertEquals("abc-123", anyMap["device_id"])
        assertEquals("pending", anyMap["status"])
    }

    @Test
    fun outboxJson_roundTripsGeoLocationAndPayload() {
        val json = OutboxJson()
        val loc = GeoLocation(latitude = 42.36, longitude = -71.06, accuracyMeters = 4.5)

        assertEquals(loc, json.decodeLocation(json.encodeLocation(loc)))

        val payload = mapOf("modality" to "obdii", "pids" to mapOf("rpm" to 850))
        val roundTripped = json.decodePayload(json.encodePayload(payload))
        assertEquals("obdii", roundTripped["modality"])
        @Suppress("UNCHECKED_CAST")
        val pids = roundTripped["pids"] as Map<String, Any?>
        assertEquals(850, pids["rpm"])
    }

    private companion object {
        val INT_MAP_REF = object :
            com.fasterxml.jackson.core.type.TypeReference<Map<String, Int>>() {}
        val ANY_MAP_REF = object :
            com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}
    }
}
