package com.tagpulse.gateway.core.relay

import android.util.Log
import com.tagpulse.gateway.core.outbox.Outbox
import com.tagpulse.gateway.core.outbox.OutboxItem
import com.tagpulse.gateway.core.outbox.OutboxJson
import com.tagpulse.gateway.core.outbox.OutboxMapper
import com.tagpulse.gateway.core.outbox.OutboxState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Drains the durable outbox and relays it as batched `POST /tag-reads/batch`
 * (plan §7, milestone M4).
 *
 * One [drain] pass:
 * 1. **[Outbox.purgeExpired] first** — drop rows older than the backend's clock
 *    window so we never ship a guaranteed dead-letter.
 * 2. Take up to [DrainConfig.batchSize] (≤ 500) `PENDING` rows, oldest-first, map
 *    each to a generated `TagReadCreate`, and POST the batch.
 * 3. Apply the outcome:
 *    - **`201`** → the rows go `PENDING → SENT`.
 *    - **Retryable (`5xx`/`408`/`429`/network)** → bump `attempts`, back off (a `429`
 *      `Retry-After` ≤ `maxBackoff` is honored in place of the computed backoff, else
 *      **full-jitter exponential backoff**), retry the same batch; once `attempts` would
 *      reach [DrainConfig.maxAttempts] the rows are parked **FAILED** (surfaced, not
 *      dropped). A `429` `Retry-After` **longer than `maxBackoff`** instead **defers** —
 *      the batch stays **PENDING** (no attempt counted) and the drain stops for a later pass.
 *    - **Terminal (`4xx`, e.g. 400)** → the rows go straight to **FAILED**.
 *    - **`401` credential error** → abort the drain, leaving rows **PENDING** (a
 *      fixed credential re-drains them) — no per-row terminal-fail spam.
 *
 * ### At-least-once (Fix 4, DECIDED)
 * `TagReadCreate` carries no client event id and the backend mints its own row
 * UUID, so a **lost `201`** (the backend committed but the ack never arrived → the
 * client sees a retryable network error) leaves the rows `PENDING`; the next
 * attempt **re-sends and duplicates** them on the backend. This is accepted for the
 * MVE — a repeated PID snapshot is harmless. The drainer deliberately does **not**
 * invent a client idempotency key.
 *
 * @param outbox the durable queue (M3).
 * @param client the backend transport.
 * @param credentials source of the reporting `device_id` (relayed per read).
 * @param config batch size / retry / backoff tuning.
 * @param clock reference time for the age purge (injectable).
 * @param json outbox payload/location codec (matches the store's).
 * @param jitter full-jitter selector — picks a delay in `[0, cap]`. Injectable so
 *   backoff is deterministic under test.
 * @param sleep suspending delay hook (injectable so tests don't wait real time).
 */
class Drainer(
    private val outbox: Outbox,
    private val client: BackendClient,
    private val credentials: CredentialStore,
    private val config: DrainConfig = DrainConfig(),
    private val clock: () -> Instant = Instant::now,
    private val json: OutboxJson = OutboxJson(),
    private val jitter: (Long) -> Long = { cap -> if (cap <= 0L) 0L else Random.nextLong(cap + 1) },
    private val sleep: suspend (Long) -> Unit = { millis -> delay(millis) },
) {

    /**
     * Drain the outbox once: purge stale rows, then relay pending batches until the
     * queue is empty, a batch is terminally rejected past retries, or a credential
     * error aborts the pass. Returns a [DrainReport].
     */
    suspend fun drain(): DrainReport {
        val deviceId = credentials.deviceId
        if (deviceId.isNullOrBlank()) {
            Log.w(TAG, "drain skipped: not enrolled (no device_id)")
            return DrainReport(credentialError = "not enrolled (no device_id)")
        }
        val gatewayUuid = try {
            UUID.fromString(deviceId)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "drain skipped: device_id is not a valid UUID")
            return DrainReport(credentialError = "device_id is not a valid UUID")
        }

        val purged = outbox.purgeExpired(clock())
        var sent = 0
        var rejected = 0
        var failed = 0
        var batches = 0

        while (true) {
            val batch = outbox.pending().take(config.batchSize)
            if (batch.isEmpty()) break

            batches++
            when (val outcome = deliverBatch(batch, gatewayUuid)) {
                is BatchDelivery.Sent -> {
                    sent += batch.size
                    // Surface (don't act on) the backend's clock-rejected count: the
                    // rows still went SENT (no per-row ids to selectively fail, and
                    // purgeExpired pre-drops clock-terminal rows) — plan §7 "keep
                    // rejected for inspection".
                    rejected += outcome.rejected
                }
                is BatchDelivery.Failed -> failed += batch.size
                is BatchDelivery.Deferred ->
                    // Server rate-limited us for longer than we'll block: stop the
                    // drain, leaving the batch (and any later rows) PENDING for a
                    // future pass. Surface the directive; don't fail rows.
                    return DrainReport(
                        sent = sent,
                        rejected = rejected,
                        failed = failed,
                        purged = purged,
                        batches = batches,
                        retryAfterMillis = outcome.retryAfterMillis,
                    )
                is BatchDelivery.CredentialBlocked ->
                    return DrainReport(
                        sent = sent,
                        rejected = rejected,
                        failed = failed,
                        purged = purged,
                        batches = batches,
                        credentialError = outcome.reason,
                    )
            }
        }
        return DrainReport(
            sent = sent,
            rejected = rejected,
            failed = failed,
            purged = purged,
            batches = batches,
        )
    }

    /**
     * Deliver a single [batch], retrying retryable failures with full-jitter
     * exponential backoff until it succeeds, is terminally rejected, exhausts
     * [DrainConfig.maxAttempts], or hits a credential error.
     */
    private suspend fun deliverBatch(batch: List<OutboxItem>, gatewayUuid: UUID): BatchDelivery {
        val reads = batch.map { item ->
            ObservationMapper.toTagReadCreate(OutboxMapper.toObservation(item, json), gatewayUuid)
        }

        return when (val result = client.postTagReadsBatch(reads)) {
            is BatchResult.Accepted -> {
                // At-least-once: on a *lost* 201 we'd never reach here — the rows
                // stay PENDING and re-send later (documented duplicate). Here the
                // ack arrived, so commit SENT.
                for (item in batch) outbox.transition(item.id, OutboxState.SENT, item.attempts)
                Log.i(TAG, "batch of ${batch.size} accepted (ingested=${result.ingested}, rejected=${result.rejected})")
                BatchDelivery.Sent(rejected = result.rejected)
            }

            is BatchResult.Terminal -> {
                for (item in batch) {
                    outbox.transition(item.id, OutboxState.FAILED, item.attempts + 1)
                }
                Log.w(TAG, "batch of ${batch.size} terminally rejected: ${result.reason}")
                BatchDelivery.Failed
            }

            is BatchResult.CredentialError ->
                // Leave PENDING; do not bump attempts or fail rows (plan §7).
                BatchDelivery.CredentialBlocked(result.reason)

            is BatchResult.Retryable -> {
                // A server Retry-After (429) longer than we're willing to block an
                // on-demand drain: defer — leave rows PENDING, do NOT bump attempts or
                // FAIL, and stop the pass so a later drain retries. Clamping it down
                // instead would retry prematurely and eventually FAIL rows the server
                // would still accept (rubber-duck finding).
                val retryAfter = result.retryAfterMillis
                if (retryAfter != null && retryAfter > config.maxBackoff.toMillis()) {
                    Log.i(
                        TAG,
                        "batch deferred: Retry-After ${retryAfter}ms exceeds maxBackoff " +
                            "${config.maxBackoff.toMillis()}ms; leaving ${batch.size} row(s) PENDING",
                    )
                    return BatchDelivery.Deferred(retryAfter)
                }
                // Failures accrued so far for this batch (attempts is persisted, so
                // this survives process restarts / prior drains).
                val failuresSoFar = batch.maxOf { it.attempts }
                val nextAttempts = failuresSoFar + 1
                if (nextAttempts >= config.maxAttempts) {
                    for (item in batch) {
                        outbox.transition(item.id, OutboxState.FAILED, item.attempts + 1)
                    }
                    Log.w(TAG, "batch of ${batch.size} exhausted ${config.maxAttempts} attempts: ${result.reason}")
                    return BatchDelivery.Failed
                }
                // Bump attempts, stay PENDING, back off, then retry the same batch.
                for (item in batch) {
                    outbox.transition(item.id, OutboxState.PENDING, item.attempts + 1)
                }
                // Honor a (≤ maxBackoff) Retry-After exactly; otherwise full-jitter backoff.
                val waitMillis = retryAfter ?: backoffMillis(failuresSoFar)
                Log.i(TAG, "batch retryable (${result.reason}); attempt $nextAttempts/${config.maxAttempts}, backoff ${waitMillis}ms")
                try {
                    sleep(waitMillis)
                } catch (e: CancellationException) {
                    throw e
                }
                // Re-read so the persisted attempt counter drives the next decision.
                val batchIds = batch.mapTo(HashSet()) { it.id }
                val refreshed = outbox.pending().filter { it.id in batchIds }
                if (refreshed.isEmpty()) BatchDelivery.Failed
                else deliverBatch(refreshed, gatewayUuid)
            }
        }
    }

    /**
     * Full-jitter exponential backoff (plan §7): `delay ∈ [0, min(maxBackoff,
     * baseBackoff · 2^failures)]`. [failures] is 0-based (0 = the first retry).
     */
    internal fun backoffMillis(failures: Int): Long {
        val base = config.baseBackoff.toMillis().toDouble()
        val exp = base * 2.0.pow(failures.toDouble())
        val capped = min(exp, config.maxBackoff.toMillis().toDouble()).toLong()
        return jitter(capped)
    }

    private sealed interface BatchDelivery {
        /** Accepted (`201`); [rejected] = the backend's clock-rejected count for the batch. */
        data class Sent(val rejected: Int) : BatchDelivery
        data object Failed : BatchDelivery
        data class CredentialBlocked(val reason: String) : BatchDelivery

        /**
         * Server asked (via `Retry-After` on a `429`) to wait longer than [Drainer]
         * will synchronously block; rows stay `PENDING` (uncounted attempt) and the
         * drain stops so a later pass retries. [retryAfterMillis] is the directive.
         */
        data class Deferred(val retryAfterMillis: Long) : BatchDelivery
    }

    private companion object {
        const val TAG = "Drainer"
    }
}
