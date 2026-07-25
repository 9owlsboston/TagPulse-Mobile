package com.tagpulse.gateway.core.outbox

/**
 * Lifecycle of an [OutboxItem] row.
 *
 * The enum spans the **full** send lifecycle so the schema is stable across
 * milestones, but **M3 only ever produces [PENDING]**. The [SENT]/[FAILED]
 * transitions — and the drainer that performs them — are **M4** (plan §7/§8).
 * Nothing in M3 sends, so nothing in M3 flips a row off [PENDING].
 */
enum class OutboxState {
    /** Written through, not yet relayed. The only state M3 emits. */
    PENDING,

    /** Accepted by the backend (`ingested`). Set by the M4 drainer. */
    SENT,

    /** Exhausted retries / rejected. Parked for inspection by the M4 drainer. */
    FAILED,
}
