package com.tagpulse.gateway.core.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * State + query behavior: M3 enqueue always yields a `PENDING`, `attempts = 0`
 * row (there is no sender, so no other state is ever produced), and
 * `count()`/`pending()` report correctly.
 */
@RunWith(RobolectricTestRunner::class)
class OutboxStateTest {

    private lateinit var db: OutboxDatabase
    private lateinit var outbox: Outbox
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = OutboxDatabaseFactory.open(context, name = "state-test.db")
        outbox = Outbox(db.outboxDao())
    }

    @After
    fun tearDown() {
        db.close()
        context.getDatabasePath("state-test.db").delete()
    }

    @Test
    fun `enqueue always produces a PENDING row with zero attempts`() = runBlocking {
        outbox.enqueue(OutboxFixtures.observation())

        val row = outbox.pending().single()
        assertEquals(OutboxState.PENDING, row.outboxState)
        assertEquals(0, row.attempts)
        // M3 emits only PENDING — no SENT/FAILED rows exist.
        assertEquals(0, outbox.countInState(OutboxState.SENT))
        assertEquals(0, outbox.countInState(OutboxState.FAILED))
    }

    @Test
    fun `count and pending track enqueues`() = runBlocking {
        assertEquals(0, outbox.count())
        assertTrue(outbox.pending().isEmpty())

        val ids = (1..3).map { outbox.enqueue(OutboxFixtures.observation(subjectId = "veh-$it")) }

        assertEquals(3, outbox.count())
        assertEquals(3, outbox.countInState(OutboxState.PENDING))
        assertEquals(ids, outbox.pending().map { it.id })
    }

    @Test
    fun `enqueue returns an increasing row id`() = runBlocking {
        val first = outbox.enqueue(OutboxFixtures.observation())
        val second = outbox.enqueue(OutboxFixtures.observation())
        assertTrue(second > first)
    }
}
