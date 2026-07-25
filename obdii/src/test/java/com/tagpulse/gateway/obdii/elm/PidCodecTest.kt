package com.tagpulse.gateway.obdii.elm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function tests for RPM (`010C`) decode — canned hex frames, no hardware
 * (plan `docs/design/obdii-mve-plan.md` §6 "PidCodec unit-tested against canned
 * hex frames").
 */
class PidCodecTest {

    @Test
    fun `canonical 41 0C 0D 48 decodes to 850 rpm`() {
        // ((0x0D * 256) + 0x48) / 4 = (3328 + 72) / 4 = 850.
        val result = PidCodec.decodeRpm("41 0C 0D 48\r\r>")
        assertEquals(RpmReading.Value(850), result)
    }

    @Test
    fun `spaces-off framing 410C0D48 (after ATS0) decodes to 850`() {
        assertEquals(RpmReading.Value(850), PidCodec.decodeRpm("410C0D48\r>"))
    }

    @Test
    fun `whitespace-noisy, lowercase, echoed response still decodes to 850`() {
        // Command echo line + irregular spacing + lowercase hex + prompt.
        val raw = "010C\r 41 0c   0d 48 \r\r>"
        assertEquals(RpmReading.Value(850), PidCodec.decodeRpm(raw))
    }

    @Test
    fun `idle-ish 41 0C 00 00 decodes to 0 rpm`() {
        assertEquals(RpmReading.Value(0), PidCodec.decodeRpm("41 0C 00 00\r>"))
    }

    @Test
    fun `NO DATA is a clean failure, not an exception`() {
        assertEquals(RpmReading.Failure(ObdError.NO_DATA), PidCodec.decodeRpm("NO DATA\r>"))
    }

    @Test
    fun `question-mark (command not understood) maps to UNSUPPORTED_COMMAND`() {
        assertEquals(RpmReading.Failure(ObdError.UNSUPPORTED_COMMAND), PidCodec.decodeRpm("?\r>"))
    }

    @Test
    fun `UNABLE TO CONNECT maps to that reason`() {
        assertEquals(
            RpmReading.Failure(ObdError.UNABLE_TO_CONNECT),
            PidCodec.decodeRpm("UNABLE TO CONNECT\r>"),
        )
    }

    @Test
    fun `truncated header without data bytes is MALFORMED`() {
        assertEquals(RpmReading.Failure(ObdError.MALFORMED), PidCodec.decodeRpm("41 0C\r>"))
    }

    @Test
    fun `garbage with no positive header is MALFORMED`() {
        assertEquals(RpmReading.Failure(ObdError.MALFORMED), PidCodec.decodeRpm("ZZZZ\r>"))
    }

    // --- Speed (010D): value = A km/h -------------------------------------------------

    @Test
    fun `canonical 41 0D 32 decodes to 50 kph`() {
        assertEquals(PidReading.Value(50), PidCodec.decodeSpeed("41 0D 32\r\r>"))
    }

    @Test
    fun `spaces-off framing 410D00 decodes to 0 kph`() {
        assertEquals(PidReading.Value(0), PidCodec.decodeSpeed("410D00\r>"))
    }

    @Test
    fun `speed NO DATA is a clean failure`() {
        assertEquals(PidReading.Failure(ObdError.NO_DATA), PidCodec.decodeSpeed("NO DATA\r>"))
    }

    @Test
    fun `speed decode of a wrong-header (RPM) frame is MALFORMED, not a value`() {
        assertEquals(PidReading.Failure(ObdError.MALFORMED), PidCodec.decodeSpeed("41 0C 0D 48\r>"))
    }

    // --- Coolant temp (0105): value = A - 40 °C ---------------------------------------

    @Test
    fun `canonical 41 05 7B decodes to 83 celsius`() {
        // 0x7B = 123; 123 - 40 = 83.
        assertEquals(PidReading.Value(83), PidCodec.decodeCoolantTemp("41 05 7B\r\r>"))
    }

    @Test
    fun `cold-engine 41 05 14 decodes to negative -20 celsius`() {
        // 0x14 = 20; 20 - 40 = -20.
        assertEquals(PidReading.Value(-20), PidCodec.decodeCoolantTemp("41 05 14\r>"))
    }

    @Test
    fun `coolant malformed (header only) is a clean failure`() {
        assertEquals(PidReading.Failure(ObdError.MALFORMED), PidCodec.decodeCoolantTemp("41 05\r>"))
    }

    @Test
    fun `coolant question-mark maps to UNSUPPORTED_COMMAND`() {
        assertEquals(PidReading.Failure(ObdError.UNSUPPORTED_COMMAND), PidCodec.decodeCoolantTemp("?\r>"))
    }

    // --- Fuel level (012F): value = A * 100 / 255 % (float) ----------------------------

    @Test
    fun `canonical 41 2F 7F decodes to about 49-point-8 percent`() {
        // 0x7F = 127; 127 * 100 / 255 = 49.803...; kept to one decimal -> 49.8.
        assertEquals(PidReading.Value(49.8f), PidCodec.decodeFuelLevel("41 2F 7F\r\r>"))
    }

    @Test
    fun `fuel full 41 2F FF decodes to 100 percent`() {
        assertEquals(PidReading.Value(100.0f), PidCodec.decodeFuelLevel("41 2F FF\r>"))
    }

    @Test
    fun `fuel empty 41 2F 00 decodes to 0 percent`() {
        assertEquals(PidReading.Value(0.0f), PidCodec.decodeFuelLevel("412F00\r>"))
    }

    @Test
    fun `fuel UNABLE TO CONNECT is a clean failure`() {
        assertEquals(
            PidReading.Failure(ObdError.UNABLE_TO_CONNECT),
            PidCodec.decodeFuelLevel("UNABLE TO CONNECT\r>"),
        )
    }
}
