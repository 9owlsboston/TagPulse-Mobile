package com.tagpulse.gateway.obdii

import com.tagpulse.gateway.core.DiscoveredDevice
import com.tagpulse.gateway.core.DriverReading
import com.tagpulse.gateway.core.GatewayDriver
import com.tagpulse.gateway.core.Modality
import com.tagpulse.gateway.core.Observation

/**
 * The OBD-II [GatewayDriver] — the first (and, for the MVE, only) modality driver.
 *
 * **M0 scaffold: no behavior.** This class exists to prove the core/driver seam
 * wires up (the module implements [GatewayDriver] against `:gateway-core`); the
 * real implementation lands across later milestones:
 * - `discover()` / `read()` — BLE transport + ELM327 session (M1).
 * - `normalize()` — PID codec → the `sensor_data` snapshot → [Observation] (M2).
 *
 * The methods intentionally throw [NotImplementedError] until then; nothing in the
 * app calls them at M0. GATT UUIDs / timings will be **config, not constants**, so
 * an OBDLink MX+ upgrade needs no rewrite (plan §9 risks).
 */
class ObdiiDriver : GatewayDriver {

    override val modality: Modality = Modality.OBDII

    override suspend fun discover(): List<DiscoveredDevice> =
        TODO("M1: scan for ELM327-over-BLE dongles")

    override suspend fun read(device: DiscoveredDevice): DriverReading =
        TODO("M1: BLE connect + ELM327 AT handshake + J1979 PID request/response")

    override fun normalize(reading: DriverReading): Observation =
        TODO("M2: PidCodec decode -> sensor_data snapshot -> Observation")
}
