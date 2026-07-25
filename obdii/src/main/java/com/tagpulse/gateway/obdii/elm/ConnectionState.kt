package com.tagpulse.gateway.obdii.elm

/**
 * Observable connection state of an [Elm327Session] (plan
 * `docs/design/obdii-mve-plan.md` §6 — "connection state observable").
 *
 * Exposed as a `StateFlow<ConnectionState>` so the UI (M5) and unit tests can
 * watch the link progress Disconnected → Connecting → Handshaking → Ready →
 * Reading and settle back on [Ready] or land on [Error].
 */
sealed interface ConnectionState {

    /** No GATT link (initial state, or after a clean [Elm327Session.disconnect]). */
    data object Disconnected : ConnectionState

    /** Scanning / establishing the GATT link + enabling notifications. */
    data object Connecting : ConnectionState

    /** Link is up; running the ELM327 AT init handshake. */
    data object Handshaking : ConnectionState

    /** Handshake complete; the adapter is ready to accept PID requests. */
    data object Ready : ConnectionState

    /** A PID request is in flight. */
    data object Reading : ConnectionState

    /**
     * A terminal-for-this-attempt failure. Carries the machine-readable [reason]
     * and a short human message. Failures are surfaced here (not thrown as
     * crashes) so the foreground UX can react (plan §6 — treat `NO DATA` / `?` /
     * `UNABLE TO CONNECT` / timeout as clean failures).
     */
    data class Error(val reason: ObdError, val message: String) : ConnectionState
}

/**
 * Machine-readable failure reasons shared by the pure [PidCodec] decode and the
 * [Elm327Session] transport layer (plan §6 response-parsing row).
 */
enum class ObdError {
    /** ECU answered `NO DATA` — the PID is unsupported by this vehicle right now. */
    NO_DATA,

    /** Response was present but could not be parsed into a valid `41 0C` frame. */
    MALFORMED,

    /** Adapter answered `?` — it did not understand the command. */
    UNSUPPORTED_COMMAND,

    /** Adapter answered `UNABLE TO CONNECT` — no ECU link. */
    UNABLE_TO_CONNECT,

    /** The per-command timeout elapsed before a `>` prompt arrived. */
    TIMEOUT,

    /** The GATT link dropped and could not be re-established. */
    DISCONNECTED,

    /**
     * A non-disconnect transport failure — e.g. the GATT write was rejected or a
     * required characteristic was unresolved (surfaced as a generic `BleException`).
     */
    LINK_ERROR,
}

/**
 * Result of decoding an RPM (`010C`) response — a value or a clean failure.
 *
 * The decode never throws on bad input; malformed / error frames map to
 * [Failure] so a per-PID problem is a value, not a crash (plan §6).
 */
sealed interface RpmReading {

    /** Successfully decoded engine RPM. */
    data class Value(val rpm: Int) : RpmReading

    /** The response could not be decoded into an RPM value. */
    data class Failure(val reason: ObdError) : RpmReading
}
