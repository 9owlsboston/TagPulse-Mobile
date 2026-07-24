package com.tagpulse.gateway.core

/**
 * The core/driver seam: `discover → read → normalize`.
 *
 * A `GatewayDriver` is the **only** modality-specific code the gateway needs; the
 * core (outbox, credential, batcher, backend client) is written once and reused,
 * and every new modality is a new driver — not a new SDK (plan
 * `docs/design/obdii-mve-plan.md` §3).
 *
 * Contract of the seam:
 * - The driver knows **nothing** about HTTP, auth, or the outbox.
 * - The core knows **nothing** about BLE, ELM327, or PID framing.
 * - They meet here, exchanging only a normalized [Observation].
 *
 * This is an interface only. The OBD-II implementation — BLE transport, ELM327
 * session, PID codec — arrives in milestones M1–M2; M0 defines the shape.
 */
interface GatewayDriver {

    /** The modality this driver senses. Stable for the lifetime of the driver. */
    val modality: Modality

    /**
     * Find candidate southbound devices for this modality (e.g. scan for ELM327
     * BLE dongles). I/O-bound and cancellable.
     *
     * @return the devices discovered during this scan (possibly empty).
     */
    suspend fun discover(): List<DiscoveredDevice>

    /**
     * Take a single raw reading from a previously [discover]ed device (e.g. connect
     * the dongle, run the AT handshake, request the PIDs). I/O-bound and cancellable.
     *
     * On-demand semantics: one call == one snapshot, not a sampling stream
     * (plan §4).
     *
     * @param device a device returned by [discover].
     * @return the raw, un-normalized reading.
     */
    suspend fun read(device: DiscoveredDevice): DriverReading

    /**
     * Convert a raw [DriverReading] into the core's normalized [Observation]. Pure
     * and synchronous — no I/O — so it is trivially unit-testable (plan §3: the PID
     * codec / normalize step is the pure, portable part).
     *
     * @param reading a reading returned by [read].
     * @return the normalized observation the core will enqueue and relay.
     */
    fun normalize(reading: DriverReading): Observation
}

/**
 * A candidate southbound device surfaced by [GatewayDriver.discover].
 *
 * Deliberately transport-neutral: the OBD-II driver will carry a BLE address in
 * [address], but the shape does not name BLE so other modalities can reuse it.
 *
 * @property id stable, driver-scoped identifier for the device.
 * @property displayName human-readable name for UI (e.g. the dongle's BLE name).
 * @property address opaque transport address the driver uses to connect (e.g. a
 *   BLE MAC / GATT handle). Opaque to the core.
 */
data class DiscoveredDevice(
    val id: String,
    val displayName: String,
    val address: String,
)

/**
 * A raw, un-normalized reading produced by [GatewayDriver.read], before
 * [GatewayDriver.normalize] turns it into an [Observation].
 *
 * The concrete driver decides what goes in [attributes] (for OBD-II: decoded PID
 * values plus, debug-gated, the raw ELM327 frames — plan §4). Kept as a neutral
 * string map at the seam so the core never needs modality-specific types.
 *
 * @property device the device this reading came from.
 * @property attributes normalized key/value data captured by the driver.
 */
data class DriverReading(
    val device: DiscoveredDevice,
    val attributes: Map<String, String>,
)
