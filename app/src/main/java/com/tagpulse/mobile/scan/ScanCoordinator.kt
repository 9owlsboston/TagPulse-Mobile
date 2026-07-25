package com.tagpulse.mobile.scan

import android.util.Log
import com.tagpulse.gateway.core.DiscoveredDevice
import com.tagpulse.gateway.core.GatewayDriver
import com.tagpulse.gateway.core.Observation
import com.tagpulse.gateway.core.outbox.Outbox
import com.tagpulse.gateway.core.relay.DrainReport
import com.tagpulse.gateway.obdii.elm.ConnectionState
import com.tagpulse.mobile.location.LocationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Composes the end-to-end "Scan vehicle" slice (plan `docs/design/obdii-mve-plan.md`
 * §8 M5) and exposes it as an observable [ScanState] `StateFlow` the Compose screen
 * renders.
 *
 * One [scan]:
 * 1. `driver.discover()` → pick the bound dongle (plan §5 — one dongle ↔ one vehicle).
 * 2. `driver.read()` → `driver.normalize()` → an [Observation] (BLE connect + ELM327
 *    handshake + the four-PID read happen inside `read()`; the live link sub-state is
 *    mirrored from [connectionState] for operator feedback).
 * 3. **Attach the GPS fix** from [locationProvider] onto `Observation.location` (§4).
 * 4. `outbox.enqueue()` — durable write-through (§7).
 * 5. `relay.drain()` → reflect the [DrainReport] (sent / failed / **credentialError**)
 *    in the UI. A `credentialError` becomes a [ScanState.Error] of kind
 *    [ScanState.ErrorKind.CREDENTIAL] telling the operator to re-enrol / check the key —
 *    this closes ledger **`C-5EHY`** (surface `DrainReport.credentialError` to the operator).
 *
 * Modality-agnostic: it depends only on the core [GatewayDriver] seam plus app-level
 * [LocationProvider] / [Relay] abstractions, so it is fully unit-testable with fakes
 * (the real BLE / GPS / Keystore / HTTP paths are HIL). [connectionState] is an
 * optional live-link mirror (the OBD-II driver exposes it); when absent the coordinator
 * still emits a baseline [ScanState.Reading] around the read.
 *
 * @param driver the modality driver (OBD-II for the MVE).
 * @param locationProvider one-shot GPS fix source.
 * @param outbox the durable queue.
 * @param relay drains the outbox and reports the outcome.
 * @param connectionState optional live driver link state, mirrored to the UI during read.
 */
class ScanCoordinator(
    private val driver: GatewayDriver,
    private val locationProvider: LocationProvider,
    private val outbox: Outbox,
    private val relay: Relay,
    private val connectionState: StateFlow<ConnectionState>? = null,
) {

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)

    /** The observable scan state the UI collects. */
    val state: StateFlow<ScanState> = _state.asStateFlow()

    // Serialize scans: a second tap while one is in flight is ignored (not queued).
    private val scanLock = Mutex()

    /**
     * Run the full slice once. Re-entrant taps are ignored while a scan is in flight.
     * Never throws for an expected failure — every outcome lands in [state] as
     * [ScanState.Done] or [ScanState.Error]; only cooperative cancellation propagates.
     */
    suspend fun scan(): Unit = coroutineScope {
        if (!scanLock.tryLock()) {
            Log.i(TAG, "scan ignored: a scan is already in flight")
            return@coroutineScope
        }
        try {
            _state.value = ScanState.Connecting

            val device = discoverOrFail() ?: return@coroutineScope

            // Mirror the driver's live link state (Connecting→Handshaking→Reading)
            // onto the UI during the atomic read(); baseline to Reading if the driver
            // exposes no state (e.g. a fake in tests).
            val mirror = connectionState?.let { link ->
                launch { link.collect { cs -> mapLink(cs)?.let { _state.value = it } } }
            }
            if (mirror == null) _state.value = ScanState.Reading

            val observation = readOrFail(device, mirror) ?: return@coroutineScope
            mirror?.cancelAndJoin()

            // Attach the one-shot GPS fix (plan §4). A missing fix is not an error —
            // the read still relays; the mapper renders a present fix to Location(source=gps).
            val fix = currentFixOrNull()
            val located = observation.copy(location = fix)
            outbox.enqueue(located)

            _state.value = ScanState.Relaying
            val report = relay.drain()
            _state.value = resultOf(report, located, hasLocation = fix != null)
        } finally {
            scanLock.unlock()
        }
    }

    /** Reset back to [ScanState.Idle] (e.g. after the operator dismisses a result). */
    fun reset() {
        _state.value = ScanState.Idle
    }

    private suspend fun discoverOrFail(): DiscoveredDevice? {
        val devices = try {
            driver.discover()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = ScanState.Error(ScanState.ErrorKind.DRIVER, driverMessage(e))
            return null
        }
        val device = devices.firstOrNull()
        if (device == null) {
            _state.value = ScanState.Error(
                ScanState.ErrorKind.DRIVER,
                "No OBD-II dongle found — check the dongle is plugged in and paired.",
            )
        }
        return device
    }

    private suspend fun readOrFail(
        device: DiscoveredDevice,
        mirror: kotlinx.coroutines.Job?,
    ): Observation? = try {
        driver.normalize(driver.read(device))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        mirror?.cancelAndJoin()
        _state.value = ScanState.Error(ScanState.ErrorKind.DRIVER, driverMessage(e))
        null
    }

    private suspend fun currentFixOrNull() = try {
        locationProvider.currentFix()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "GPS fix unavailable; relaying without a location")
        null
    }

    private fun resultOf(
        report: DrainReport,
        observation: Observation,
        hasLocation: Boolean,
    ): ScanState = when {
        // Closes C-5EHY: surface the credential problem to the operator (the read
        // stays PENDING in the outbox, ready to re-drain once the key is fixed).
        report.credentialError != null -> ScanState.Error(
            ScanState.ErrorKind.CREDENTIAL,
            "Ingest rejected the credentials — re-enrol the device / check the API key, then scan again.",
        )
        report.failed > 0 -> ScanState.Error(
            ScanState.ErrorKind.RELAY,
            "Relay failed: ${report.failed} read(s) could not be delivered — they stay queued for retry.",
        )
        else -> ScanState.Done(
            pids = pidsOf(observation.payload),
            hasLocation = hasLocation,
            report = report,
        )
    }

    private fun mapLink(cs: ConnectionState): ScanState? = when (cs) {
        ConnectionState.Connecting -> ScanState.Connecting
        ConnectionState.Handshaking -> ScanState.Handshaking
        ConnectionState.Reading -> ScanState.Reading
        // Disconnected / Ready / Error are not UI-driving here: the read() outcome
        // (success or thrown Elm327Exception) is the authority for the scan result.
        else -> null
    }

    /** Extract the decoded PID sub-map from the snapshot payload for display. */
    private fun pidsOf(payload: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return (payload["pids"] as? Map<String, Any?>) ?: emptyMap()
    }

    /** A short, secret-free operator message for a driver failure. */
    private fun driverMessage(e: Exception): String =
        "Could not read the dongle: ${e.message ?: e.javaClass.simpleName}"

    private companion object {
        const val TAG = "ScanCoordinator"
    }
}
