package com.tagpulse.gateway.core.relay

import com.tagpulse.gateway.core.api.model.TagReadCreate

/**
 * The gateway's northbound transport to the TagPulse backend (plan §3/§7).
 *
 * A **thin wrapper over the generated contract models** — it (de)serializes the
 * generated [TagReadCreate] and never hand-writes a request/response model
 * (AGENTS §2; see `contract/CONTRACT.md` "Transport decision" for why a thin
 * OkHttp transport is used over generating the full OpenAPI api-client).
 *
 * Both calls return **typed outcomes** rather than throwing, so the drainer can
 * classify failures (retryable vs terminal vs credential) without exception
 * plumbing.
 */
interface BackendClient {

    /**
     * Relay a batch to `POST {baseUrl}/tag-reads/batch` with
     * `Authorization: Bearer <apiKey>` (plan §5). Maps the HTTP result to a
     * [BatchResult]:
     * - `201 {ingested, rejected}` → [BatchResult.Accepted]
     * - `5xx` / `408` / `429` / network / timeout → [BatchResult.Retryable]
     *   (a `429` carries the server's `Retry-After` as `retryAfterMillis`)
     * - `401` → [BatchResult.CredentialError] (a credential problem, surfaced once —
     *   not per-item terminal-fail spam)
     * - other `4xx` → [BatchResult.Terminal]
     */
    suspend fun postTagReadsBatch(reads: List<TagReadCreate>): BatchResult

    /**
     * Enrolment call: `POST {baseUrl}/devices/provision` with the
     * `X-Provisioning-Key` header (plan §5). Returns the pending `device_id`.
     * Admin **approval** (`POST /device-registry/{id}/approve`) is out-of-band and
     * deliberately **not** automated here.
     *
     * [deviceType] defaults to `"mobile_gateway"` — the MVE's phone-gateway device
     * type. The backend `device_type` is a **free-form `str` (≤50 chars, no enum)**
     * (`~/ws/TagPulse` `schemas.py:142`), so it stays a caller-configurable parameter.
     */
    suspend fun provisionDevice(
        provisioningKey: String,
        name: String,
        deviceType: String = "mobile_gateway",
    ): ProvisionResult

    /**
     * Resolve a vehicle **binding value** (a canonical VIN) to its asset via
     * `GET {baseUrl}/assets/by-binding?value=<vin>` with `Authorization: Bearer <tp_ key>`
     * (ledger `C-RYH7` Increment 2a; backend `I-P923`). The response's `display_label` is
     * the vehicle's **plate**, shown to the operator for confirmation; the caller keys the
     * binding on the canonical VIN it sends as `tag_id`.
     *
     * Maps the HTTP result to an [AssetLookupResult]:
     * - `200 {id, display_label}` → [AssetLookupResult.Resolved]
     * - `404` → [AssetLookupResult.NotFound] (unknown VIN — **not** retryable)
     * - `401`/`403` → [AssetLookupResult.CredentialError]
     * - `5xx`/`408`/`429`/network → [AssetLookupResult.Retryable]
     * - other `4xx` → [AssetLookupResult.Terminal]
     */
    suspend fun resolveAssetByBinding(value: String): AssetLookupResult
}

/** Outcome of a `POST /tag-reads/batch` relay. */
sealed interface BatchResult {
    /** `201` — the backend accepted [ingested] rows and clock-rejected [rejected]. */
    data class Accepted(val ingested: Int, val rejected: Int) : BatchResult

    /**
     * Transient (`5xx` / `408` / `429` / network / timeout) — safe to retry with
     * backoff. [retryAfterMillis], when present, is the server's `Retry-After`
     * directive (delta-seconds form) on a `429` — the drainer honors it in place of
     * its computed backoff.
     */
    data class Retryable(val reason: String, val retryAfterMillis: Long? = null) : BatchResult

    /** Non-retryable client error (e.g. `400` bad payload) — park the rows FAILED. */
    data class Terminal(val statusCode: Int, val reason: String) : BatchResult

    /** `401` — the ingest credential is missing/invalid; surface, don't fail rows. */
    data class CredentialError(val reason: String) : BatchResult
}

/** Outcome of a `POST /devices/provision` enrolment call. */
sealed interface ProvisionResult {
    /** `201` — device self-registered; awaiting admin approval. */
    data class Registered(val deviceId: String, val status: String) : ProvisionResult

    /** Any non-2xx (e.g. `401` invalid provisioning key) or transport failure. */
    data class Failed(val statusCode: Int?, val reason: String) : ProvisionResult
}

/** Outcome of a `GET /assets/by-binding` VIN→asset lookup (Increment 2a). */
sealed interface AssetLookupResult {
    /**
     * `200` — the binding resolved to an asset. [displayLabel] is the vehicle's plate
     * (`display_label`), possibly `null` if the backend has none on file.
     */
    data class Resolved(val assetId: String, val displayLabel: String?) : AssetLookupResult

    /** `404` — no active binding for that value (unknown VIN). Not retryable. */
    data object NotFound : AssetLookupResult

    /** `401`/`403` — the ingest credential is missing/invalid/unauthorized. */
    data class CredentialError(val reason: String) : AssetLookupResult

    /** Transient (`5xx`/`408`/`429`/network) — safe to retry. */
    data class Retryable(val reason: String) : AssetLookupResult

    /** Non-retryable client error (other `4xx`, or an unparseable `200`). */
    data class Terminal(val statusCode: Int, val reason: String) : AssetLookupResult
}
