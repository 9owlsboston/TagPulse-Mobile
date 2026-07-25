package com.tagpulse.mobile.di

import android.content.Context
import com.tagpulse.gateway.core.Modality
import com.tagpulse.gateway.core.Source
import com.tagpulse.gateway.core.Subject
import com.tagpulse.gateway.core.SubjectKind
import com.tagpulse.gateway.core.outbox.Outbox
import com.tagpulse.gateway.core.outbox.OutboxDatabaseFactory
import com.tagpulse.gateway.core.relay.Drainer
import com.tagpulse.gateway.core.relay.InMemoryCredentialStore
import com.tagpulse.gateway.core.relay.KeystoreCredentialStore
import com.tagpulse.gateway.core.relay.OkHttpBackendClient
import com.tagpulse.gateway.core.relay.isEnrolled
import com.tagpulse.gateway.obdii.ObdiiConfig
import com.tagpulse.gateway.obdii.ObdiiDriver
import com.tagpulse.mobile.enrol.EnrolmentCoordinator
import com.tagpulse.mobile.location.AndroidLocationProvider
import com.tagpulse.mobile.scan.Relay
import com.tagpulse.mobile.scan.ScanCoordinator

/**
 * The app's **composition root** — manual dependency injection (no Hilt needed for
 * the MVE, plan §8 M5). It constructs the real production implementations of the
 * gateway core + OBD-II driver and hands them to a [ScanCoordinator].
 *
 * Wires, end to end:
 * - [KeystoreCredentialStore] — enrolment facts (`device_id` + tenant API key) held
 *   in the Android Keystore (never in source / logs; AGENTS §2).
 * - [Outbox] — the durable Room queue (restart-safe; plan §7).
 * - [OkHttpBackendClient] + [Drainer] — batched `POST /tag-reads/batch` relay (M4).
 * - [ObdiiDriver] over `AndroidBleTransport` — the BLE/ELM327 read path (M1/M2).
 * - [AndroidLocationProvider] — the one-shot GPS fix (M5).
 *
 * **HIL note:** the real Keystore / BLE / GPS / backend all need a device + a live
 * tenant, so this root is exercised hardware-in-the-loop, not in the unit gate (the
 * gate covers [ScanCoordinator] with fakes). [baseUrl] and the vehicle binding are
 * setup inputs (QR/out-of-band, plan §5) — placeholders here until enrolment writes them.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Enrolment credentials (Keystore-backed). Base URL falls back to a build-time
     *  default until enrolment persists the real one (plan §5). */
    val credentials: KeystoreCredentialStore =
        KeystoreCredentialStore(appContext, fallbackBaseUrl = DEFAULT_BASE_URL)

    /** True once the handset is enrolled (device_id + ingest key present). */
    val isEnrolled: Boolean get() = credentials.isEnrolled

    /**
     * Drives the handset↔tenant enrolment (ledger `C-RYH7`). Provisioning runs against
     * the operator-supplied *candidate* base URL via an ephemeral client (the persisted
     * store is written only on success), then the enrolment tuple is stored atomically.
     */
    val enrolmentCoordinator: EnrolmentCoordinator = EnrolmentCoordinator(
        provision = { baseUrl, provisioningKey, name ->
            OkHttpBackendClient(InMemoryCredentialStore(baseUrl))
                .provisionDevice(provisioningKey = provisioningKey, name = name)
        },
        persist = { deviceId, apiKey, baseUrl -> credentials.store(deviceId, apiKey, baseUrl) },
    )

    /** Durable outbox over the file-backed Room DB (A4 restart-safe). */
    val outbox: Outbox = Outbox(OutboxDatabaseFactory.open(appContext).outboxDao())

    private val backendClient = OkHttpBackendClient(credentials)

    private val drainer = Drainer(outbox = outbox, client = backendClient, credentials = credentials)

    /**
     * The OBD-II driver bound to one vehicle (plan §5 — dongle↔vehicle is a local,
     * operator-confirmed mapping). [ObdiiConfig.subject]'s id is the vehicle's
     * `binding_kind='device'` binding value (relayed as `tag_id`, plan §4). This is a
     * placeholder until the bind flow captures the real vehicle; wired here so the
     * end-to-end path compiles + runs on the handset.
     */
    private val driver: ObdiiDriver = ObdiiDriver.forAndroid(
        context = appContext,
        config = ObdiiConfig(
            subject = Subject(SubjectKind.VEHICLE, id = DEFAULT_VEHICLE_BINDING_VALUE),
            source = Source(Modality.OBDII, gatewayDeviceId = credentials.deviceId),
        ),
        deviceNamePrefix = OBDII_NAME_PREFIX,
    )

    private val locationProvider = AndroidLocationProvider(appContext)

    /** The end-to-end coordinator the UI drives (real relay wraps the [Drainer]). */
    val scanCoordinator: ScanCoordinator = ScanCoordinator(
        driver = driver,
        locationProvider = locationProvider,
        outbox = outbox,
        relay = Relay { drainer.drain() },
        connectionState = driver.connectionState,
    )

    companion object {
        /**
         * Fallback backend origin used until enrolment persists the real one. A
         * **setup input** (not a secret). Enrolment (`EnrolmentCoordinator`) overwrites
         * it in the Keystore; this is only the pre-enrolment default.
         */
        const val DEFAULT_BASE_URL: String = "https://api.tagpulse.example"

        /** Placeholder vehicle `device`-binding value; real value captured at bind (§5). */
        const val DEFAULT_VEHICLE_BINDING_VALUE: String = "vehicle-mve"

        /** Advertised-name prefix used to filter the BLE scan to the ELM327 dongle. */
        const val OBDII_NAME_PREFIX: String = "OBDII"
    }
}
