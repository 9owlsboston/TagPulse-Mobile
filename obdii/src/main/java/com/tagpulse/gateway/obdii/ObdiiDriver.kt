package com.tagpulse.gateway.obdii

import android.content.Context
import com.tagpulse.gateway.core.DiscoveredDevice
import com.tagpulse.gateway.core.DriverReading
import com.tagpulse.gateway.core.GatewayDriver
import com.tagpulse.gateway.core.Modality
import com.tagpulse.gateway.core.Observation
import com.tagpulse.gateway.obdii.ble.AndroidBleTransport
import com.tagpulse.gateway.obdii.ble.BleTransport
import com.tagpulse.gateway.obdii.ble.BleUuidConfig
import com.tagpulse.gateway.obdii.elm.ConnectionState
import com.tagpulse.gateway.obdii.elm.Elm327Session
import com.tagpulse.gateway.obdii.elm.ObdError
import com.tagpulse.gateway.obdii.elm.RpmReading
import kotlinx.coroutines.flow.StateFlow

/**
 * The OBD-II [GatewayDriver] — the first (and, for the MVE, only) modality driver.
 *
 * **M1 scope:** BLE connect → ELM327 handshake → read **RPM (`010C`) only** → log
 * it. `discover()` / `read()` are wired just enough to exercise that path; the
 * heavy lifting lives behind the testable [Elm327Session] / [BleTransport] seam
 * (plan `docs/design/obdii-mve-plan.md` §3, §6). [normalize] stays a TODO until
 * M2 (PID codec → `sensor_data` snapshot → [Observation]).
 *
 * Construct with a [session] for real / test use (see [create] and
 * [forAndroid]); the no-arg form exists only for scaffold smoke tests that check
 * [modality].
 */
class ObdiiDriver(
    private val session: Elm327Session? = null,
    private val target: DiscoveredDevice = DEFAULT_TARGET,
) : GatewayDriver {

    override val modality: Modality = Modality.OBDII

    /** Observable connection state, or `null` if this driver has no session. */
    val connectionState: StateFlow<ConnectionState>? get() = session?.state

    /**
     * M1: the transport targets a single configured dongle, so discovery returns
     * that one device (the `BluetoothLeScanner` scan itself runs inside
     * [AndroidBleTransport.connect]). Multi-dongle scan/selection is deferred — the
     * MVE binds one dongle to one vehicle locally (plan §5, OQ-3).
     */
    override suspend fun discover(): List<DiscoveredDevice> = listOf(target)

    /**
     * M1 read path: connect the dongle, run the ELM327 handshake, read RPM, and
     * return a [DriverReading] carrying the value. A clean per-read failure
     * (`NO DATA` / timeout / disconnect) surfaces as a typed [ObdReadException],
     * not a crash (plan §6).
     */
    override suspend fun read(device: DiscoveredDevice): DriverReading {
        val active = session
            ?: error("ObdiiDriver has no Elm327Session; build it via ObdiiDriver.forAndroid(...) or ObdiiDriver.create(...)")
        active.connect()
        return when (val reading = active.readRpm()) {
            is RpmReading.Value -> DriverReading(
                device = device,
                attributes = mapOf(
                    "modality" to "obdii",
                    "rpm" to reading.rpm.toString(),
                ),
            )
            is RpmReading.Failure -> throw ObdReadException(reading.reason)
        }
    }

    override fun normalize(reading: DriverReading): Observation =
        TODO("M2: PidCodec decode -> sensor_data snapshot -> Observation")

    companion object {
        /** Placeholder target for the single MVE dongle (real address set at bind). */
        val DEFAULT_TARGET: DiscoveredDevice = DiscoveredDevice(
            id = "obdii-dongle",
            displayName = "OBD-II dongle",
            address = "",
        )

        /** Build a driver over any [BleTransport] (used with the fake in tests). */
        fun create(transport: BleTransport, target: DiscoveredDevice = DEFAULT_TARGET): ObdiiDriver =
            ObdiiDriver(Elm327Session(transport), target)

        /** Build the production driver over [AndroidBleTransport] (HIL). */
        fun forAndroid(
            context: Context,
            target: DiscoveredDevice = DEFAULT_TARGET,
            config: BleUuidConfig = BleUuidConfig.NORDIC_UART_LIKE,
            deviceNamePrefix: String? = null,
        ): ObdiiDriver = create(
            AndroidBleTransport(context, config, deviceNamePrefix),
            target,
        )
    }
}

/** A clean, typed per-read failure (plan §6) — not a crash. */
class ObdReadException(val reason: ObdError) : Exception("OBD-II read failed: $reason")
