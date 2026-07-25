package com.tagpulse.gateway.core.relay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tagpulse.gateway.core.GeoLocation
import com.tagpulse.gateway.core.Modality
import com.tagpulse.gateway.core.Observation
import com.tagpulse.gateway.core.Source
import com.tagpulse.gateway.core.Subject
import com.tagpulse.gateway.core.SubjectKind
import com.tagpulse.gateway.core.outbox.Outbox
import com.tagpulse.gateway.core.outbox.OutboxDatabase
import com.tagpulse.gateway.core.outbox.OutboxDatabaseFactory
import com.tagpulse.gateway.core.outbox.OutboxState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

/**
 * [Drainer] behavior over a **real** Robolectric Room-backed [Outbox] + a scripted
 * [FakeBackendClient]: state transitions, backoff, at-least-once, purge-before-send,
 * and batch capping (plan §7, milestone M4). Drives the real persisted `attempts`
 * counter — a stronger check than a fully in-memory fake.
 */
@RunWith(RobolectricTestRunner::class)
class DrainerTest {

    private lateinit var db: OutboxDatabase
    private lateinit var outbox: Outbox
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val now = Instant.parse("2026-07-24T21:00:00Z")

    @Before
    fun setUp() {
        db = OutboxDatabaseFactory.open(context, name = "drainer-test.db")
        outbox = Outbox(db.outboxDao())
    }

    @After
    fun tearDown() {
        db.close()
        context.getDatabasePath("drainer-test.db").delete()
    }

    private fun observation(id: String, capturedAt: Instant = now.minus(Duration.ofMinutes(5))) =
        Observation(
            subject = Subject(kind = SubjectKind.VEHICLE, id = id),
            source = Source(modality = Modality.OBDII, gatewayDeviceId = null),
            timestamp = capturedAt,
            payload = linkedMapOf("modality" to "obdii", "pids" to linkedMapOf("rpm" to 850)),
            location = GeoLocation(latitude = 42.36, longitude = -71.06, accuracyMeters = 4.5),
        )

    private fun drainer(
        client: FakeBackendClient,
        config: DrainConfig = DrainConfig(),
        sleeps: MutableList<Long>? = null,
    ) = Drainer(
        outbox = outbox,
        client = client,
        credentials = FakeCredentialStore(),
        config = config,
        clock = { now },
        // Deterministic full-jitter: pick the cap (max) so backoff is assertable.
        jitter = { cap -> cap },
        sleep = { millis -> sleeps?.add(millis) },
    )

    @Test
    fun `pending rows transition to SENT on 201`() = runBlocking {
        outbox.enqueue(observation("veh-1"))
        outbox.enqueue(observation("veh-2"))
        val client = FakeBackendClient().enqueue(BatchResult.Accepted(ingested = 2, rejected = 0))

        val report = drainer(client).drain()

        assertEquals(2, report.sent)
        assertEquals(0, report.failed)
        assertEquals(2, outbox.countInState(OutboxState.SENT))
        assertEquals(0, outbox.countInState(OutboxState.PENDING))
    }

    @Test
    fun `backend rejected count is surfaced in the report while rows still go SENT`() = runBlocking {
        outbox.enqueue(observation("veh-1"))
        // 201 with a nonzero clock-rejected count: the rows still commit SENT (no
        // per-row ids to selectively fail), but the count is surfaced for inspection.
        val client = FakeBackendClient().enqueue(BatchResult.Accepted(ingested = 1, rejected = 2))

        val report = drainer(client).drain()

        assertEquals(2, report.rejected)
        assertEquals(1, report.sent)
        assertEquals(1, outbox.countInState(OutboxState.SENT))
        assertEquals(0, outbox.countInState(OutboxState.PENDING))
    }

    @Test
    fun `repeated 5xx increments attempts, backs off, and eventually FAILS`() = runBlocking {
        val id = outbox.enqueue(observation("veh-1"))
        val client = FakeBackendClient().enqueue(BatchResult.Retryable("server error 500"))
        val sleeps = mutableListOf<Long>()

        val report = drainer(client, DrainConfig(maxAttempts = 3), sleeps).drain()

        // 3 attempts total, then FAILED.
        assertEquals(3, client.callCount)
        assertEquals(1, report.failed)
        assertEquals(1, outbox.countInState(OutboxState.FAILED))
        assertEquals(0, outbox.countInState(OutboxState.PENDING))
        // attempts persisted = 3 (bumped once per failure).
        assertEquals(3, outbox.pending().plus(failedRows()).first { it.id == id }.attempts)
        // Full-jitter exponential backoff between the 3 attempts: base 1s, then 2s.
        assertEquals(listOf(1_000L, 2_000L), sleeps)
    }

    @Test
    fun `at-least-once - a lost 201 re-sends the still-PENDING rows (duplicate) without losing data`() = runBlocking {
        outbox.enqueue(observation("veh-1"))
        outbox.enqueue(observation("veh-2"))
        // First call: the backend committed but the ack was lost → the client sees a
        // retryable network error. Second call: the re-send is accepted.
        val client = FakeBackendClient().enqueue(
            BatchResult.Retryable("network error: SocketTimeoutException"),
            BatchResult.Accepted(ingested = 2, rejected = 0),
        )

        val report = drainer(client).drain()

        // The rows were re-sent (call 2) — on a real backend this DUPLICATES the
        // snapshot (documented, accepted; at-least-once, Fix 4). No data is lost.
        assertEquals(2, client.callCount)
        assertEquals(
            client.sentBatches[0].map { it.tagId },
            client.sentBatches[1].map { it.tagId },
        )
        assertEquals(2, report.sent)
        assertEquals(2, outbox.countInState(OutboxState.SENT))
        assertEquals(0, outbox.countInState(OutboxState.PENDING))
    }

    @Test
    fun `429 within maxBackoff honors Retry-After then succeeds`() = runBlocking {
        outbox.enqueue(observation("veh-1"))
        // 429 with a 5s Retry-After (< maxBackoff) then an accepted re-send.
        val client = FakeBackendClient().enqueue(
            BatchResult.Retryable("rate limited (429)", retryAfterMillis = 5_000L),
            BatchResult.Accepted(ingested = 1, rejected = 0),
        )
        val sleeps = mutableListOf<Long>()

        val report = drainer(client, sleeps = sleeps).drain()

        // The Retry-After directive is honored verbatim (no exponential/jitter).
        assertEquals(listOf(5_000L), sleeps)
        assertEquals(2, client.callCount)
        assertEquals(1, report.sent)
        assertEquals(1, outbox.countInState(OutboxState.SENT))
        assertNull(report.retryAfterMillis)
    }

    @Test
    fun `429 with Retry-After beyond maxBackoff defers, leaving rows PENDING`() = runBlocking {
        val id = outbox.enqueue(observation("veh-1"))
        // Server asks to wait 1 h — far beyond the 1-min maxBackoff.
        val client = FakeBackendClient()
            .enqueue(BatchResult.Retryable("rate limited (429)", retryAfterMillis = 3_600_000L))
        val sleeps = mutableListOf<Long>()

        val report = drainer(client, sleeps = sleeps).drain()

        // Deferred: one attempt made, then stop — no sleep, no FAIL, rows PENDING.
        assertEquals(1, client.callCount)
        assertEquals(emptyList<Long>(), sleeps)
        assertEquals(3_600_000L, report.retryAfterMillis)
        assertEquals(0, report.sent)
        assertEquals(0, report.failed)
        assertEquals(1, outbox.countInState(OutboxState.PENDING))
        assertEquals(0, outbox.countInState(OutboxState.FAILED))
        // Attempts NOT bumped — a defer is not a failed attempt.
        assertEquals(0, outbox.pending().first { it.id == id }.attempts)
    }

    @Test
    fun `purgeExpired runs before send - stale rows are dropped, not relayed`() = runBlocking {
        outbox.enqueue(observation("stale", capturedAt = now.minus(Duration.ofHours(25))))
        outbox.enqueue(observation("fresh", capturedAt = now.minus(Duration.ofHours(1))))
        val client = FakeBackendClient() // default → Accepted

        val report = drainer(client).drain()

        assertEquals(1, report.purged)
        assertEquals(1, client.callCount)
        // Only the fresh row was relayed; the stale one never reached the client.
        assertEquals(listOf("fresh"), client.sentBatches.single().map { it.tagId })
        assertEquals(1, report.sent)
    }

    @Test
    fun `batches are capped at the configured size`() = runBlocking {
        repeat(5) { outbox.enqueue(observation("veh-$it")) }
        val client = FakeBackendClient() // default → Accepted

        val report = drainer(client, DrainConfig(batchSize = 2)).drain()

        // 5 rows in batches of 2 → 2, 2, 1.
        assertEquals(3, client.callCount)
        assertEquals(listOf(2, 2, 1), client.sentBatches.map { it.size })
        assertEquals(3, report.batches)
        assertEquals(5, report.sent)
    }

    @Test
    fun `batchSize cannot exceed the server cap`() {
        try {
            DrainConfig(batchSize = 501)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `terminal 400 parks rows FAILED`() = runBlocking {
        outbox.enqueue(observation("veh-1"))
        val client = FakeBackendClient().enqueue(BatchResult.Terminal(statusCode = 400, reason = "bad payload"))

        val report = drainer(client).drain()

        assertEquals(1, report.failed)
        assertEquals(1, outbox.countInState(OutboxState.FAILED))
        assertNull(report.credentialError)
    }

    @Test
    fun `401 aborts the drain and leaves rows PENDING`() = runBlocking {
        outbox.enqueue(observation("veh-1"))
        outbox.enqueue(observation("veh-2"))
        val client = FakeBackendClient().enqueue(BatchResult.CredentialError("ingest rejected the API key (401)"))

        val report = drainer(client).drain()

        assertNotNull(report.credentialError)
        assertEquals(0, report.sent)
        assertEquals(0, report.failed)
        // Not failed — a fixed credential re-drains them.
        assertEquals(2, outbox.countInState(OutboxState.PENDING))
        assertEquals(0, outbox.countInState(OutboxState.FAILED))
    }

    @Test
    fun `drain skips when not enrolled`() = runBlocking {
        outbox.enqueue(observation("veh-1"))
        val client = FakeBackendClient()
        val notEnrolled = Drainer(
            outbox = outbox,
            client = client,
            credentials = FakeCredentialStore(deviceId = null),
            clock = { now },
        )

        val report = notEnrolled.drain()

        assertNotNull(report.credentialError)
        assertEquals(0, client.callCount)
        assertEquals(1, outbox.countInState(OutboxState.PENDING))
    }

    private suspend fun failedRows() =
        db.outboxDao().byState(OutboxState.FAILED.name)
}
