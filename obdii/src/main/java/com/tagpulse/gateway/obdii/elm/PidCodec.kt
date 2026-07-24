package com.tagpulse.gateway.obdii.elm

/**
 * Pure, synchronous decode of ELM327 / J1979 PID responses — the seed of the
 * future `PidCodec` (plan `docs/design/obdii-mve-plan.md` §3).
 *
 * **M1 scope: RPM (`010C`) only.** Speed / coolant / fuel-level decode and the
 * full `sensor_data` snapshot assembly are M2 — deliberately not implemented here.
 *
 * No I/O, no coroutines, no Android types → trivially unit-testable against canned
 * hex frames. The caller ([Elm327Session]) is responsible for reassembling BLE
 * notification fragments up to the `>` prompt *before* handing the full response
 * text here.
 */
object PidCodec {

    /** Positive-response header for mode-01 PID `0C` (RPM): `41 0C`. */
    private const val RPM_HEADER = "410C"

    private val HEX_CHARS = "0123456789ABCDEF".toSet()

    /**
     * Decode an RPM (`010C`) response into engine RPM.
     *
     * Handles the ELM327 quirks the handshake still leaves in the payload:
     * command echo lines, the `>` prompt, arbitrary whitespace, and spaces-on
     * (`41 0C 0D 48`) vs spaces-off (`410C0D48`, after `ATS0`) framing.
     *
     * RPM = ((A × 256) + B) / 4, where A/B are the two data bytes after the
     * `41 0C` header (J1979). The `sensor_data` snapshot models RPM as an integer
     * (plan §4), so the ¼-rpm resolution is truncated toward zero.
     *
     * Never throws: `NO DATA` / `?` / `UNABLE TO CONNECT` / unparseable input map
     * to [RpmReading.Failure] (plan §6).
     *
     * @param response the full reassembled ELM327 response text (prompt included).
     */
    fun decodeRpm(response: String): RpmReading {
        val text = response.uppercase()

        // Adapter/ECU status tokens take precedence over any partial hex.
        when {
            text.contains("NO DATA") -> return RpmReading.Failure(ObdError.NO_DATA)
            text.contains("UNABLE TO CONNECT") -> return RpmReading.Failure(ObdError.UNABLE_TO_CONNECT)
            text.contains('?') -> return RpmReading.Failure(ObdError.UNSUPPORTED_COMMAND)
        }

        // The response may span several lines (echo, blank lines, prompt). Find the
        // one hex line carrying the positive `41 0C ..` frame.
        for (rawLine in text.split('\r', '\n', '>')) {
            val hex = rawLine.filterNot { it.isWhitespace() }
            if (hex.isEmpty() || hex.any { it !in HEX_CHARS }) continue
            if (!hex.startsWith(RPM_HEADER)) continue

            val bytes = hex.chunked(2)
            // Need header (41, 0C) + two data bytes (A, B); each a full 2-char byte.
            if (bytes.size < 4 || bytes.any { it.length != 2 }) {
                return RpmReading.Failure(ObdError.MALFORMED)
            }
            val a = bytes[2].toInt(16)
            val b = bytes[3].toInt(16)
            return RpmReading.Value(((a shl 8) + b) / 4)
        }

        return RpmReading.Failure(ObdError.MALFORMED)
    }
}
