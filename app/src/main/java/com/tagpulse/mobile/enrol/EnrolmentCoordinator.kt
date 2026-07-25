package com.tagpulse.mobile.enrol

import android.util.Log
import com.tagpulse.gateway.core.relay.ProvisionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Orchestrates the handset↔tenant enrolment (ledger `C-RYH7`, Increment 1;
 * `docs/design/enrolment-flow.md`), exposing an observable [EnrolState] `StateFlow`
 * the Compose `EnrolScreen` renders. Mirrors `ScanCoordinator`'s discipline (a
 * `Mutex`; every outcome lands in [state]; only cooperative cancellation propagates).
 *
 * One [enrol]:
 * 1. **Validate** the input — non-blank fields and a well-formed **`https://`**
 *    `baseUrl` (keys must never travel in plaintext) → else [EnrolState.Error] of
 *    kind [EnrolState.ErrorKind.INPUT], no network.
 * 2. **[provision]** against the *candidate* `baseUrl` (an ephemeral client — the
 *    persisted store is not touched yet).
 * 3. On [ProvisionResult.Registered] → **atomically** [persist] `{deviceId, apiKey,
 *    baseUrl}` (one secure-store write) → [EnrolState.Enrolled]. On
 *    [ProvisionResult.Failed] → [EnrolState.Error] of kind `PROVISION`, **nothing
 *    persisted** (no half-enrolled tuple).
 *
 * Both collaborators are plain seams so the coordinator is fully JVM-unit-testable
 * without a device (the real provision HTTP call + Keystore write are HIL).
 *
 * @param provision runs `POST /devices/provision` against the given candidate
 *   `baseUrl` with the `provisioningKey` + device `name`.
 * @param persist atomically writes the enrolment tuple to the secure store (wired to
 *   `KeystoreCredentialStore.store`).
 */
class EnrolmentCoordinator(
    private val provision: suspend (baseUrl: String, provisioningKey: String, name: String) -> ProvisionResult,
    private val persist: (deviceId: String, apiKey: String, baseUrl: String) -> Unit,
) {

    private val _state = MutableStateFlow<EnrolState>(EnrolState.Idle)

    /** The observable enrolment state the UI collects. */
    val state: StateFlow<EnrolState> = _state.asStateFlow()

    // Serialize attempts: a second tap while one is in flight is ignored (not queued).
    private val enrolLock = Mutex()

    /**
     * Run one enrolment attempt. Re-entrant calls are ignored while one is in flight.
     * Never throws for an expected failure — every outcome lands in [state]; only
     * cooperative cancellation propagates.
     */
    suspend fun enrol(input: EnrolmentInput) {
        if (!enrolLock.tryLock()) {
            Log.i(TAG, "enrol ignored: an attempt is already in flight")
            return
        }
        try {
            val baseUrl = validate(input) ?: return
            _state.value = EnrolState.Provisioning

            when (val result = provision(baseUrl, input.provisioningKey.trim(), input.deviceName.trim())) {
                is ProvisionResult.Registered -> {
                    // Atomic, success-only persist — nothing is written on failure.
                    persist(result.deviceId, input.ingestApiKey.trim(), baseUrl)
                    Log.i(TAG, "enrolled device ${result.deviceId} (status=${result.status})")
                    _state.value = EnrolState.Enrolled(result.deviceId, result.status)
                }
                is ProvisionResult.Failed -> {
                    // Do NOT echo the server body verbatim (avoid leaking anything it
                    // might reflect) — a fixed, safe message + the status code.
                    val code = result.statusCode?.let { " ($it)" } ?: ""
                    Log.w(TAG, "provision failed$code")
                    _state.value = EnrolState.Error(
                        EnrolState.ErrorKind.PROVISION,
                        "Provisioning was rejected$code — check the backend URL and provisioning key, then try again.",
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Never surface a secret; log only the exception type.
            Log.w(TAG, "enrol failed unexpectedly: ${e.javaClass.simpleName}")
            _state.value = EnrolState.Error(
                EnrolState.ErrorKind.INTERNAL,
                "Enrolment failed unexpectedly. Please try again.",
            )
        } finally {
            enrolLock.unlock()
        }
    }

    /**
     * Returns the normalized `https` base URL, or emits an `INPUT` error and returns
     * `null`. Requires all fields present and `baseUrl` a valid HTTPS origin.
     */
    private fun validate(input: EnrolmentInput): String? {
        if (input.provisioningKey.isBlank() || input.ingestApiKey.isBlank() || input.deviceName.isBlank()) {
            _state.value = EnrolState.Error(
                EnrolState.ErrorKind.INPUT,
                "Fill in the backend URL, provisioning key, ingest key, and device name.",
            )
            return null
        }
        val url = input.baseUrl.trim().toHttpUrlOrNull()
        if (url == null || url.scheme != "https") {
            _state.value = EnrolState.Error(
                EnrolState.ErrorKind.INPUT,
                "The backend URL must be a valid https:// address.",
            )
            return null
        }
        // Normalized origin (scheme://host[:port]); the client appends the path.
        return url.newBuilder().encodedPath("/").build().toString().trimEnd('/')
    }

    private companion object {
        const val TAG = "EnrolmentCoordinator"
    }
}
