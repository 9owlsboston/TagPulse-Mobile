package com.tagpulse.mobile.bind

import com.tagpulse.gateway.core.relay.AssetLookupResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private class Harness(
        private val result: AssetLookupResult,
        private val vinRead: VinReadOutcome? = null,
    ) {
        var resolveCalls = 0; private set
        var lastResolveArg: String? = null; private set
        val persisted = mutableListOf<VehicleBinding>()

        val coordinator = VehicleBindingCoordinator(
            resolve = { vin -> resolveCalls++; lastResolveArg = vin; result },
            persist = { persisted += it },
            readVinFromVehicle = vinRead?.let { outcome -> { outcome } },
        )
    }

    @Test
    fun `resolve then confirm persists the binding and reports Bound`() = runBlocking {
        val h = Harness(AssetLookupResult.Resolved(assetId = "asset-9", displayLabel = "MASS-1234", bindingKind = "device"))

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
    fun `a device binding resolves with no warning`() = runBlocking {
        val h = Harness(AssetLookupResult.Resolved("asset-9", "MASS-1234", bindingKind = "device"))
        h.coordinator.resolve(validVin)
        val state = h.coordinator.state.value as BindState.Confirming
        assertNull(state.warning)
    }

    @Test
    fun `a lookup-only vin binding resolves to Confirming WITH a warning`() = runBlocking {
        val h = Harness(AssetLookupResult.Resolved("asset-9", "MASS-1234", bindingKind = "vin"))
        h.coordinator.resolve(validVin)
        val state = h.coordinator.state.value as BindState.Confirming
        assertNotNull(state.warning)
        // Still Confirming (warn, not block) — the operator can proceed.
        assertEquals("MASS-1234", state.plate)
    }

    @Test
    fun `a non-device binding kind (or null) warns`() = runBlocking {
        for (kind in listOf("epc", "tid", null)) {
            val h = Harness(AssetLookupResult.Resolved("a", "P", bindingKind = kind))
            h.coordinator.resolve(validVin)
            assertNotNull("kind=$kind should warn", (h.coordinator.state.value as BindState.Confirming).warning)
        }
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
        val h = Harness(AssetLookupResult.Resolved(assetId = "asset-9", displayLabel = null, bindingKind = "device"))
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

    @Test
    fun `readVin success resolves the read VIN to Confirming`() = runBlocking {
        val h = Harness(
            result = AssetLookupResult.Resolved(assetId = "asset-9", displayLabel = "MASS-1234", bindingKind = "device"),
            vinRead = VinReadOutcome.Read("1hgcm82633a004352"),
        )
        assertTrue(h.coordinator.canReadVin)

        h.coordinator.readVin()

        // The read VIN was canonicalized + resolved, landing in Confirming with the plate.
        assertEquals(validVin, h.lastResolveArg)
        assertEquals(BindState.Confirming(validVin, "MASS-1234", "asset-9"), h.coordinator.state.value)
    }

    @Test
    fun `readVin failure is a READ error and does not resolve`() = runBlocking {
        val h = Harness(
            result = AssetLookupResult.NotFound,
            vinRead = VinReadOutcome.Failed("unsupported"),
        )
        h.coordinator.readVin()
        assertEquals(0, h.resolveCalls)
        assertEquals(BindState.ErrorKind.READ, (h.coordinator.state.value as BindState.Error).kind)
    }

    @Test
    fun `canReadVin is false and readVin is a no-op when no reader is wired`() = runBlocking {
        val h = Harness(AssetLookupResult.NotFound) // no vinRead
        assertFalse(h.coordinator.canReadVin)
        h.coordinator.readVin()
        assertEquals(BindState.Idle, h.coordinator.state.value)
    }
}
