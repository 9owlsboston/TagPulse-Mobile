package com.tagpulse.mobile.scan

import com.tagpulse.gateway.core.relay.DrainReport

/**
 * The observable state of the end-to-end "Scan vehicle" flow (plan
 * `docs/design/obdii-mve-plan.md` §8 M5 — wire the UI action end-to-end). The
 * [ScanCoordinator] exposes this as a `StateFlow` the Compose screen renders.
 *
 * The progression mirrors the pipeline the coordinator composes:
 * `Idle → Connecting → Handshaking → Reading → Relaying → (Done | Error)`.
 * BLE connect + the ELM327 handshake happen inside the driver's single `read()`
 * call, so [Handshaking] / [Reading] bracket that step for operator feedback.
 */
sealed interface ScanState {

    /** Nothing in flight — the initial state and the resting state after a scan. */
    data object Idle : ScanState

    /** Discovering / BLE-connecting the dongle. */
    data object Connecting : ScanState

    /** Running the ELM327 init handshake (`ATZ`/`ATE0`/`ATSP0`). */
    data object Handshaking : ScanState

    /** Requesting the four PIDs and normalizing the snapshot. */
    data object Reading : ScanState

    /** Enqueuing to the durable outbox and draining it to the backend. */
    data object Relaying : ScanState

    /**
     * The slice completed: the snapshot was read, enqueued, and the drain ran.
     *
     * @property pids the decoded PID values (from the snapshot payload) for display.
     * @property hasLocation whether a GPS fix was attached to the read (A6/A7).
     * @property report the relay outcome ([DrainReport]) — sent / rejected / failed.
     */
    data class Done(
        val pids: Map<String, Any?>,
        val hasLocation: Boolean,
        val report: DrainReport,
    ) : ScanState

    /**
     * The slice failed. [kind] classifies the failure so the UI can guide the
     * operator (a credential error asks them to re-enrol / check the key — plan
     * §7, closes ledger `C-5EHY`).
     *
     * @property kind the failure category.
     * @property message an operator-facing message (never contains a secret).
     */
    data class Error(
        val kind: ErrorKind,
        val message: String,
    ) : ScanState

    /** How a [Error] happened — drives the operator's next action. */
    enum class ErrorKind {
        /** No dongle discovered / BLE connect / handshake / PID read failed. */
        DRIVER,

        /** Ingest rejected the credential (`401`) or the device isn't enrolled. */
        CREDENTIAL,

        /** The read enqueued but the drain parked it `FAILED` (retryable exhausted / terminal). */
        RELAY,
    }
}
