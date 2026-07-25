package com.tagpulse.mobile.scan

import com.tagpulse.gateway.core.DiscoveredDevice
import com.tagpulse.gateway.core.DriverReading
import com.tagpulse.gateway.core.GatewayDriver
import com.tagpulse.gateway.core.Modality
import com.tagpulse.gateway.core.Observation
import com.tagpulse.gateway.core.Source
import com.tagpulse.gateway.core.Subject
import com.tagpulse.gateway.core.SubjectKind
import com.tagpulse.gateway.core.relay.DrainReport
import java.time.Instant

/**
 * A scriptable in-memory [GatewayDriver] for the [ScanCoordinator] gate tests — no
 * BLE / ELM327 (the real driver is HIL). Lets a test drive discovery, the read
 * outcome, and (optionally) advance a live-link state during `read()`.
 *
 * @param devices what `discover()` returns (empty → "no dongle").
 * @param discoverError if set, `discover()` throws it.
 * @param readError if set, `read()` throws it (a driver/handshake/read failure).
 * @param pids the decoded PID map the normalized snapshot carries.
 * @param onRead a hook run inside `read()` (e.g. to advance a connection StateFlow).
 */
class FakeGatewayDriver(
    private val devices: List<DiscoveredDevice> = listOf(DEVICE),
    private val discoverError: Throwable? = null,
    private val readError: Throwable? = null,
    private val pids: Map<String, Any?> = linkedMapOf("rpm" to 850, "speed_kph" to 0),
    private val capturedAt: Instant = Instant.parse("2026-07-24T21:00:00Z"),
    private val subjectId: String = "vehicle-42",
    private val onRead: (suspend () -> Unit)? = null,
) : GatewayDriver {

    var discoverCalls = 0
        private set
    var readCalls = 0
        private set

    override val modality: Modality = Modality.OBDII

    override suspend fun discover(): List<DiscoveredDevice> {
        discoverCalls++
        discoverError?.let { throw it }
        return devices
    }

    override suspend fun read(device: DiscoveredDevice): DriverReading {
        readCalls++
        onRead?.invoke()
        readError?.let { throw it }
        return DriverReading(device = device, attributes = mapOf("captured_at" to capturedAt.toString()))
    }

    override fun normalize(reading: DriverReading): Observation = Observation(
        subject = Subject(SubjectKind.VEHICLE, id = subjectId),
        source = Source(Modality.OBDII, gatewayDeviceId = null),
        timestamp = capturedAt,
        payload = linkedMapOf(
            "modality" to "obdii",
            "protocol" to "elm327/j1979",
            "captured_at" to capturedAt.toString(),
            "pids" to LinkedHashMap(pids),
        ),
        location = null,
    )

    companion object {
        val DEVICE = DiscoveredDevice(id = "obdii-dongle", displayName = "OBDII", address = "AA:BB")
    }
}

/**
 * A [Relay] returning a scripted [DrainReport] and counting calls — the coordinator
 * gate seam for the drain outcome (the real `Drainer` + backend are exercised in
 * `:gateway-core` and HIL).
 */
class FakeRelay(private val report: DrainReport = DrainReport(sent = 1, batches = 1)) : Relay {
    var drainCalls = 0
        private set

    override suspend fun drain(): DrainReport {
        drainCalls++
        return report
    }
}
