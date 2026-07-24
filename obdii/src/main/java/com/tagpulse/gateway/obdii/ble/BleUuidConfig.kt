package com.tagpulse.gateway.obdii.ble

import java.util.UUID

/**
 * GATT service/characteristic UUIDs for an ELM327-over-BLE dongle.
 *
 * **Configurable, never hard-coded constants** (plan `docs/design/obdii-mve-plan.md`
 * §6 / §9 "hard-coding vs config"): the exact UUIDs are **dongle-specific**
 * (`unverified`). Any field left `null` tells [AndroidBleTransport] to *discover*
 * it at runtime (the first service exposing a notify + write characteristic). The
 * [NORDIC_UART_LIKE] default matches the common clone layout but **must** be
 * overridable so an OBDLink MX+ (an ELM327 superset) needs no driver rewrite.
 *
 * @property serviceUuid GATT service to use, or `null` to discover.
 * @property notifyCharUuid characteristic that emits response notifications, or
 *   `null` to discover (first characteristic with the NOTIFY property).
 * @property writeCharUuid characteristic that accepts command writes, or `null` to
 *   discover (first characteristic with a WRITE / WRITE-NO-RESPONSE property).
 */
data class BleUuidConfig(
    val serviceUuid: UUID? = null,
    val notifyCharUuid: UUID? = null,
    val writeCharUuid: UUID? = null,
) {
    companion object {
        /**
         * Nordic-UART-like default many ELM327 BLE clones expose. **`unverified` —
         * dongle-specific**; validate against the purchased adapter early (plan §9
         * risk "ELM327 clone quirks"). Override, or pass `null`s to auto-discover.
         */
        val NORDIC_UART_LIKE: BleUuidConfig = BleUuidConfig(
            serviceUuid = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
            notifyCharUuid = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
            writeCharUuid = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
        )

        /**
         * Discover everything at runtime — no assumed UUIDs at all. Prefer this
         * (or a per-dongle override) once the field adapter's layout is known.
         */
        val DISCOVER_ALL: BleUuidConfig = BleUuidConfig()

        /** Standard Client Characteristic Configuration Descriptor (enable notify). */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
