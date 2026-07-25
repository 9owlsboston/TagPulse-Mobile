package com.tagpulse.mobile.enrol

/**
 * The operator-supplied inputs for one enrolment attempt (`docs/design/enrolment-flow.md`).
 *
 * Two of these fields are **sensitive** — [provisioningKey] (tenant-scoped) and
 * [ingestApiKey] (the broad `tp_…` tenant key) — so this is **not** a `data class`:
 * its [toString] is redacted so a stray log line can't leak them (AGENTS §2).
 *
 * @property baseUrl the backend origin (from the enrolment QR or manual entry); must
 *   be a valid `https://` URL.
 * @property provisioningKey the tenant `X-Provisioning-Key` (QR/manual).
 * @property ingestApiKey the tenant user API key (`tp_…`) pasted out-of-band.
 * @property deviceName a human label for the provisioned device.
 */
class EnrolmentInput(
    val baseUrl: String,
    val provisioningKey: String,
    val ingestApiKey: String,
    val deviceName: String,
) {
    /** Redacted — never expose the provisioning key or the `tp_` ingest key. */
    override fun toString(): String =
        "EnrolmentInput(baseUrl=$baseUrl, deviceName=$deviceName, " +
            "provisioningKey=***redacted***, ingestApiKey=***redacted***)"
}
