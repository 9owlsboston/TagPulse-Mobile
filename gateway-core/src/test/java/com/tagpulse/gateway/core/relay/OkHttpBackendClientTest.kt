package com.tagpulse.gateway.core.relay

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.tagpulse.gateway.core.api.model.Location
import com.tagpulse.gateway.core.api.model.TagReadCreate
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * [OkHttpBackendClient] against a loopback [MockWebServer] (no network, no device).
 * Asserts request path/method/auth header/body shape and status→outcome mapping.
 */
class OkHttpBackendClientTest {

    private lateinit var server: MockWebServer
    private lateinit var credentials: FakeCredentialStore
    private lateinit var client: OkHttpBackendClient
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private val deviceId = UUID.fromString("11111111-2222-3333-4444-555555555555")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        credentials = FakeCredentialStore(
            baseUrl = server.url("/").toString(),
            deviceId = deviceId.toString(),
            apiKey = "tp_acme_SECRET",
        )
        client = OkHttpBackendClient(credentials)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sampleRead() = TagReadCreate(
        deviceId = deviceId,
        timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.parse("2026-07-24T21:00:00Z")),
        tagId = "vehicle-42",
        sensorData = mapOf("modality" to "obdii", "pids" to mapOf("rpm" to 850)),
        location = Location(
            latitude = BigDecimal.valueOf(42.36),
            longitude = BigDecimal.valueOf(-71.06),
            accuracyM = BigDecimal.valueOf(4.5),
            source = Location.Source.GPS,
        ),
    )

    @Test
    fun `201 parses ingested and rejected`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ingested":1,"rejected":0}"""))

        val result = client.postTagReadsBatch(listOf(sampleRead()))

        assertEquals(BatchResult.Accepted(ingested = 1, rejected = 0), result)
    }

    @Test
    fun `request has correct path, method, auth header, and jackson body shape`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ingested":1,"rejected":0}"""))

        client.postTagReadsBatch(listOf(sampleRead()))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/tag-reads/batch", recorded.path)
        assertEquals("Bearer tp_acme_SECRET", recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("Content-Type").orEmpty().startsWith("application/json"))

        // The body is a JSON array of TagReadCreate with the generated field names.
        val body = recorded.body.readUtf8()
        val parsed: List<Map<String, Any?>> = mapper.readValue(
            body,
            mapper.typeFactory.constructCollectionType(List::class.java, Map::class.java),
        )
        assertEquals(1, parsed.size)
        val read = parsed.first()
        assertEquals(deviceId.toString(), read["device_id"])
        assertEquals("2026-07-24T21:00:00Z", read["timestamp"])
        assertEquals("vehicle-42", read["tag_id"])

        @Suppress("UNCHECKED_CAST")
        val sensor = read["sensor_data"] as Map<String, Any?>
        assertEquals("obdii", sensor["modality"])

        @Suppress("UNCHECKED_CAST")
        val loc = read["location"] as Map<String, Any?>
        // accuracy_m (not accuracy_meters) and source=gps (plan §4).
        assertTrue(loc.containsKey("accuracy_m"))
        assertEquals("gps", loc["source"])
        assertEquals(42.36, (loc["latitude"] as Number).toDouble(), 1e-9)
    }

    @Test
    fun `500 maps to Retryable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val result = client.postTagReadsBatch(listOf(sampleRead()))
        assertTrue(result is BatchResult.Retryable)
    }

    @Test
    fun `400 maps to Terminal`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad payload"))
        val result = client.postTagReadsBatch(listOf(sampleRead()))
        assertTrue(result is BatchResult.Terminal)
        assertEquals(400, (result as BatchResult.Terminal).statusCode)
    }

    @Test
    fun `401 maps to CredentialError`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        val result = client.postTagReadsBatch(listOf(sampleRead()))
        assertTrue(result is BatchResult.CredentialError)
    }

    @Test
    fun `408 maps to Retryable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(408).setBody("request timeout"))
        val result = client.postTagReadsBatch(listOf(sampleRead()))
        assertTrue(result is BatchResult.Retryable)
        assertNull((result as BatchResult.Retryable).retryAfterMillis)
    }

    @Test
    fun `429 maps to Retryable and honors Retry-After delta-seconds`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "30").setBody("slow down"),
        )
        val result = client.postTagReadsBatch(listOf(sampleRead()))
        assertTrue(result is BatchResult.Retryable)
        assertEquals(30_000L, (result as BatchResult.Retryable).retryAfterMillis)
    }

    @Test
    fun `429 without Retry-After is Retryable with no directive`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        val result = client.postTagReadsBatch(listOf(sampleRead()))
        assertTrue(result is BatchResult.Retryable)
        assertNull((result as BatchResult.Retryable).retryAfterMillis)
    }

    @Test
    fun `429 with non-numeric (HTTP-date) Retry-After falls back to no directive`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT").setBody("slow down"),
        )
        val result = client.postTagReadsBatch(listOf(sampleRead()))
        assertTrue(result is BatchResult.Retryable)
        assertNull((result as BatchResult.Retryable).retryAfterMillis)
    }

    @Test
    fun `missing api key short-circuits to CredentialError without a request`() = runBlocking {
        credentials.apiKey = null
        val result = client.postTagReadsBatch(listOf(sampleRead()))
        assertTrue(result is BatchResult.CredentialError)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `provisionDevice posts X-Provisioning-Key and parses device_id`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"device_id":"abc-123","status":"pending","message":"ok"}"""),
        )

        val result = client.provisionDevice(provisioningKey = "prov-key-xyz", name = "phone-1")

        assertEquals(ProvisionResult.Registered(deviceId = "abc-123", status = "pending"), result)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/devices/provision", recorded.path)
        assertEquals("prov-key-xyz", recorded.getHeader("X-Provisioning-Key"))
        // Default device_type is the phone-gateway type, not "rfid_reader".
        val body: Map<String, Any?> = mapper.readValue(
            recorded.body.readUtf8(),
            mapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java),
        )
        assertEquals("phone-1", body["name"])
        assertEquals("mobile_gateway", body["device_type"])
    }

    @Test
    fun `provisionDevice maps non-2xx to Failed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Invalid provisioning key"))
        val result = client.provisionDevice(provisioningKey = "bad", name = "phone-1")
        assertTrue(result is ProvisionResult.Failed)
        assertEquals(401, (result as ProvisionResult.Failed).statusCode)
    }

    @Test
    fun `resolveAssetByBinding 200 parses asset id and plate, sends bearer + encoded value`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"id":"asset-9","display_label":"MASS-1234","binding_kind":"device","name":"Van 9"}"""),
        )

        val result = client.resolveAssetByBinding("1HGCM82633A004352")

        assertEquals(
            AssetLookupResult.Resolved(assetId = "asset-9", displayLabel = "MASS-1234", bindingKind = "device"),
            result,
        )
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/assets/by-binding?value=1HGCM82633A004352", recorded.path)
        assertEquals("Bearer tp_acme_SECRET", recorded.getHeader("Authorization"))
    }

    @Test
    fun `resolveAssetByBinding url-encodes the value`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"a","display_label":"P"}"""))
        client.resolveAssetByBinding("A B/C")
        assertEquals("/assets/by-binding?value=A+B%2FC", server.takeRequest().path)
    }

    @Test
    fun `resolveAssetByBinding 200 with blank plate yields null displayLabel`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"a","display_label":""}"""))
        val result = client.resolveAssetByBinding("v")
        assertEquals(AssetLookupResult.Resolved(assetId = "a", displayLabel = null, bindingKind = null), result)
    }

    @Test
    fun `resolveAssetByBinding 404 maps to NotFound`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        assertEquals(AssetLookupResult.NotFound, client.resolveAssetByBinding("v"))
    }

    @Test
    fun `resolveAssetByBinding 401 and 403 map to CredentialError`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(client.resolveAssetByBinding("v") is AssetLookupResult.CredentialError)
        server.enqueue(MockResponse().setResponseCode(403))
        assertTrue(client.resolveAssetByBinding("v") is AssetLookupResult.CredentialError)
    }

    @Test
    fun `resolveAssetByBinding 5xx maps to Retryable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(client.resolveAssetByBinding("v") is AssetLookupResult.Retryable)
    }

    @Test
    fun `resolveAssetByBinding missing api key short-circuits to CredentialError without a request`() = runBlocking {
        credentials.apiKey = null
        val result = client.resolveAssetByBinding("v")
        assertTrue(result is AssetLookupResult.CredentialError)
        assertEquals(0, server.requestCount)
    }
}
