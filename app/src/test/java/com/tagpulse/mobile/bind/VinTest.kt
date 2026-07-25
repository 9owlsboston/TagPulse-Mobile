package com.tagpulse.mobile.bind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [Vin] (ledger `C-RYH7`, Increment 2a). */
class VinTest {

    // ISO 3779 canonical example VIN; the check digit (position 9) is '3'.
    private val validVin = "1HGCM82633A004352"

    @Test
    fun `canonical trims and upper-cases`() {
        assertEquals(validVin, Vin.canonical("  1hgcm82633a004352 "))
    }

    @Test
    fun `isValid accepts a 17-char VIN in the alphabet`() {
        assertTrue(Vin.isValid(validVin))
    }

    @Test
    fun `isValid rejects wrong length`() {
        assertFalse(Vin.isValid("1HGCM82633A00435"))   // 16
        assertFalse(Vin.isValid("1HGCM82633A0043521")) // 18
    }

    @Test
    fun `isValid rejects the excluded letters I O Q`() {
        assertFalse(Vin.isValid("1HGCM82633A0043I2"))
        assertFalse(Vin.isValid("1HGCM82633A0043O2"))
        assertFalse(Vin.isValid("1HGCM82633A0043Q2"))
    }

    @Test
    fun `checkDigitValid is true for a correct check digit`() {
        assertTrue(Vin.checkDigitValid(validVin))
    }

    @Test
    fun `checkDigitValid is false for a wrong check digit`() {
        // Same VIN with the check digit flipped from 3 to 4.
        assertFalse(Vin.checkDigitValid("1HGCM82634A004352"))
    }

    @Test
    fun `checkDigitValid is false for a structurally invalid VIN`() {
        assertFalse(Vin.checkDigitValid("short"))
    }
}
