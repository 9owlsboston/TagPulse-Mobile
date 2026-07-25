package com.tagpulse.gateway.obdii

import android.content.Context
import com.tagpulse.gateway.core.DiscoveredDevice
import com.tagpulse.gateway.core.DriverReading
import com.tagpulse.gateway.core.GatewayDriver
import com.tagpulse.gateway.core.Modality
import com.tagpulse.gateway.core.Observation
import com.tagpulse.gateway.core.Source
import com.tagpulse.gateway.core.Subject
import com.tagpulse.gateway.core.SubjectKind
import com.tagpulse.gateway.obdii.ble.AndroidBleTransport
import com.tagpulse.gateway.obdii.ble.BleTransport
import com.tagpulse.gateway.obdii.ble.BleUuidConfig
import com.tagpulse.gateway.obdii.elm.ConnectionState
import com.tagpulse.gateway.obdii.elm.Elm327Session
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
    private val config: ObdiiConfig = DEFAULT_CONFIG,
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
     * M2 read path: connect the dongle, run the ELM327 handshake, read the full
     * four-PID snapshot, and return a [DriverReading] carrying that snapshot
     * (flattened onto the neutral seam attributes) so [normalize] can consume it.
     *
     * Per-PID failures are absorbed by [Elm327Session.readSnapshot] (that field is
     * simply absent) — a partial snapshot is a success, not a crash (plan §4/§6).
     * A link/handshake failure surfaces as [com.tagpulse.gateway.obdii.elm.Elm327Exception].
     */
    override suspend fun read(device: DiscoveredDevice): DriverReading {
        val active = session
            ?: error("ObdiiDriver has no Elm327Session; build it via ObdiiDriver.forAndroid(...) or ObdiiDriver.create(...)")
        active.connect()
        val snapshot = active.readSnapshot(
            includeRaw = config.includeRawFrames,
            dongle = config.dongle,
        )
        return DriverReading(device = device, attributes = snapshot.toAttributes())
    }

    /**
     * Pure, synchronous normalize (no I/O — plan §3): reconstruct the snapshot from
     * the seam attributes and map it onto the core's [Observation]. `subject` /
     * `source` come from the injected [config] (a driver is bound to one vehicle);
     * `timestamp` is the snapshot capture time; `location` stays null — GPS wiring
     * is a later layer (M4/M5), out of M2 scope (plan §4).
     */
    override fun normalize(reading: DriverReading): Observation {
        val snapshot = ObdSnapshot.fromAttributes(reading.attributes)
        return Observation(
            subject = config.subject,
            source = config.source,
            timestamp = snapshot.capturedAt,
            payload = snapshot.toPayload(),
            location = null,
        )
    }

    companion object {
        /** Placeholder target for the single MVE dongle (real address set at bind). */
        val DEFAULT_TARGET: DiscoveredDevice = DiscoveredDevice(
            id = "obdii-dongle",
            displayName = "OBD-II dongle",
            address = "",
        )

        /**
         * Placeholder config for scaffold/read smoke tests. Real usage injects an
         * [ObdiiConfig] carrying the bound vehicle's [Subject] (plan §4/§5).
         */
        val DEFAULT_CONFIG: ObdiiConfig = ObdiiConfig(
            subject = Subject(SubjectKind.VEHICLE, id = "vehicle"),
        )

        /** Build a driver over any [BleTransport] (used with the fake in tests). */
        fun create(
            transport: BleTransport,
            config: ObdiiConfig = DEFAULT_CONFIG,
            target: DiscoveredDevice = DEFAULT_TARGET,
        ): ObdiiDriver = ObdiiDriver(Elm327Session(transport), target, config)

        /** Build the production driver over [AndroidBleTransport] (HIL). */
        fun forAndroid(
            context: Context,
            config: ObdiiConfig = DEFAULT_CONFIG,
            target: DiscoveredDevice = DEFAULT_TARGET,
            uuidConfig: BleUuidConfig = BleUuidConfig.NORDIC_UART_LIKE,
            deviceNamePrefix: String? = null,
        ): ObdiiDriver = create(
            AndroidBleTransport(context, uuidConfig, deviceNamePrefix),
            config,
            target,
        )
    }
}

/**
 * Driver configuration injected at construction — binds this driver to one vehicle
 * and one reporting gateway (plan §3 "cheap hedge": every observation keys an
 * explicit subject + source; §4/§5).
 *
 * @property subject the observed vehicle asset — its [Subject.id] is the vehicle's
 *   `binding_kind='device'` binding value, relayed as `TagReadCreate.tag_id` (§4).
 * @property source the reporting modality + gateway; `gatewayDeviceId` stays null
 *   until enrolment (M4).
 * @property includeRawFrames debug flag: retain the raw ELM327 frames in the
 *   snapshot payload (dropped by default to stay within footprint — §4).
 * @property dongle best-effort adapter metadata stamped on the snapshot (optional).
 */
data class ObdiiConfig(
    val subject: Subject,
    val source: Source = Source(Modality.OBDII, gatewayDeviceId = null),
    val includeRawFrames: Boolean = false,
    val dongle: ObdSnapshot.DongleInfo? = null,
)
