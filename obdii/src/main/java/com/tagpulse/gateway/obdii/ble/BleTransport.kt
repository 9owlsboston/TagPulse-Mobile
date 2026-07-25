package com.tagpulse.gateway.obdii.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The BLE link seam for the OBD-II driver (plan `docs/design/obdii-mve-plan.md`
 * §3 / §6) — **the key design constraint of M1.**
 *
 * The transport is deliberately transport-*mechanism* only: it knows how to open a
 * GATT link, enable notifications, push command bytes, and surface notification
 * fragments. It knows nothing about ELM327 AT commands, the `>` prompt, or PID
 * framing — that is [com.tagpulse.gateway.obdii.elm.Elm327Session]'s job. This
 * split is what lets the session be unit-tested with a [FakeBleTransport] and no
 * hardware.
 *
 * Two implementations exist:
 * - [AndroidBleTransport] — the real `android.bluetooth` link (HIL-only).
 * - `FakeBleTransport` (test source set) — scriptable, in-memory.
 *
 * Notification framing is **not** guaranteed to align with responses: BLE may
 * split one ELM327 response across several notifications regardless of the
 * negotiated MTU, so the consumer must reassemble [notifications] up to the `>`
 * prompt itself (plan §6 MTU row).
 */
interface BleTransport {

    /**
     * Live GATT link status. Flips to `false` on a GATT disconnect so the session
     * can attempt a single reconnect (plan §6 timeouts/reconnect row).
     */
    val connected: StateFlow<Boolean>

    /**
     * Raw notification fragments from the notify characteristic, in arrival order.
     * One emission == one BLE notification == an arbitrary slice of an ELM327
     * response. Cold/replayless: subscribe *before* [write] to avoid missing the
     * response.
     */
    val notifications: Flow<ByteArray>

    /**
     * Establish the link: scan/select the dongle → `connectGatt` → discover
     * services → enable notifications (write the CCCD) → request a larger MTU.
     * Suspends until the adapter is ready to accept writes.
     *
     * @throws BleException if the link cannot be established.
     */
    suspend fun connect()

    /**
     * Write a command (already terminated, e.g. `010C\r`) to the write
     * characteristic.
     *
     * @throws BleDisconnectedException if the link dropped mid-session.
     * @throws BleException on other write failures.
     */
    suspend fun write(command: ByteArray)

    /** Tear down the GATT link. Idempotent. */
    suspend fun disconnect()
}

/** Base failure for [BleTransport] operations. */
open class BleException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The GATT link dropped; the session may attempt a single reconnect (plan §6). */
class BleDisconnectedException(message: String = "GATT link disconnected") : BleException(message)
