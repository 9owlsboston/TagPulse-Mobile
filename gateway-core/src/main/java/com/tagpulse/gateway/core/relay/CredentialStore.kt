package com.tagpulse.gateway.core.relay

/**
 * The gateway's enrolment credentials — the three facts the relay needs to reach
 * the backend (plan §5, Fix 1).
 *
 * The MVE authenticates ingest with an **out-of-band tenant user API key**
 * (`tp_{slug}_…`) sent as `Authorization: Bearer <apiKey>` — *not* the `tpd_`
 * device token, which the backend never verifies on the ingest path (plan §5 🚩).
 * The `device_id` is the phone-gateway's provisioned UUID and is relayed as
 * `TagReadCreate.device_id`.
 *
 * ### Security contract (AGENTS §2)
 * [apiKey] is **sensitive** and **tenant-scoped/broad** (no per-device revocation
 * for the MVE — see plan §5 caveat / ledger `I-K6D1`). Implementations MUST keep it
 * in the platform secure store and MUST NOT emit it in `toString()`, logs, or any
 * error message. Read it fresh per request so a rotation takes effect immediately.
 *
 * @property baseUrl the backend origin, e.g. `https://api.tagpulse.example`
 *   (no trailing `/tag-reads/batch` — the client appends the path).
 * @property deviceId the phone-gateway's provisioned device UUID (as a string).
 *   `null` until enrolment (provision → approve) has run.
 * @property apiKey the tenant user API key (`tp_…`) used as the ingest bearer.
 *   `null` until provisioned out-of-band onto the device.
 */
interface CredentialStore {
    val baseUrl: String
    val deviceId: String?
    val apiKey: String?
}

/** True only when both enrolment facts needed to relay are present. */
val CredentialStore.isEnrolled: Boolean
    get() = !deviceId.isNullOrBlank() && !apiKey.isNullOrBlank()
