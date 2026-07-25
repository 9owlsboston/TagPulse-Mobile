package com.tagpulse.gateway.core.relay

import com.tagpulse.gateway.core.GeoLocation
import com.tagpulse.gateway.core.api.model.Identity
import com.tagpulse.gateway.core.api.model.Location
import com.tagpulse.gateway.core.api.model.TagReadCreate
import com.tagpulse.gateway.core.outbox.OutboxJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * Pins the **exact reflective Jackson (de)serialization contracts** the R8
 * keep-rules must preserve (ledger `C-ZVMF`): the generated `@get:JsonProperty`
 * models (incl. the nested `Location.Source` enum), `GeoLocation`, and the
 * anonymous `TypeReference` map reads.
 *
 * This is a **JVM/unit** guard — it exercises the *unminified* code, so it locks
 * the serialization behavior and catches any future change that breaks it. It does
 * **not** exercise R8 output; that is the job of the instrumented
 * `JacksonR8SmokeTest` run against the **minified `release`** variant on an
 * emulator/CI (see that test + `docs/history/execution-log.md`). The two together
 * cover "the contract is correct" (here) and "R8 preserves it" (there).
 */
class JacksonR8ContractTest {

    private val deviceId = UUID.fromString("11111111-2222-3333-4444-555555555555")

    @Test
    fun `generated TagReadCreate serializes with JsonProperty names and enum source`() {
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

        // The @get:JsonProperty snake_case names + the enum's @JsonProperty value +
        // the nested Identity model's field.
        assertTrue(json, json.contains("\"device_id\""))
        assertTrue(json, json.contains("\"accuracy_m\""))
        assertTrue(json, json.contains("\"source\":\"gps\""))
        assertTrue(json, json.contains("\"tag_id\":\"vehicle-42\""))
        assertTrue(json, json.contains("\"epc\":\"E280-1160-6000\""))
    }

    @Test
    fun `TypeReference map reads round-trip (INT_MAP and ANY_MAP paths)`() {
        val mapper = OkHttpBackendClient.defaultMapper()

        // Mirrors parseBatchBody's INT_MAP read.
        val intMap: Map<String, Int> =
            mapper.readValue("""{"ingested":3,"rejected":1}""", INT_MAP_REF)
        assertEquals(3, intMap["ingested"])
        assertEquals(1, intMap["rejected"])

        // Mirrors parseProvisionBody's ANY_MAP read.
        val anyMap: Map<String, Any?> =
            mapper.readValue("""{"device_id":"abc-123","status":"pending"}""", ANY_MAP_REF)
        assertEquals("abc-123", anyMap["device_id"])
        assertEquals("pending", anyMap["status"])
    }

    @Test
    fun `OutboxJson round-trips GeoLocation and a payload map`() {
        val json = OutboxJson()
        val loc = GeoLocation(latitude = 42.36, longitude = -71.06, accuracyMeters = 4.5)

        val decoded = json.decodeLocation(json.encodeLocation(loc))
        assertEquals(loc, decoded)

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
