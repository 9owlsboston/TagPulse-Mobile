package com.tagpulse.mobile.enrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/**
 * Pure-JVM tests for [EnrolmentQrCode.parse] (ledger `C-RYH7`, Increment 1b). No
 * Android / camera — the ML Kit/CameraX capture glue is HIL; this locks the payload
 * contract the scanner feeds into enrolment.
 */
class EnrolmentQrCodeTest {

    private fun qr(base: String, pkey: String): String =
        "tagpulse://enrol?base=${URLEncoder.encode(base, "UTF-8")}&pkey=${URLEncoder.encode(pkey, "UTF-8")}"

    @Test
    fun `parses a well-formed enrolment QR with url-encoded base`() {
        val payload = EnrolmentQrCode.parse(qr("https://api.tenant.example:8443", "prov-key-xyz"))
        assertEquals("https://api.tenant.example:8443", payload!!.baseUrl)
        assertEquals("prov-key-xyz", payload.provisioningKey)
    }

    @Test
    fun `scheme and host are case-insensitive`() {
        val payload = EnrolmentQrCode.parse("TagPulse://Enrol?base=https%3A%2F%2Fapi.x.example&pkey=k")
        assertEquals("https://api.x.example", payload!!.baseUrl)
        assertEquals("k", payload.provisioningKey)
    }

    @Test
    fun `wrong scheme is rejected`() {
        assertNull(EnrolmentQrCode.parse(qr("https://api.x.example", "k").replace("tagpulse://", "https://")))
    }

    @Test
    fun `wrong host is rejected`() {
        assertNull(EnrolmentQrCode.parse("tagpulse://setup?base=https%3A%2F%2Fapi.x.example&pkey=k"))
    }

    @Test
    fun `missing base is rejected`() {
        assertNull(EnrolmentQrCode.parse("tagpulse://enrol?pkey=k"))
    }

    @Test
    fun `missing pkey is rejected`() {
        assertNull(EnrolmentQrCode.parse("tagpulse://enrol?base=https%3A%2F%2Fapi.x.example"))
    }

    @Test
    fun `blank pkey is rejected`() {
        assertNull(EnrolmentQrCode.parse("tagpulse://enrol?base=https%3A%2F%2Fapi.x.example&pkey="))
    }

    @Test
    fun `non-https base is rejected`() {
        assertNull(EnrolmentQrCode.parse(qr("http://api.x.example", "k")))
    }

    @Test
    fun `malformed base is rejected`() {
        assertNull(EnrolmentQrCode.parse("tagpulse://enrol?base=not%20a%20url&pkey=k"))
    }

    @Test
    fun `arbitrary non-uri text returns null, never throws`() {
        assertNull(EnrolmentQrCode.parse("just some random qr contents"))
        assertNull(EnrolmentQrCode.parse(""))
        assertNull(EnrolmentQrCode.parse(null))
        assertNull(EnrolmentQrCode.parse("://:::"))
    }

    @Test
    fun `ProvisioningPayload toString redacts the provisioning key`() {
        val s = ProvisioningPayload("https://api.x.example", "prov-key-xyz").toString()
        assertFalse(s.contains("prov-key-xyz"))
        assertTrue(s.contains("redacted"))
        assertTrue(s.contains("https://api.x.example"))
    }
}
