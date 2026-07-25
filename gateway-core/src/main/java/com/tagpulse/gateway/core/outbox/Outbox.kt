package com.tagpulse.gateway.core.outbox

import android.util.Log
import com.tagpulse.gateway.core.Observation
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * The core-facing durable outbox (plan §3/§7).
 *
 * Every observation — even in the on-demand MVE — is **written through** here and
 * the call returns immediately; a drainer (M4) sends it later. This is the whole
 * reason the core exists and what every future modality reuses.
 *
 * **M3 scope:** enqueue + persist + query + caps. There is **no sender**, so this
 * class never flips a row to `SENT`/`FAILED` — every row it produces is
 * [OutboxState.PENDING] with `attempts = 0`. The drainer, retry/backoff, and the
 * drain-time "drop stale before send" all land in **M4**.
 *
 * @param dao the Room DAO over the file-backed [OutboxDatabase].
 * @param config size + age caps (footprint budget).
 * @param json payload/location JSON codec.
 * @param clock injectable time source (epoch ms via [Instant]); defaults to now.
 */
class Outbox(
    private val dao: OutboxDao,
    private val config: OutboxConfig = OutboxConfig(),
    private val json: OutboxJson = OutboxJson(),
    private val clock: () -> Instant = Instant::now,
) {

    /**
     * Persist [observation] as a new **`PENDING`** row and return its id.
     *
     * Write-through + immediate return (no send). After insert the **size cap** is
     * enforced: if the table now exceeds [OutboxConfig.maxItems], the oldest rows
     * are evicted. Evicting *unsent* rows is deliberate **bounded data-loss
     * protection** — an unbounded local queue is the worse failure — and every
     * eviction is logged.
     */
    suspend fun enqueue(observation: Observation): Long {
        val now = clock().toEpochMilli()
        val id = dao.insert(OutboxMapper.toItem(observation, createdAt = now, json = json))
        enforceSizeCap()
        return id
    }

    /** All pending rows, oldest-first (write-through queue order). */
    suspend fun pending(): List<OutboxItem> = dao.byState(OutboxState.PENDING.name)

    /** Reactive view of the pending rows, oldest-first. */
    fun observePending(): Flow<List<OutboxItem>> = dao.observeByState(OutboxState.PENDING.name)

    /** Total row count across all states. */
    suspend fun count(): Int = dao.count()

    /** Reactive total row count. */
    fun observeCount(): Flow<Int> = dao.observeCount()

    /** Row count in a specific [state]. */
    suspend fun countInState(state: OutboxState): Int = dao.countByState(state.name)

    /**
     * Age cap: drop rows whose capture time predates `now − maxAge` (plan §7).
     *
     * Wired now; the M4 drainer calls it *before* a send to avoid shipping items
     * the backend would dead-letter (>24 h old). Returns the number of rows purged.
     *
     * @param now the reference instant (injected for testability).
     */
    suspend fun purgeExpired(now: Instant = clock()): Int {
        val cutoff = now.toEpochMilli() - config.maxAgeMillis
        val purged = dao.deleteCapturedBefore(cutoff)
        if (purged > 0) {
            Log.i(TAG, "purgeExpired: dropped $purged item(s) older than ${config.maxAge}")
        }
        return purged
    }

    /**
     * Drainer transition (M4): set [item]'s [OutboxState] + attempt count.
     *
     * The single write the M4 drainer uses to move a row off `PENDING` — to
     * [OutboxState.SENT] on a `201`, to [OutboxState.FAILED] on a terminal reject
     * or exhausted retries, or back to `PENDING` with a bumped [attempts] between
     * retryable failures. Delegates to the DAO's `updateStateAndAttempts`. Returns
     * rows affected (0 if the row was meanwhile evicted by a cap). The relay
     * policy (backoff, max-attempts, at-least-once) lives in the drainer, not here.
     */
    suspend fun transition(id: Long, state: OutboxState, attempts: Int): Int =
        dao.updateStateAndAttempts(id, state.name, attempts)

    private suspend fun enforceSizeCap() {
        // Atomic single-statement cap (ledger C-1TQZ): fold count + eviction into
        // one DELETE so a concurrent writer (the M4 drainer) can't slip a row in
        // between a separate count() and delete → over-eviction. Keeps the newest
        // maxItems rows; deletes the rest.
        val evicted = dao.evictToCap(config.maxItems)
        if (evicted > 0) {
            // Bounded data-loss protection: unsent rows may be dropped so the local
            // queue can't grow without bound (footprint budget, plan §7).
            Log.w(
                TAG,
                "size cap ${config.maxItems} exceeded: evicted $evicted oldest unsent item(s)",
            )
        }
    }

    private companion object {
        const val TAG = "Outbox"
    }
}
