package com.tagpulse.mobile.enrol

/**
 * Observable state of the handset↔tenant enrolment flow (ledger `C-RYH7`,
 * Increment 1; `docs/design/enrolment-flow.md`). [EnrolmentCoordinator] exposes this
 * as a `StateFlow` the Compose `EnrolScreen` renders.
 *
 * Progression: `Idle → Provisioning → (Enrolled | Error)`.
 */
sealed interface EnrolState {

    /** Nothing in flight — the initial/resting state. */
    data object Idle : EnrolState

    /** `POST /devices/provision` is in flight against the candidate backend. */
    data object Provisioning : EnrolState

    /**
     * Enrolment succeeded: a `device_id` was issued and all enrolment facts are
     * persisted (Keystore). The device may still be `pending` admin approval — that
     * does not gate ingest for the MVE (ingest uses the tenant `tp_` key; the Map
     * link keys on `tag_id`, not `device_id`).
     *
     * @property deviceId the provisioned device UUID (safe to display).
     * @property status the backend's device status (e.g. `pending`/`active`).
     */
    data class Enrolled(val deviceId: String, val status: String) : EnrolState

    /**
     * Enrolment failed. [kind] guides the operator's next action; [message] is
     * operator-facing and **never contains a secret**.
     */
    data class Error(val kind: ErrorKind, val message: String) : EnrolState

    /** How an [Error] happened. */
    enum class ErrorKind {
        /** A field was blank or the base URL was not a valid `https://` origin. */
        INPUT,

        /** `POST /devices/provision` was rejected (e.g. bad provisioning key) or the network failed. */
        PROVISION,

        /** An unexpected internal failure (e.g. the secure-store write threw). */
        INTERNAL,
    }
}
