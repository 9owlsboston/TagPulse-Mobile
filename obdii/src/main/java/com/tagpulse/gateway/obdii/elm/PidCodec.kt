package com.tagpulse.gateway.obdii.elm

import kotlin.math.round

/**
 * Pure, synchronous decode of ELM327 / J1979 PID responses (plan
 * `docs/design/obdii-mve-plan.md` §3, §6).
 *
 * **M2 scope: all four MVE PIDs** — RPM (`010C`), speed (`010D`), coolant temp
 * (`0105`) and fuel level (`012F`). Every decode is a pure function of the
 * reassembled response text: no I/O, no coroutines, no Android types → trivially
 * unit-testable against canned hex frames.
 *
 * The caller ([Elm327Session]) reassembles BLE notification fragments up to the
 * `>` prompt *before* handing the full response text here. All decoders share
 * [parseFrame], which handles the ELM327 quirks the handshake leaves behind:
 * command echo lines, the `>` prompt, arbitrary whitespace, lowercase hex, and
 * spaces-on (`41 0D 32`) vs spaces-off (`410D32`, after `ATS0`) framing. Adapter/
 * ECU status tokens (`NO DATA` / `?` / `UNABLE TO CONNECT`) and malformed / wrong-
 * header frames map to a clean `Failure` — a decode never throws (plan §6).
 */
object PidCodec {

    /** Positive-response headers for the four MVE mode-01 PIDs (`41 <pid>`). */
    private const val RPM_HEADER = "410C"
    private const val SPEED_HEADER = "410D"
    private const val COOLANT_HEADER = "4105"
    private const val FUEL_HEADER = "412F"

    private val HEX_CHARS = "0123456789ABCDEF".toSet()

    /**
     * Decode an RPM (`010C`) response into engine RPM.
     *
     * RPM = ((A × 256) + B) / 4, where A/B are the two data bytes after the
     * `41 0C` header (J1979). The `sensor_data` snapshot models RPM as an integer
     * (plan §4), so the ¼-rpm resolution is truncated toward zero.
     *
     * Kept returning [RpmReading] (not the parametric [PidReading]) so the M1
     * `readRpm()` path and its tests are unchanged.
     */
    fun decodeRpm(response: String): RpmReading =
        when (val frame = parseFrame(response, RPM_HEADER, minDataBytes = 2)) {
            is Frame.Bytes -> RpmReading.Value(((frame.data[0] shl 8) + frame.data[1]) / 4)
            is Frame.Bad -> RpmReading.Failure(frame.reason)
        }

    /**
     * Decode a vehicle-speed (`010D`) response into km/h.
     *
     * Speed = A (a single data byte after the `41 0D` header), 0–255 km/h (J1979).
     */
    fun decodeSpeed(response: String): PidReading<Int> =
        when (val frame = parseFrame(response, SPEED_HEADER, minDataBytes = 1)) {
            is Frame.Bytes -> PidReading.Value(frame.data[0])
            is Frame.Bad -> PidReading.Failure(frame.reason)
        }

    /**
     * Decode a coolant-temperature (`0105`) response into °C.
     *
     * Coolant = A − 40 (single data byte after the `41 05` header), so it can be
     * negative on a cold engine, e.g. `41 05 14` → 20 − 40 = −20 °C (J1979).
     */
    fun decodeCoolantTemp(response: String): PidReading<Int> =
        when (val frame = parseFrame(response, COOLANT_HEADER, minDataBytes = 1)) {
            is Frame.Bytes -> PidReading.Value(frame.data[0] - 40)
            is Frame.Bad -> PidReading.Failure(frame.reason)
        }

    /**
     * Decode a fuel-level (`012F`) response into a percentage.
     *
     * Fuel = A × 100 / 255 (single data byte after the `41 2F` header) — a float,
     * e.g. `41 2F 7F` → 127 × 100 / 255 ≈ 49.8 %. Kept to one-decimal precision to
     * match the `sensor_data` example (plan §4).
     */
    fun decodeFuelLevel(response: String): PidReading<Float> =
        when (val frame = parseFrame(response, FUEL_HEADER, minDataBytes = 1)) {
            is Frame.Bytes -> {
                val pct = frame.data[0] * 100f / 255f
                PidReading.Value(round(pct * 10f) / 10f)
            }
            is Frame.Bad -> PidReading.Failure(frame.reason)
        }

    /**
     * The reassembled-frame parse shared by every decoder: strip echoes /
     * whitespace / prompt, reject adapter/ECU error tokens, find the positive
     * `41 <pid>` line, and return its data bytes (those after the header) — or a
     * typed failure reason. Never throws.
     */
    private fun parseFrame(response: String, header: String, minDataBytes: Int): Frame {
        val text = response.uppercase()

        // Adapter/ECU status tokens take precedence over any partial hex.
        when {
            text.contains("NO DATA") -> return Frame.Bad(ObdError.NO_DATA)
            text.contains("UNABLE TO CONNECT") -> return Frame.Bad(ObdError.UNABLE_TO_CONNECT)
            text.contains('?') -> return Frame.Bad(ObdError.UNSUPPORTED_COMMAND)
        }

        // The response may span several lines (echo, blank lines, prompt). Find the
        // one hex line carrying the positive `41 <pid> ..` frame.
        for (rawLine in text.split('\r', '\n', '>')) {
            val hex = rawLine.filterNot { it.isWhitespace() }
            if (hex.isEmpty() || hex.any { it !in HEX_CHARS }) continue
            if (!hex.startsWith(header)) continue

            val bytes = hex.chunked(2)
            if (bytes.any { it.length != 2 }) return Frame.Bad(ObdError.MALFORMED)
            val data = bytes.drop(header.length / 2)
            if (data.size < minDataBytes) return Frame.Bad(ObdError.MALFORMED)
            return Frame.Bytes(data.map { it.toInt(16) })
        }

        return Frame.Bad(ObdError.MALFORMED)
    }

    /** Parse outcome: the positive-response data bytes, or a typed failure. */
    private sealed interface Frame {
        data class Bytes(val data: List<Int>) : Frame
        data class Bad(val reason: ObdError) : Frame
    }
}
