package com.tagpulse.mobile.enrol

import com.tagpulse.gateway.core.relay.ProvisionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [EnrolmentCoordinator] over fake seams (no device / network / Keystore). Asserts
 * validation, the provision→persist ordering, atomic success-only persistence, and
 * secret-free error states (`docs/design/enrolment-flow.md`).
 *
 * Robolectric only supplies a working `android.util.Log` (the coordinator's sole
 * Android touchpoint); all collaborators are plain fakes.
 */
@RunWith(RobolectricTestRunner::class)
class EnrolmentCoordinatorTest {

    private data class PersistCall(val deviceId: String, val apiKey: String, val baseUrl: String)

    /** Records provision calls + returns a scripted result; captures persist calls. */
    private class Harness(
        private val result: ProvisionResult = ProvisionResult.Registered("dev-123", "pending"),
        private val persistThrows: Boolean = false,
    ) {
        var provisionCalls = 0; private set
        var lastProvisionArgs: Triple<String, String, String>? = null; private set
        val persisted = mutableListOf<PersistCall>()

        val coordinator = EnrolmentCoordinator(
            provision = { baseUrl, key, name ->
                provisionCalls++
                lastProvisionArgs = Triple(baseUrl, key, name)
                result
            },
            persist = { deviceId, apiKey, baseUrl ->
                if (persistThrows) error("secure-store write failed")
                persisted += PersistCall(deviceId, apiKey, baseUrl)
            },
        )
    }

    private fun input(
        baseUrl: String = "https://api.tenant.example",
        provisioningKey: String = "prov-key-xyz",
        ingestApiKey: String = "tp_acme_SECRET",
        deviceName: String = "phone-1",
    ) = EnrolmentInput(baseUrl, provisioningKey, ingestApiKey, deviceName)

    @Test
    fun `happy path provisions then atomically persists and reports Enrolled`() = runBlocking {
        val h = Harness(result = ProvisionResult.Registered("dev-123", "pending"))

        h.coordinator.enrol(input())

        // Provisioned once against the normalized https origin, with trimmed key + name.
        assertEquals(1, h.provisionCalls)
        assertEquals(Triple("https://api.tenant.example", "prov-key-xyz", "phone-1"), h.lastProvisionArgs)
        // Persisted exactly the enrolment tuple (deviceId, tp_ key, baseUrl), once.
        assertEquals(listOf(PersistCall("dev-123", "tp_acme_SECRET", "https://api.tenant.example")), h.persisted)
        assertEquals(EnrolState.Enrolled("dev-123", "pending"), h.coordinator.state.value)
    }

    @Test
    fun `trims fields before provisioning and persisting`() = runBlocking {
        val h = Harness()
        h.coordinator.enrol(input(provisioningKey = "  prov-key-xyz  ", ingestApiKey = "  tp_acme_SECRET  ", deviceName = " phone-1 "))
        assertEquals("prov-key-xyz", h.lastProvisionArgs!!.second)
        assertEquals("phone-1", h.lastProvisionArgs!!.third)
        assertEquals("tp_acme_SECRET", h.persisted.single().apiKey)
    }

    @Test
    fun `normalizes base url to its origin (strips path, trailing slash)`() = runBlocking {
        val h = Harness()
        h.coordinator.enrol(input(baseUrl = "https://api.tenant.example:8443/ignored/path/"))
        assertEquals("https://api.tenant.example:8443", h.lastProvisionArgs!!.first)
        assertEquals("https://api.tenant.example:8443", h.persisted.single().baseUrl)
    }

    @Test
    fun `blank field is an INPUT error with no network or persistence`() = runBlocking {
        val h = Harness()
        h.coordinator.enrol(input(provisioningKey = "   "))
        assertEquals(0, h.provisionCalls)
        assertTrue(h.persisted.isEmpty())
        val state = h.coordinator.state.value
        assertTrue(state is EnrolState.Error)
        assertEquals(EnrolState.ErrorKind.INPUT, (state as EnrolState.Error).kind)
    }

    @Test
    fun `non-https base url is an INPUT error with no network`() = runBlocking {
        val h = Harness()
        h.coordinator.enrol(input(baseUrl = "http://api.tenant.example"))
        assertEquals(0, h.provisionCalls)
        assertTrue(h.persisted.isEmpty())
        assertEquals(EnrolState.ErrorKind.INPUT, (h.coordinator.state.value as EnrolState.Error).kind)
    }

    @Test
    fun `malformed base url is an INPUT error`() = runBlocking {
        val h = Harness()
        h.coordinator.enrol(input(baseUrl = "not a url"))
        assertEquals(0, h.provisionCalls)
        assertEquals(EnrolState.ErrorKind.INPUT, (h.coordinator.state.value as EnrolState.Error).kind)
    }

    @Test
    fun `provision failure is a PROVISION error and persists nothing`() = runBlocking {
        val h = Harness(result = ProvisionResult.Failed(401, "invalid provisioning key"))
        h.coordinator.enrol(input())
        assertEquals(1, h.provisionCalls)
        assertTrue("nothing persisted on failure", h.persisted.isEmpty())
        val state = h.coordinator.state.value
        assertEquals(EnrolState.ErrorKind.PROVISION, (state as EnrolState.Error).kind)
        // The message carries the status code but not the server body/secret.
        assertTrue(state.message.contains("401"))
        assertFalse(state.message.contains("tp_"))
    }

    @Test
    fun `unexpected persist failure is an INTERNAL error`() = runBlocking {
        val h = Harness(persistThrows = true)
        h.coordinator.enrol(input())
        assertEquals(1, h.provisionCalls)
        assertEquals(EnrolState.ErrorKind.INTERNAL, (h.coordinator.state.value as EnrolState.Error).kind)
    }

    @Test
    fun `EnrolmentInput toString redacts both secrets`() {
        val s = input(provisioningKey = "prov-key-xyz", ingestApiKey = "tp_acme_SECRET").toString()
        assertFalse(s.contains("prov-key-xyz"))
        assertFalse(s.contains("tp_acme_SECRET"))
        assertTrue(s.contains("redacted"))
    }

    @Test
    fun `initial state is Idle`() {
        assertEquals(EnrolState.Idle, Harness().coordinator.state.value)
    }
}
