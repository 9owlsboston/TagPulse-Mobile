package com.tagpulse.mobile.bind

import com.tagpulse.gateway.core.relay.AssetLookupResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [VehicleBindingCoordinator] over fake seams (no device / network / store). Asserts
 * validation, resolve→confirm, plate-required, and error mapping
 * (`docs/design/vehicle-bind-flow.md`). Robolectric only supplies `android.util.Log`.
 */
@RunWith(RobolectricTestRunner::class)
class VehicleBindingCoordinatorTest {

    private val validVin = "1HGCM82633A004352"

    private class Harness(private val result: AssetLookupResult) {
        var resolveCalls = 0; private set
        var lastResolveArg: String? = null; private set
        val persisted = mutableListOf<VehicleBinding>()

        val coordinator = VehicleBindingCoordinator(
            resolve = { vin -> resolveCalls++; lastResolveArg = vin; result },
            persist = { persisted += it },
        )
    }

    @Test
    fun `resolve then confirm persists the binding and reports Bound`() = runBlocking {
        val h = Harness(AssetLookupResult.Resolved(assetId = "asset-9", displayLabel = "MASS-1234"))

        h.coordinator.resolve("  1hgcm82633a004352 ")

        // Resolve is called with the CANONICAL vin.
        assertEquals(1, h.resolveCalls)
        assertEquals(validVin, h.lastResolveArg)
        assertEquals(BindState.Confirming(validVin, "MASS-1234", "asset-9"), h.coordinator.state.value)

        h.coordinator.confirm()

        assertEquals(listOf(VehicleBinding(validVin, "MASS-1234", "asset-9")), h.persisted)
        assertEquals(BindState.Bound(validVin, "MASS-1234"), h.coordinator.state.value)
    }

    @Test
    fun `invalid VIN is an INPUT error with no network`() = runBlocking {
        val h = Harness(AssetLookupResult.NotFound)
        h.coordinator.resolve("NOT-A-VIN")
        assertEquals(0, h.resolveCalls)
        assertEquals(BindState.ErrorKind.INPUT, (h.coordinator.state.value as BindState.Error).kind)
    }

    @Test
    fun `resolved vehicle with no plate is a NO_PLATE error`() = runBlocking {
        val h = Harness(AssetLookupResult.Resolved(assetId = "asset-9", displayLabel = null))
        h.coordinator.resolve(validVin)
        assertEquals(BindState.ErrorKind.NO_PLATE, (h.coordinator.state.value as BindState.Error).kind)
    }

    @Test
    fun `404 maps to NOT_FOUND`() = runBlocking {
        val h = Harness(AssetLookupResult.NotFound)
        h.coordinator.resolve(validVin)
        assertEquals(BindState.ErrorKind.NOT_FOUND, (h.coordinator.state.value as BindState.Error).kind)
    }

    @Test
    fun `credential error maps to CREDENTIAL`() = runBlocking {
        val h = Harness(AssetLookupResult.CredentialError("401"))
        h.coordinator.resolve(validVin)
        assertEquals(BindState.ErrorKind.CREDENTIAL, (h.coordinator.state.value as BindState.Error).kind)
    }

    @Test
    fun `retryable and terminal map to NETWORK`() = runBlocking {
        val r = Harness(AssetLookupResult.Retryable("5xx"))
        r.coordinator.resolve(validVin)
        assertEquals(BindState.ErrorKind.NETWORK, (r.coordinator.state.value as BindState.Error).kind)

        val t = Harness(AssetLookupResult.Terminal(400, "bad"))
        t.coordinator.resolve(validVin)
        assertEquals(BindState.ErrorKind.NETWORK, (t.coordinator.state.value as BindState.Error).kind)
    }

    @Test
    fun `confirm is a no-op when not confirming`() = runBlocking {
        val h = Harness(AssetLookupResult.NotFound)
        h.coordinator.confirm() // from Idle
        assertTrue(h.persisted.isEmpty())
        assertEquals(BindState.Idle, h.coordinator.state.value)
    }
}
