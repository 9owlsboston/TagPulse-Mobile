package com.tagpulse.gateway.obdii.elm

import com.tagpulse.gateway.obdii.ObdSnapshot
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
import java.time.Instant

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

    /**
     * Read the vehicle **VIN** via Mode 09 PID 02 (`0902`) once (ledger `C-RYH7`
     * Increment 2b). Returns the 17-character VIN or a clean [VinReading]. On success
     * the VIN is logged and the state returns to [ConnectionState.Ready]; on failure
     * the state becomes [ConnectionState.Error]. Never throws for a per-command problem.
     *
     * The session must already be [ConnectionState.Ready] (call [connect] first).
     * Scoped to CAN vehicles (see [PidCodec.decodeVin]); an unsupported/legacy response
     * decodes to a [VinReading.Failure] so the caller can fall back to manual VIN entry.
     */
    suspend fun readVin(): VinReading {
        _state.value = ConnectionState.Reading
        val reading = requestVin(retriesLeft = maxRetries)
        when (reading) {
            is VinReading.Value -> {
                logger("OBD-II VIN = ${reading.vin}")
                _state.value = ConnectionState.Ready
            }
            is VinReading.Failure -> fail(reading.reason, "VIN read failed: ${reading.reason}")
        }
        return reading
    }

    private suspend fun requestVin(retriesLeft: Int): VinReading =
        try {
            PidCodec.decodeVin(exchange(PID_VIN))
        } catch (e: TimeoutCancellationException) {
            if (retriesLeft > 0) requestVin(retriesLeft - 1) else VinReading.Failure(ObdError.TIMEOUT)
        } catch (e: BleDisconnectedException) {
            if (retriesLeft > 0 && reconnect()) {
                requestVin(retriesLeft - 1)
            } else {
                VinReading.Failure(ObdError.DISCONNECTED)
            }
        } catch (e: BleException) {
            VinReading.Failure(ObdError.LINK_ERROR)
        }

    /**
     * Read the full four-PID snapshot (`010C`, `010D`, `0105`, `012F`) in sequence
     * and assemble an [ObdSnapshot] (plan §4). The session must already be
     * [ConnectionState.Ready] (call [connect] first).
     *
     * **Graceful per-PID failure:** each PID is requested and decoded independently;
     * a PID that returns `NO DATA` / an error / a malformed frame simply leaves that
     * field null in the snapshot — the read does **not** fail wholesale, and the
     * other PIDs still land. The session settles back on [ConnectionState.Ready]
     * regardless of partial failures (plan §4/§6).
     *
     * @param includeRaw whether to retain the raw ELM327 frames in the snapshot
     *   (debug-gated; dropped by default to stay within footprint — plan §4).
     * @param dongle best-effort adapter metadata to stamp on the snapshot.
     * @param capturedAt capture time for the snapshot (injectable for tests).
     */
    suspend fun readSnapshot(
        includeRaw: Boolean = false,
        dongle: ObdSnapshot.DongleInfo? = null,
        capturedAt: Instant = Instant.now(),
    ): ObdSnapshot {
        _state.value = ConnectionState.Reading
        val raw = LinkedHashMap<String, String>()

        val rpm = readPid(PID_RPM, raw) { PidCodec.decodeRpm(it).asPidReading() }
        val speed = readPid(PID_SPEED, raw) { PidCodec.decodeSpeed(it) }
        val coolant = readPid(PID_COOLANT, raw) { PidCodec.decodeCoolantTemp(it) }
        val fuel = readPid(PID_FUEL, raw) { PidCodec.decodeFuelLevel(it) }

        val snapshot = ObdSnapshot(
            capturedAt = capturedAt,
            rpm = rpm.valueOrNull(),
            speedKph = speed.valueOrNull(),
            coolantTempC = coolant.valueOrNull(),
            fuelLevelPct = fuel.valueOrNull(),
            dongle = dongle,
            rawFrames = raw,
            includeRaw = includeRaw,
        )
        logger("OBD-II snapshot: ${snapshot.toPayload()["pids"]}")
        _state.value = ConnectionState.Ready
        return snapshot
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

    /**
     * Request one PID, capture its raw frame, and decode it — the generic
     * per-PID read used by [readSnapshot]. Mirrors [requestRpm]'s bounded
     * retry / single-reconnect handling, but returns a typed [PidReading] so a
     * per-PID failure is a value (null field in the snapshot), never a throw.
     */
    private suspend fun <T> readPid(
        command: String,
        raw: MutableMap<String, String>,
        retriesLeft: Int = maxRetries,
        decode: (String) -> PidReading<T>,
    ): PidReading<T> =
        try {
            val response = exchange(command)
            raw[command] = cleanFrame(response)
            decode(response)
        } catch (e: TimeoutCancellationException) {
            if (retriesLeft > 0) readPid(command, raw, retriesLeft - 1, decode)
            else PidReading.Failure(ObdError.TIMEOUT)
        } catch (e: BleDisconnectedException) {
            if (retriesLeft > 0 && reconnect()) readPid(command, raw, retriesLeft - 1, decode)
            else PidReading.Failure(ObdError.DISCONNECTED)
        } catch (e: BleException) {
            PidReading.Failure(ObdError.LINK_ERROR)
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

    /** Collapse a raw ELM327 response to a single clean line for the `raw` debug block. */
    private fun cleanFrame(response: String): String =
        response
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(">", " ")
            .trim()
            .replace(WHITESPACE, " ")

    companion object {
        /** ELM327 PID request for engine RPM (mode 01, PID 0C). */
        const val PID_RPM = "010C"

        /** ELM327 PID request for vehicle speed (mode 01, PID 0D). */
        const val PID_SPEED = "010D"

        /** ELM327 PID request for coolant temperature (mode 01, PID 05). */
        const val PID_COOLANT = "0105"

        /** ELM327 PID request for fuel level (mode 01, PID 2F). */
        const val PID_FUEL = "012F"

        /** ELM327 request for the vehicle VIN (mode 09, PID 02). */
        const val PID_VIN = "0902"

        /** Command terminator ELM327 expects (carriage return). */
        private const val TERMINATOR = "\r"

        /** Response prompt that terminates every ELM327 reply. */
        private const val PROMPT = '>'

        private val WHITESPACE = Regex("\\s+")

        /**
         * The init handshake (plan §6): reset → echo off → linefeeds off →
         * spaces off → auto protocol.
         */
        val HANDSHAKE: List<String> = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0")
    }
}

/** Adapt the M1 [RpmReading] into the parametric [PidReading] used by snapshot decode. */
private fun RpmReading.asPidReading(): PidReading<Int> = when (this) {
    is RpmReading.Value -> PidReading.Value(rpm)
    is RpmReading.Failure -> PidReading.Failure(reason)
}

/** The decoded value, or null on a per-PID failure (drops the field from the snapshot). */
private fun <T> PidReading<T>.valueOrNull(): T? = (this as? PidReading.Value<T>)?.value

/** Raised when the ELM327 link or handshake fails (as opposed to a per-PID failure). */
class Elm327Exception(message: String, cause: Throwable? = null) : Exception(message, cause)
