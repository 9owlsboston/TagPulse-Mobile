package com.tagpulse.mobile.bind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM tests for [VinBarcode.extract] (ledger `C-RYH7`, Increment 2c). */
class VinBarcodeTest {

    private val vin = "1HGCM82633A004352"

    @Test
    fun `extracts a bare 17-char VIN`() {
        assertEquals(vin, VinBarcode.extract(vin))
    }

    @Test
    fun `trims and upper-cases`() {
        assertEquals(vin, VinBarcode.extract("  1hgcm82633a004352 "))
    }

    @Test
    fun `strips a leading AIAG I data identifier`() {
        assertEquals(vin, VinBarcode.extract("I$vin"))
    }

    @Test
    fun `rejects a non-VIN-length barcode`() {
        assertNull(VinBarcode.extract("12345"))
        assertNull(VinBarcode.extract("1HGCM82633A00435"))    // 16
        assertNull(VinBarcode.extract("1HGCM82633A0043521")) // 18, no leading I
    }

    @Test
    fun `rejects a barcode with non-alphanumeric characters`() {
        assertNull(VinBarcode.extract("1HGCM82633A00435-"))
        assertNull(VinBarcode.extract("*1HGCM82633A00435"))
    }

    @Test
    fun `rejects null and blank`() {
        assertNull(VinBarcode.extract(null))
        assertNull(VinBarcode.extract("   "))
    }
}
