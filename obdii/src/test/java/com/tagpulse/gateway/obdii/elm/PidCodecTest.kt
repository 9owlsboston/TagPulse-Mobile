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
}
