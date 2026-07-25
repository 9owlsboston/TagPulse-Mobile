package com.tagpulse.mobile.bind

import android.util.Log
import com.tagpulse.gateway.core.relay.AssetLookupResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Orchestrates the vehicle VIN-bind (ledger `C-RYH7`, Increment 2a;
 * `docs/design/vehicle-bind-flow.md`), exposing an observable [BindState] `StateFlow`.
 * Mirrors `EnrolmentCoordinator`'s discipline (a `Mutex`; every outcome lands in
 * [state]; only cooperative cancellation propagates).
 *
 * [resolve]: canonicalize + hard-validate the VIN (length + alphabet; the ISO-3779
 * check digit is advisory, not enforced) → `GET /assets/by-binding` → require a
 * non-blank **plate** (the operator's confirmation signal) → [BindState.Confirming].
 * [confirm]: persist the binding → [BindState.Bound]. The reads then carry the canonical
 * VIN as `tag_id` (`ScanCoordinator`).
 *
 * @param resolve looks a canonical VIN up against the backend.
 * @param persist writes the confirmed [VehicleBinding] to the store.
 */
class VehicleBindingCoordinator(
    private val resolve: suspend (vin: String) -> AssetLookupResult,
    private val persist: (VehicleBinding) -> Unit,
    private val readVinFromVehicle: (suspend () -> VinReadOutcome)? = null,
) {

    private val _state = MutableStateFlow<BindState>(BindState.Idle)

    /** The observable bind state the UI collects. */
    val state: StateFlow<BindState> = _state.asStateFlow()

    /** Whether the OBD-II VIN auto-read tier is available (a reader is wired). */
    val canReadVin: Boolean get() = readVinFromVehicle != null

    private val lock = Mutex()

    /**
     * Resolve [rawVin] to a vehicle and advance to [BindState.Confirming] (showing the
     * plate) — or an [BindState.Error]. Re-entrant calls are ignored while one is in flight.
     */
    suspend fun resolve(rawVin: String) {
        if (!lock.tryLock()) {
            Log.i(TAG, "resolve ignored: an attempt is already in flight")
            return
        }
        try {
            val vin = Vin.canonical(rawVin)
            if (!Vin.isValid(vin)) {
                _state.value = BindState.Error(
                    BindState.ErrorKind.INPUT,
                    "Enter a valid 17-character VIN (no I, O, or Q).",
                )
                return
            }
            resolveCore(vin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "resolve failed unexpectedly: ${e.javaClass.simpleName}")
            _state.value = BindState.Error(
                BindState.ErrorKind.INTERNAL,
                "Vehicle lookup failed unexpectedly. Please try again.",
            )
        } finally {
            lock.unlock()
        }
    }

    /**
     * Auto-read the VIN over OBD-II (Mode 09) and resolve it (ledger `C-RYH7` §2b) — the
     * zero-touch capture: on a successful read the VIN flows straight into [resolveCore]
     * (→ [BindState.Confirming]); a read failure is an [BindState.ErrorKind.READ]. A no-op
     * if no reader is wired. Re-entrant calls are ignored while one is in flight.
     */
    suspend fun readVin() {
        val reader = readVinFromVehicle ?: return
        if (!lock.tryLock()) {
            Log.i(TAG, "readVin ignored: an attempt is already in flight")
            return
        }
        try {
            _state.value = BindState.Reading
            when (val outcome = reader.invoke()) {
                is VinReadOutcome.Read -> {
                    val vin = Vin.canonical(outcome.vin)
                    if (!Vin.isValid(vin)) {
                        _state.value = BindState.Error(
                            BindState.ErrorKind.READ,
                            "The vehicle reported an unreadable VIN — enter it manually.",
                        )
                    } else {
                        resolveCore(vin)
                    }
                }
                is VinReadOutcome.Failed ->
                    _state.value = BindState.Error(
                        BindState.ErrorKind.READ,
                        "Couldn't read the VIN from the vehicle — enter it manually.",
                    )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "readVin failed unexpectedly: ${e.javaClass.simpleName}")
            _state.value = BindState.Error(
                BindState.ErrorKind.INTERNAL,
                "Reading the VIN failed unexpectedly. Please try again.",
            )
        } finally {
            lock.unlock()
        }
    }

    /**
     * Resolve a **canonical, already-validated** VIN against the backend and set the
     * next [BindState]. Must be called with [lock] held (it never re-locks).
     */
    private suspend fun resolveCore(vin: String) {
        _state.value = BindState.Resolving
        when (val result = resolve.invoke(vin)) {
            is AssetLookupResult.Resolved -> {
                val plate = result.displayLabel
                if (plate.isNullOrBlank()) {
                    // The plate is the operator's confirmation signal — without it we
                    // can't safely confirm the right vehicle.
                    _state.value = BindState.Error(
                        BindState.ErrorKind.NO_PLATE,
                        "This vehicle has no plate on file — ask an admin to set it, then retry.",
                    )
                } else {
                    _state.value = BindState.Confirming(vin, plate, result.assetId)
                }
            }
            is AssetLookupResult.NotFound ->
                _state.value = BindState.Error(
                    BindState.ErrorKind.NOT_FOUND,
                    "No vehicle is registered for that VIN — check the VIN or ask an admin to register it.",
                )
            is AssetLookupResult.CredentialError ->
                _state.value = BindState.Error(
                    BindState.ErrorKind.CREDENTIAL,
                    "The backend rejected the credentials — re-enrol the device / check the API key.",
                )
            is AssetLookupResult.Retryable ->
                _state.value = BindState.Error(
                    BindState.ErrorKind.NETWORK,
                    "Couldn't reach the backend — check connectivity and try again.",
                )
            is AssetLookupResult.Terminal ->
                _state.value = BindState.Error(
                    BindState.ErrorKind.NETWORK,
                    "The VIN lookup failed (${result.statusCode}) — try again.",
                )
        }
    }

    /**
     * Persist the binding for the current [BindState.Confirming] and advance to
     * [BindState.Bound]. A no-op if not currently confirming.
     */
    fun confirm() {
        val confirming = _state.value as? BindState.Confirming ?: return
        persist(VehicleBinding(vin = confirming.vin, plate = confirming.plate, assetId = confirming.assetId))
        Log.i(TAG, "vehicle bound: asset ${confirming.assetId}")
        _state.value = BindState.Bound(confirming.vin, confirming.plate)
    }

    private companion object {
        const val TAG = "VehicleBindingCoordinator"
    }
}
