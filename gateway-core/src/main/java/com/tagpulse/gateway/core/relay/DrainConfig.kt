package com.tagpulse.gateway.core.relay

import java.time.Duration

/**
 * Tuning for the [Drainer] (plan §7).
 *
 * @property batchSize max rows per `POST /tag-reads/batch`; capped at the
 *   backend's server-side limit of **500** (plan §7 / `ingestion.py`).
 * @property maxAttempts total delivery attempts for a batch before it is parked
 *   [com.tagpulse.gateway.core.outbox.OutboxState.FAILED]. Must be ≥ 1.
 * @property baseBackoff first backoff step; doubles each retry (exponential).
 * @property maxBackoff ceiling for the exponential term (before jitter).
 */
data class DrainConfig(
    val batchSize: Int = SERVER_BATCH_CAP,
    val maxAttempts: Int = 5,
    val baseBackoff: Duration = Duration.ofSeconds(1),
    val maxBackoff: Duration = Duration.ofMinutes(1),
) {
    init {
        require(batchSize in 1..SERVER_BATCH_CAP) {
            "batchSize must be in 1..$SERVER_BATCH_CAP, was $batchSize"
        }
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, was $maxAttempts" }
        require(!baseBackoff.isNegative && !baseBackoff.isZero) { "baseBackoff must be positive" }
        require(maxBackoff >= baseBackoff) { "maxBackoff must be >= baseBackoff" }
    }

    companion object {
        /** Server-side batch cap (`POST /tag-reads/batch`, plan §7). */
        const val SERVER_BATCH_CAP: Int = 500
    }
}

/**
 * Result of a [Drainer.drain] pass.
 *
 * @property sent rows moved `PENDING → SENT` (`201`).
 * @property rejected backend clock-rejected rows across accepted batches
 *   (`201 {rejected}`) — surfaced for inspection; the rows still went `SENT` (plan
 *   §7 "keep rejected for inspection"), and stale rows are pre-dropped by the age purge.
 * @property failed rows parked `PENDING → FAILED` (terminal reject or exhausted retries).
 * @property purged stale rows dropped before draining (age cap).
 * @property batches number of `POST /tag-reads/batch` requests issued.
 * @property credentialError non-null if the drain aborted on a `401` — the
 *   remaining rows stay `PENDING` (not failed) so a fixed credential lets them flow.
 * @property retryAfterMillis non-null if the drain stopped early honoring a server
 *   `Retry-After` (`429`) longer than `maxBackoff` — the batch (and any later rows)
 *   stay `PENDING` for a future pass; no rows are failed.
 */
data class DrainReport(
    val sent: Int = 0,
    val rejected: Int = 0,
    val failed: Int = 0,
    val purged: Int = 0,
    val batches: Int = 0,
    val credentialError: String? = null,
    val retryAfterMillis: Long? = null,
)
