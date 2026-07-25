package com.tagpulse.gateway.core.outbox

import java.time.Duration

/**
 * Footprint caps for the outbox (plan §7 — "cap by size + age").
 *
 * A long offline stint must not grow the local queue without bound, and items
 * older than the backend's clock window are dead on arrival — so the outbox is
 * bounded on both axes.
 *
 * > **Defaults are `unverified`** (plan §7): sane placeholders until Phase-0 field
 * > data exists. [maxAgeMillis] tracks the backend's documented **24 h** reject
 * > window (plan §4); [maxItems] is a conservative bound.
 *
 * @property maxItems hard cap on total rows; enqueue past it evicts the oldest
 *   (see [Outbox.enqueue]). Must be ≥ 1.
 * @property maxAge how old (by capture time) a row may be before [Outbox.purgeExpired]
 *   drops it.
 */
data class OutboxConfig(
    val maxItems: Int = DEFAULT_MAX_ITEMS,
    val maxAge: Duration = DEFAULT_MAX_AGE,
) {
    init {
        require(maxItems >= 1) { "maxItems must be >= 1, was $maxItems" }
        require(!maxAge.isNegative && !maxAge.isZero) { "maxAge must be positive, was $maxAge" }
    }

    /** [maxAge] as epoch-milliseconds for column comparisons. */
    val maxAgeMillis: Long get() = maxAge.toMillis()

    companion object {
        /** UNVERIFIED (plan §7): conservative row cap pending Phase-0 field data. */
        const val DEFAULT_MAX_ITEMS: Int = 10_000

        /** UNVERIFIED (plan §7): mirrors the backend's 24 h clock-reject window (plan §4). */
        val DEFAULT_MAX_AGE: Duration = Duration.ofHours(24)
    }
}
