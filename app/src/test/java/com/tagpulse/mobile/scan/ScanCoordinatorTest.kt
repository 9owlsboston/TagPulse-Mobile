package com.tagpulse.mobile.scan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tagpulse.gateway.core.GeoLocation
import com.tagpulse.gateway.core.outbox.Outbox
import com.tagpulse.gateway.core.outbox.OutboxDatabase
import com.tagpulse.gateway.core.outbox.OutboxDatabaseFactory
import com.tagpulse.gateway.core.outbox.OutboxJson
import com.tagpulse.gateway.core.outbox.OutboxMapper
import com.tagpulse.gateway.core.relay.DrainReport
import com.tagpulse.gateway.obdii.elm.ConnectionState
import com.tagpulse.gateway.obdii.elm.Elm327Exception
import com.tagpulse.mobile.location.FixedLocationProvider
import com.tagpulse.mobile.location.LocationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ScanCoordinator] end-to-end logic over a **real** Robolectric Room-backed [Outbox]
 * plus fakes for the HIL edges (driver, GPS, relay) — the gate-covered part of M5
 * (the Compose screen + Android BLE/GPS/Keystore impls are HIL). Asserts the state
 * transitions, that the GPS fix lands on `Observation.location`, that enqueue + drain
 * actually run, and the error paths (credential / driver / relay).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScanCoordinatorTest {

    private lateinit var db: OutboxDatabase
    private lateinit var outbox: Outbox
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val fix = GeoLocation(latitude = 42.36, longitude = -71.06, accuracyMeters = 4.5)

    @Before
    fun setUp() {
        db = OutboxDatabaseFactory.open(context, name = "scan-coordinator-test.db")
        outbox = Outbox(db.outboxDao())
    }

    @After
    fun tearDown() {
        db.close()
        context.getDatabasePath("scan-coordinator-test.db").delete()
    }

    private fun coordinator(
        driver: FakeGatewayDriver = FakeGatewayDriver(),
        location: LocationProvider = FixedLocationProvider(fix),
        relay: Relay = FakeRelay(),
        connectionState: MutableStateFlow<ConnectionState>? = null,
        boundSubject: () -> com.tagpulse.gateway.core.Subject? = { BOUND_VEHICLE },
    ) = ScanCoordinator(
        driver = driver,
        locationProvider = location,
        outbox = outbox,
        relay = relay,
        connectionState = connectionState,
        boundSubject = boundSubject,
    )

    @Test
    fun `happy path scans, attaches GPS, enqueues, drains, ends Done`() = runTest {
        val driver = FakeGatewayDriver(pids = linkedMapOf("rpm" to 850, "speed_kph" to 0, "coolant_temp_c" to 89, "fuel_level_pct" to 47.5))
        val relay = FakeRelay(DrainReport(sent = 1, batches = 1))
        val coordinator = coordinator(driver = driver, relay = relay)

        val emissions = collectStates(coordinator)
        coordinator.scan()
        advanceUntilIdle()

        // Ends Done with the decoded PIDs + a relay outcome.
        val state = coordinator.state.value
        assertTrue("expected Done, was $state", state is ScanState.Done)
        val done = state as ScanState.Done
        assertEquals(850, done.pids["rpm"])
        assertEquals(47.5, done.pids["fuel_level_pct"])
        assertTrue(done.hasLocation)
        assertEquals(1, done.report.sent)

        // Pipeline actually ran: enqueue + drain were invoked.
        assertEquals(1, driver.discoverCalls)
        assertEquals(1, driver.readCalls)
        assertEquals(1, relay.drainCalls)

        // The GPS fix landed on Observation.location (round-tripped through the outbox row).
        val row = outbox.pending().single()
        val stored = OutboxMapper.toObservation(row, OutboxJson())
        assertEquals(fix, stored.location)

        // Observable transitions progressed idle → connecting → reading → relaying → done.
        assertTrue(emissions.contains(ScanState.Connecting))
        assertTrue(emissions.contains(ScanState.Reading))
        assertTrue(emissions.contains(ScanState.Relaying))
        assertTrue(emissions.last() is ScanState.Done)
        assertOrder(emissions, ScanState.Connecting, ScanState.Reading, ScanState.Relaying)
    }

    @Test
    fun `relay without a GPS fix still relays (location null, not an error)`() = runTest {
        val coordinator = coordinator(location = FixedLocationProvider(null))

        coordinator.scan()
        advanceUntilIdle()

        val state = coordinator.state.value
        assertTrue(state is ScanState.Done)
        assertTrue(!(state as ScanState.Done).hasLocation)
        val stored = OutboxMapper.toObservation(outbox.pending().single(), OutboxJson())
        assertEquals(null, stored.location)
    }

    @Test
    fun `credential error surfaces to the operator (C-5EHY)`() = runTest {
        val relay = FakeRelay(DrainReport(credentialError = "ingest rejected the API key (401)"))
        val coordinator = coordinator(relay = relay)

        coordinator.scan()
        advanceUntilIdle()

        val state = coordinator.state.value
        assertTrue("expected Error, was $state", state is ScanState.Error)
        val err = state as ScanState.Error
        assertEquals(ScanState.ErrorKind.CREDENTIAL, err.kind)
        assertTrue(err.message.contains("re-enrol", ignoreCase = true))
        // The read was still enqueued (it stays PENDING for a re-drain once the key is fixed).
        assertEquals(1, outbox.pending().size)
    }

    @Test
    fun `driver read failure ends in a DRIVER error`() = runTest {
        val driver = FakeGatewayDriver(readError = Elm327Exception("handshake timed out"))
        val coordinator = coordinator(driver = driver)

        coordinator.scan()
        advanceUntilIdle()

        val state = coordinator.state.value
        assertTrue(state is ScanState.Error)
        assertEquals(ScanState.ErrorKind.DRIVER, (state as ScanState.Error).kind)
        // Nothing enqueued on a read failure.
        assertEquals(0, outbox.pending().size)
    }

    @Test
    fun `no dongle discovered ends in a DRIVER error`() = runTest {
        val coordinator = coordinator(driver = FakeGatewayDriver(devices = emptyList()))

        coordinator.scan()
        advanceUntilIdle()

        val state = coordinator.state.value
        assertTrue(state is ScanState.Error)
        assertEquals(ScanState.ErrorKind.DRIVER, (state as ScanState.Error).kind)
    }

    @Test
    fun `retryable-exhausted drain (failed rows) surfaces a RELAY error`() = runTest {
        val relay = FakeRelay(DrainReport(failed = 1, batches = 1))
        val coordinator = coordinator(relay = relay)

        coordinator.scan()
        advanceUntilIdle()

        val state = coordinator.state.value
        assertTrue(state is ScanState.Error)
        assertEquals(ScanState.ErrorKind.RELAY, (state as ScanState.Error).kind)
    }

    @Test
    fun `driver link state is mirrored (handshaking then reading) during the read`() = runTest {
        val link = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val driver = FakeGatewayDriver(onRead = {
            link.value = ConnectionState.Handshaking
            yield()
            link.value = ConnectionState.Reading
            yield()
        })
        val coordinator = coordinator(driver = driver, connectionState = link)

        val emissions = collectStates(coordinator)
        coordinator.scan()
        advanceUntilIdle()

        assertTrue(emissions.contains(ScanState.Handshaking))
        assertTrue(emissions.contains(ScanState.Reading))
        assertTrue(coordinator.state.value is ScanState.Done)
        assertOrder(emissions, ScanState.Handshaking, ScanState.Reading, ScanState.Relaying)
    }

    @Test
    fun `an unexpected enqueue failure lands a terminal INTERNAL error, not stranded`() = runTest {
        // A catastrophic durable-write failure must not strand Reading/Relaying or
        // propagate out of the scan coroutine (the class's terminal-state contract).
        val throwingOutbox = Outbox(ThrowingInsertOutboxDao())
        val coordinator = ScanCoordinator(
            driver = FakeGatewayDriver(),
            locationProvider = FixedLocationProvider(fix),
            outbox = throwingOutbox,
            relay = FakeRelay(),
            boundSubject = { BOUND_VEHICLE },
        )

        coordinator.scan() // must return normally (no exception propagates)
        advanceUntilIdle()

        val state = coordinator.state.value
        assertTrue("expected Error, was $state", state is ScanState.Error)
        assertEquals(ScanState.ErrorKind.INTERNAL, (state as ScanState.Error).kind)
    }

    @Test
    fun `an unexpected drain failure lands a terminal INTERNAL error, not stranded`() = runTest {
        val coordinator = coordinator(relay = FakeRelay(error = RuntimeException("boom")))

        coordinator.scan() // must return normally
        advanceUntilIdle()

        val state = coordinator.state.value
        assertTrue("expected Error, was $state", state is ScanState.Error)
        assertEquals(ScanState.ErrorKind.INTERNAL, (state as ScanState.Error).kind)
        // The read was durably enqueued before the drain threw.
        assertEquals(1, outbox.pending().size)
    }

    @Test
    fun `scan fails with no vehicle bound and enqueues nothing`() = runTest {
        val coordinator = coordinator(boundSubject = { null })

        coordinator.scan()
        advanceUntilIdle()

        val state = coordinator.state.value
        assertTrue("expected Error, was $state", state is ScanState.Error)
        assertEquals(ScanState.ErrorKind.CREDENTIAL, (state as ScanState.Error).kind)
        assertEquals(0, outbox.pending().size)
    }

    @Test
    fun `the bound vehicle VIN overrides the observation subject on enqueue`() = runTest {
        // The driver's fake reports subject "vehicle-42"; the bound VIN must win as tag_id.
        val coordinator = coordinator(
            driver = FakeGatewayDriver(subjectId = "vehicle-42"),
            boundSubject = { BOUND_VEHICLE },
        )

        coordinator.scan()
        advanceUntilIdle()

        assertEquals(BOUND_VEHICLE.id, outbox.pending().single().subjectId)
    }

    // -- helpers ---------------------------------------------------------------

    private companion object {
        val BOUND_VEHICLE = com.tagpulse.gateway.core.Subject(
            com.tagpulse.gateway.core.SubjectKind.VEHICLE,
            id = "1HGCM82633A004352",
        )
    }

    private fun kotlinx.coroutines.test.TestScope.collectStates(
        coordinator: ScanCoordinator,
    ): List<ScanState> {
        val emissions = mutableListOf<ScanState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.state.toList(emissions)
        }
        return emissions
    }

    private fun assertOrder(emissions: List<ScanState>, vararg expected: ScanState) {
        val indices = expected.map { emissions.indexOf(it) }
        indices.forEach { assertTrue("missing an expected state in $emissions", it >= 0) }
        assertEquals("states out of order: $emissions", indices.sorted(), indices)
    }
}
