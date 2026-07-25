package com.tagpulse.gateway.core.relay

/**
 * An in-memory [CredentialStore] carrying **only a base URL** — no persisted secrets.
 *
 * Used to point a throwaway [OkHttpBackendClient] at a **candidate** backend origin
 * during enrolment's `POST /devices/provision`, *without* mutating the persisted
 * [KeystoreCredentialStore]. Enrolment only writes the real store on success (atomic
 * `store(deviceId, apiKey, baseUrl)`), so a failed provision never leaves a
 * half-written credential tuple (see `docs/design/enrolment-flow.md`).
 *
 * [deviceId] / [apiKey] are always `null` here — provisioning authenticates with the
 * `X-Provisioning-Key` header, not the ingest credential.
 */
class InMemoryCredentialStore(
    override val baseUrl: String,
) : CredentialStore {
    override val deviceId: String? = null
    override val apiKey: String? = null
}
