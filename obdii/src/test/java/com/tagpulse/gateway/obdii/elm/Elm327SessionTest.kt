package com.tagpulse.gateway.obdii.elm

import com.tagpulse.gateway.obdii.ble.FakeBleTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Elm327Session] driven entirely by a scriptable [FakeBleTransport] — the whole
 * ELM327 handshake + RPM read path is exercised **without hardware** (plan
 * `docs/design/obdii-mve-plan.md` §6).
 *
 * Covers: (a) exact command order, (b) fragment reassembly to `>`, (c) parsed RPM,
 * (d) [ConnectionState] transitions, (e) timeout + `NO DATA` clean-failure paths,
 * plus round-2 hardware-path hardening: generic `BleException` handling on read +
 * handshake, and the drop → reconnect → re-handshake recovery path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Elm327SessionTest {

    private val handshakeScript = mapOf(
        "ATZ" to listOf("ELM327 v1.5\r\r>"),
        "ATE0" to listOf("OK\r\r>"),
        "ATL0" to listOf("OK\r\r>"),
        "ATS0" to listOf("OK\r\r>"),
        "ATSP0" to listOf("OK\r\r>"),
    )

    @Test
    fun `handshake then RPM issues the exact command sequence in order`() = runTest {
        val transport = FakeBleTransport(handshakeScript + ("010C" to listOf("41 0C 0D 48\r\r>")))
        val session = Elm327Session(transport)

        session.connect()
        session.readRpm()

        assertEquals(
            listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0", "010C"),
            transport.writes,
        )
    }

    @Test
    fun `RPM notifications fragmented across BLE frames are reassembled and decoded`() = runTest {
        // The 010C response arrives split across four separate notifications, the
        // last carrying the '>' prompt — the session must reassemble before decode.
        val transport = FakeBleTransport(
            handshakeScript + ("010C" to listOf("41 0C ", "0D ", "48\r", ">")),
        )
        val session = Elm327Session(transport)

        session.connect()
        val reading = session.readRpm()

        assertEquals(RpmReading.Value(850), reading)
    }

    @Test
    fun `state transitions Disconnected to Ready to Reading on a successful read`() = runTest {
        val transport = FakeBleTransport(handshakeScript + ("010C" to listOf("41 0C 0D 48\r\r>")))
        val session = Elm327Session(transport)

        val states = mutableListOf<ConnectionState>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            session.state.collect { states += it }
        }

        session.connect()
        session.readRpm()
        collector.cancel()

        assertEquals(ConnectionState.Disconnected, states.first())
        assertOrderedSubsequence(
            states,
            listOf(
                ConnectionState.Disconnected,
                ConnectionState.Connecting,
                ConnectionState.Handshaking,
                ConnectionState.Ready,
                ConnectionState.Reading,
            ),
        )
        assertEquals(ConnectionState.Ready, states.last())
    }

    @Test
    fun `a command timeout yields a clean TIMEOUT error state, not a crash`() = runTest {
        // No script entry for 010C -> no notifications -> the read times out.
        val transport = FakeBleTransport(handshakeScript, dropAfter = null)
        val session = Elm327Session(transport, commandTimeoutMs = 2_000, maxRetries = 1)

        session.connect()
        val reading = session.readRpm()

        assertEquals(RpmReading.Failure(ObdError.TIMEOUT), reading)
        assertEquals(ConnectionState.Error(ObdError.TIMEOUT, "RPM read failed: TIMEOUT"), session.state.value)
    }

    @Test
    fun `NO DATA yields a clean NO_DATA error state`() = runTest {
        val transport = FakeBleTransport(handshakeScript + ("010C" to listOf("NO DATA\r\r>")))
        val session = Elm327Session(transport)

        session.connect()
        val reading = session.readRpm()

        assertEquals(RpmReading.Failure(ObdError.NO_DATA), reading)
        assertTrue(session.state.value is ConnectionState.Error)
        assertEquals(ObdError.NO_DATA, (session.state.value as ConnectionState.Error).reason)
    }

    @Test
    fun `successful RPM read is logged`() = runTest {
        val logged = mutableListOf<String>()
        val transport = FakeBleTransport(handshakeScript + ("010C" to listOf("41 0C 0D 48\r\r>")))
        val session = Elm327Session(transport, logger = { logged += it })

        session.connect()
        session.readRpm()

        assertEquals(listOf("OBD-II RPM = 850"), logged)
    }

    @Test
    fun `a generic BleException on read is a clean LINK_ERROR failure, not a throw`() = runTest {
        // Fix 1: AndroidBleTransport.write() can throw a generic BleException (not
        // just BleDisconnectedException) — e.g. a rejected GATT write. readRpm()
        // must NOT throw and the state must land on Error.
        val transport = FakeBleTransport(handshakeScript, throwOn = "010C")
        val session = Elm327Session(transport)

        session.connect()
        val reading = session.readRpm() // must not throw

        assertEquals(RpmReading.Failure(ObdError.LINK_ERROR), reading)
        assertTrue(session.state.value is ConnectionState.Error)
        assertEquals(ObdError.LINK_ERROR, (session.state.value as ConnectionState.Error).reason)
    }

    @Test
    fun `a generic BleException during handshake leaves Error and throws Elm327Exception`() = runTest {
        // Fix 1: the handshake loop must also catch a generic BleException — state
        // must be Error (not stuck at Handshaking) and the thrown type Elm327Exception.
        val transport = FakeBleTransport(handshakeScript, throwOn = "ATE0")
        val session = Elm327Session(transport)

        val thrown: Throwable? = try {
            session.connect()
            null
        } catch (e: Throwable) {
            e
        }

        assertTrue("expected Elm327Exception, got $thrown", thrown is Elm327Exception)
        assertTrue(session.state.value is ConnectionState.Error)
        assertEquals(ObdError.LINK_ERROR, (session.state.value as ConnectionState.Error).reason)
    }

    @Test
    fun `a mid-read disconnect triggers one reconnect, re-handshake, and recovers`() = runTest {
        // Fix 5: exercise dropAfter + connectCount. The first 010C write drops the
        // link; the session reconnects (connectCount == 2), re-runs the handshake,
        // and the retried 010C returns a recovered value.
        val transport = FakeBleTransport(
            handshakeScript + ("010C" to listOf("41 0C 0D 48\r\r>")),
            dropAfter = "010C",
        )
        val session = Elm327Session(transport, maxRetries = 1)

        session.connect()
        val reading = session.readRpm()

        assertEquals(RpmReading.Value(850), reading)
        assertEquals(2, transport.connectCount) // initial connect + one reconnect
        assertEquals(ConnectionState.Ready, session.state.value)
    }

    /** Assert [expected] appears as an in-order (not necessarily contiguous) subsequence. */
    private fun assertOrderedSubsequence(
        actual: List<ConnectionState>,
        expected: List<ConnectionState>,
    ) {
        var index = 0
        for (state in actual) {
            if (index < expected.size && state == expected[index]) index++
        }
        assertTrue(
            "expected ordered subsequence $expected within $actual",
            index == expected.size,
        )
    }
}
