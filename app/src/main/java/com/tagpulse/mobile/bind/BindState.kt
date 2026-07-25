package com.tagpulse.mobile.bind

/**
 * Observable state of the vehicle VIN-bind flow (ledger `C-RYH7`, Increment 2a;
 * `docs/design/vehicle-bind-flow.md`). [VehicleBindingCoordinator] exposes this as a
 * `StateFlow` the Compose `BindScreen` renders.
 *
 * Progression: `Idle → Resolving → (Confirming → Bound | Error)`.
 */
sealed interface BindState {

    /** Nothing in flight — the initial/resting state. */
    data object Idle : BindState

    /** Reading the VIN from the vehicle over OBD-II (Mode 09). */
    data object Reading : BindState

    /** `GET /assets/by-binding` is in flight. */
    data object Resolving : BindState

    /**
     * The VIN resolved to an asset; the operator must **confirm the plate matches** the
     * vehicle before the binding is persisted.
     *
     * @property vin the canonical VIN.
     * @property plate the resolved plate (`display_label`) — shown for confirmation.
     * @property assetId the resolved asset id.
     */
    data class Confirming(val vin: String, val plate: String, val assetId: String) : BindState

    /** The operator confirmed; the binding is persisted. */
    data class Bound(val vin: String, val plate: String) : BindState

    /**
     * The bind failed. [kind] guides the operator; [message] is operator-facing and
     * never contains a secret.
     */
    data class Error(val kind: ErrorKind, val message: String) : BindState

    /** How an [Error] happened. */
    enum class ErrorKind {
        /** The VIN was not a valid 17-character VIN. */
        INPUT,

        /** `404` — no vehicle is registered for that VIN. */
        NOT_FOUND,

        /** The OBD-II VIN auto-read failed (unsupported / no dongle / link error). */
        READ,

        /** The resolved vehicle has no plate on file — the confirmation signal is missing. */
        NO_PLATE,

        /** `401`/`403` — the ingest credential is missing/invalid/unauthorized. */
        CREDENTIAL,

        /** A transient/terminal transport failure resolving the VIN. */
        NETWORK,

        /** An unexpected internal failure. */
        INTERNAL,
    }
}
