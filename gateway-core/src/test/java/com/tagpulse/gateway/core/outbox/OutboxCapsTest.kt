package com.tagpulse.gateway.core.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

/**
 * Footprint caps (plan §7): size-cap eviction on enqueue and age-based purge.
 */
@RunWith(RobolectricTestRunner::class)
class OutboxCapsTest {

    private lateinit var db: OutboxDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = OutboxDatabaseFactory.open(context, name = "caps-test.db")
    }

    @After
    fun tearDown() {
        db.close()
        context.getDatabasePath("caps-test.db").delete()
    }

    @Test
    fun `size cap evicts the oldest and bounds the row count`() = runBlocking {
        val maxItems = 3
        // Monotonic clock so createdAt strictly increases (deterministic oldest).
        var tick = 0L
        val outbox = Outbox(
            dao = db.outboxDao(),
            config = OutboxConfig(maxItems = maxItems),
            clock = { Instant.ofEpochMilli(1_000L + tick++) },
        )

        val ids = (1..5).map { outbox.enqueue(OutboxFixtures.observation(subjectId = "veh-$it")) }

        // Count is bounded at the cap despite 5 enqueues.
        assertEquals(maxItems, outbox.count())

        // The two oldest were evicted; the three newest remain, in order.
        val remaining = outbox.pending().map { it.subjectId }
        assertEquals(listOf("veh-3", "veh-4", "veh-5"), remaining)

        // The first two ids are gone.
        val survivingIds = outbox.pending().map { it.id }.toSet()
        assertFalse(survivingIds.contains(ids[0]))
        assertFalse(survivingIds.contains(ids[1]))
        assertTrue(survivingIds.contains(ids[4]))
    }

    @Test
    fun `age purge drops stale items and keeps fresh ones`() = runBlocking {
        val now = Instant.parse("2026-07-24T21:00:00Z")
        val outbox = Outbox(
            dao = db.outboxDao(),
            config = OutboxConfig(maxAge = Duration.ofHours(24)),
        )

        // Stale: captured 25 h ago (past the 24 h window). Fresh: captured 1 h ago.
        outbox.enqueue(OutboxFixtures.observation(subjectId = "stale", capturedAt = now.minus(Duration.ofHours(25))))
        outbox.enqueue(OutboxFixtures.observation(subjectId = "fresh", capturedAt = now.minus(Duration.ofHours(1))))
        assertEquals(2, outbox.count())

        val purged = outbox.purgeExpired(now)

        assertEquals("exactly the stale item is purged", 1, purged)
        val remaining = outbox.pending().map { it.subjectId }
        assertEquals(listOf("fresh"), remaining)
    }

    @Test
    fun `age purge is a no-op when nothing is stale`() = runBlocking {
        val now = Instant.parse("2026-07-24T21:00:00Z")
        val outbox = Outbox(dao = db.outboxDao(), config = OutboxConfig(maxAge = Duration.ofHours(24)))

        outbox.enqueue(OutboxFixtures.observation(capturedAt = now.minus(Duration.ofMinutes(30))))

        assertEquals(0, outbox.purgeExpired(now))
        assertEquals(1, outbox.count())
    }
}
