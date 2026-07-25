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

    // Mode 09 PID 02 (VIN) positive-response header + NODI byte: `49 02 01`.
    private const val VIN_HEADER_NODI = "490201"

    /** VIN length in characters and the hex-string length that encodes it. */
    private const val VIN_LEN = 17
    private const val VIN_HEX_LEN = VIN_LEN * 2

    // A VIN is uppercase letters + digits (the ISO-3779 subset excludes I/O/Q, but the
    // decoder accepts the full alphanumeric superset — the app's Vin.isValid is authoritative).
    private val VIN_ALPHANUMERIC = (('A'..'Z') + ('0'..'9')).toSet()

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
     * Decode a Mode 09 PID 02 **VIN** (`0902`) response into the 17-character VIN
     * (ledger `C-RYH7` Increment 2b).
     *
     * Unlike the single-frame Mode 01 PIDs, the VIN is a **multi-frame** response.
     * Scoped to **CAN / ISO 15765-4** (the ISO-TP coalesced form ELM327 emits for
     * modern vehicles): the positive response is `49 02 01` (mode+0x40, PID, NODI=1)
     * followed by the 17 VIN bytes as ASCII, delivered across ISO-TP segment lines
     * (`0:`/`1:`/`2:`), optionally preceded by a length line (`014`), with per-frame
     * CAN padding. Legacy J1850/ISO-9141 multi-packet VINs (repeated `49 02 <seq>`
     * headers) are **not** parsed — they cleanly return [ObdError.MALFORMED] so the
     * bind flow falls back to manual VIN entry (documented; pre-2008 vehicles only).
     *
     * Robustness: every `490201` candidate is evaluated (guards against a stray
     * `4902` in padding/junk and multi-ECU echoes); a candidate is accepted only if
     * it yields exactly 17 alphanumeric VIN bytes. Exactly one **distinct** valid VIN
     * → [VinReading.Value]; zero or conflicting candidates → [VinReading.Failure].
     * Never throws.
     */
    fun decodeVin(response: String): VinReading {
        val text = response.uppercase()

        when {
            text.contains("NO DATA") -> return VinReading.Failure(ObdError.NO_DATA)
            text.contains("UNABLE TO CONNECT") -> return VinReading.Failure(ObdError.UNABLE_TO_CONNECT)
            text.contains('?') -> return VinReading.Failure(ObdError.UNSUPPORTED_COMMAND)
        }

        // Concatenate hex from every line, dropping an ISO-TP segment index ("<hex>:")
        // and any non-hex line (echo, blank, the '>' prompt).
        val hex = StringBuilder()
        for (rawLine in text.split('\r', '\n', '>')) {
            var line = rawLine.trim()
            val colon = line.indexOf(':')
            // A leading segment index is 1–2 hex digits then ':'. Strip it; a bare
            // length line ("014", no colon) is left in — indexOf("490201") skips it.
            if (colon in 1..2 && line.take(colon).all { it in HEX_CHARS }) {
                line = line.substring(colon + 1)
            }
            val cleaned = line.filterNot { it.isWhitespace() }
            if (cleaned.isEmpty() || cleaned.any { it !in HEX_CHARS }) continue
            hex.append(cleaned)
        }
        val all = hex.toString()

        // Evaluate every 49 02 01 (header + NODI=1) candidate; keep the distinct
        // 17-char alphanumeric VINs.
        val vins = LinkedHashSet<String>()
        var from = all.indexOf(VIN_HEADER_NODI)
        while (from >= 0) {
            val start = from + VIN_HEADER_NODI.length
            if (start + VIN_HEX_LEN <= all.length) {
                val vinHex = all.substring(start, start + VIN_HEX_LEN)
                decodeAsciiVin(vinHex)?.let { vins.add(it) }
            }
            from = all.indexOf(VIN_HEADER_NODI, from + VIN_HEADER_NODI.length)
        }

        return when (vins.size) {
            1 -> VinReading.Value(vins.first())
            else -> VinReading.Failure(ObdError.MALFORMED) // 0 (none) or >1 (ambiguous)
        }
    }

    /** Decode 34 hex chars → 17 ASCII chars; null unless all 17 are VIN-alphanumeric. */
    private fun decodeAsciiVin(vinHex: String): String? {
        val sb = StringBuilder(VIN_LEN)
        for (i in 0 until VIN_HEX_LEN step 2) {
            val code = vinHex.substring(i, i + 2).toIntOrNull(16) ?: return null
            val c = code.toChar()
            if (c !in VIN_ALPHANUMERIC) return null
            sb.append(c)
        }
        return sb.toString()
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
