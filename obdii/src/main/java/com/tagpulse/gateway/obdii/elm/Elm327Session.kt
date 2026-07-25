package com.tagpulse.gateway.obdii.elm

import com.tagpulse.gateway.obdii.ble.BleDisconnectedException
import com.tagpulse.gateway.obdii.ble.BleException
import com.tagpulse.gateway.obdii.ble.BleTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * An ELM327 command session layered on a [BleTransport] (plan
 * `docs/design/obdii-mve-plan.md` §6).
 *
 * Responsibilities:
 * - Run the AT init handshake `ATZ → ATE0 → ATL0 → ATS0 → ATSP0`.
 * - Request **RPM (`010C`) only** (M1 scope — the other three PIDs are M2).
 * - Reassemble notification fragments up to the `>` prompt, then decode via the
 *   pure [PidCodec].
 * - Expose an observable [state] and treat `NO DATA` / `?` / timeout / disconnect
 *   as clean failures (never crashes).
 *
 * Everything I/O here goes through the injected [transport], so this class is
 * fully unit-testable with a scriptable fake — no hardware.
 *
 * @param transport the BLE link (real on device, fake in tests).
 * @param commandTimeoutMs per-command deadline for the `>` prompt (plan §6: 2–5 s).
 * @param maxRetries bounded retry for a timed-out command / one reconnect on a
 *   dropped link (plan §6).
 * @param logger sink for the successful RPM value (plan M1: "log the value").
 */
class Elm327Session(
    private val transport: BleTransport,
    private val commandTimeoutMs: Long = 4_000,
    private val maxRetries: Int = 1,
    private val logger: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    /** Observable connection state for the UI (M5) and tests (plan §6). */
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /**
     * Connect the dongle and run the ELM327 init handshake, leaving the session
     * [ConnectionState.Ready].
     *
     * @throws Elm327Exception if the link or handshake fails (state is left
     *   [ConnectionState.Error]).
     */
    suspend fun connect() {
        _state.value = ConnectionState.Connecting
        try {
            transport.connect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(ObdError.DISCONNECTED, "connect failed: ${e.message}")
            throw Elm327Exception("BLE connect failed", e)
        }

        _state.value = ConnectionState.Handshaking
        try {
            for (command in HANDSHAKE) {
                exchange(command)
            }
        } catch (e: TimeoutCancellationException) {
            fail(ObdError.TIMEOUT, "handshake timed out")
            throw Elm327Exception("handshake timed out", e)
        } catch (e: BleDisconnectedException) {
            fail(ObdError.DISCONNECTED, "link dropped during handshake")
            throw Elm327Exception("link dropped during handshake", e)
        } catch (e: BleException) {
            // Any other transport-level failure (e.g. a rejected write) must still
            // land the session on Error — never leak uncaught, leaving Handshaking.
            fail(ObdError.LINK_ERROR, "handshake failed: ${e.message}")
            throw Elm327Exception("handshake failed", e)
        }
        _state.value = ConnectionState.Ready
    }

    /**
     * Request engine RPM (`010C`) once. Returns a value or a clean [RpmReading].
     * On success the value is logged and the state returns to
     * [ConnectionState.Ready]; on failure the state becomes [ConnectionState.Error].
     * Never throws for a per-command problem.
     */
    suspend fun readRpm(): RpmReading {
        _state.value = ConnectionState.Reading
        val reading = requestRpm(retriesLeft = maxRetries)
        when (reading) {
            is RpmReading.Value -> {
                logger("OBD-II RPM = ${reading.rpm}")
                _state.value = ConnectionState.Ready
            }
            is RpmReading.Failure -> fail(reading.reason, "RPM read failed: ${reading.reason}")
        }
        return reading
    }

    /** Tear down the link and reset to [ConnectionState.Disconnected]. */
    suspend fun disconnect() {
        transport.disconnect()
        _state.value = ConnectionState.Disconnected
    }

    private suspend fun requestRpm(retriesLeft: Int): RpmReading =
        try {
            PidCodec.decodeRpm(exchange(PID_RPM))
        } catch (e: TimeoutCancellationException) {
            if (retriesLeft > 0) requestRpm(retriesLeft - 1) else RpmReading.Failure(ObdError.TIMEOUT)
        } catch (e: BleDisconnectedException) {
            if (retriesLeft > 0 && reconnect()) {
                requestRpm(retriesLeft - 1)
            } else {
                RpmReading.Failure(ObdError.DISCONNECTED)
            }
        } catch (e: BleException) {
            // A non-disconnect transport failure (e.g. a rejected/unsupported write)
            // is a clean per-command failure — readRpm() must NOT throw. Not retried:
            // a rejected write is typically persistent, not transient.
            RpmReading.Failure(ObdError.LINK_ERROR)
        }

    /** Single reconnect + re-handshake attempt (plan §6). */
    private suspend fun reconnect(): Boolean = try {
        transport.connect()
        for (command in HANDSHAKE) exchange(command)
        true
    } catch (e: CancellationException) {
        // Never swallow structured-concurrency cancellation.
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * Write one command and reassemble notification fragments until the `>`
     * prompt, subject to [commandTimeoutMs]. Subscribes to notifications *before*
     * writing (UNDISPATCHED) so no response fragment is missed.
     */
    private suspend fun exchange(command: String): String = coroutineScope {
        val response = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(commandTimeoutMs) {
                val buffer = StringBuilder()
                transport.notifications.first { fragment ->
                    buffer.append(String(fragment, Charsets.US_ASCII))
                    buffer.contains(PROMPT)
                }
                buffer.toString()
            }
        }
        transport.write((command + TERMINATOR).toByteArray(Charsets.US_ASCII))
        response.await()
    }

    private fun fail(reason: ObdError, message: String) {
        _state.value = ConnectionState.Error(reason, message)
    }

    companion object {
        /** ELM327 PID request for engine RPM (mode 01, PID 0C). */
        const val PID_RPM = "010C"

        /** Command terminator ELM327 expects (carriage return). */
        private const val TERMINATOR = "\r"

        /** Response prompt that terminates every ELM327 reply. */
        private const val PROMPT = '>'

        /**
         * The init handshake (plan §6): reset → echo off → linefeeds off →
         * spaces off → auto protocol.
         */
        val HANDSHAKE: List<String> = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0")
    }
}

/** Raised when the ELM327 link or handshake fails (as opposed to a per-PID failure). */
class Elm327Exception(message: String, cause: Throwable? = null) : Exception(message, cause)
