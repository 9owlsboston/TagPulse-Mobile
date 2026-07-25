package com.tagpulse.mobile.enrol

/**
 * The two enrolment facts an enrolment QR carries (OQ2 decision): the backend origin
 * and the tenant provisioning key. The sensitive `tp_` ingest key is **not** in the
 * QR — it is pasted separately (kept off the printed/displayed artifact).
 */
data class ProvisioningPayload(
    val baseUrl: String,
    val provisioningKey: String,
)

/**
 * Seam for obtaining a [ProvisioningPayload] by scanning the enrolment QR (OQ1).
 *
 * Increment 1 ships this interface + a test fake and wires the manual-entry path; the
 * camera implementation (ML Kit barcode + CameraX, bundled — no Play Services) is
 * **Increment 1b** (HIL). The `EnrolScreen` shows a "Scan QR" affordance only when a
 * scanner is wired, so there is no dead button while the impl is pending.
 *
 * Returns `null` if the operator cancels the scan.
 */
fun interface ProvisioningScanner {
    suspend fun scan(): ProvisioningPayload?
}
