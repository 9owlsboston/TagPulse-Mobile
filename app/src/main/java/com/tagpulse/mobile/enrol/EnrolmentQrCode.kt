package com.tagpulse.mobile.enrol

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * The two enrolment facts an enrolment QR carries (OQ2): the backend origin and the
 * tenant provisioning key. The sensitive `tp_` ingest key is **not** in the QR — it is
 * pasted separately, kept off the printed/displayed artifact.
 *
 * Not a `data class`: [provisioningKey] is a tenant secret, so [toString] is **redacted**
 * to keep it out of any stray log line (AGENTS §2).
 */
class ProvisioningPayload(
    val baseUrl: String,
    val provisioningKey: String,
) {
    override fun toString(): String =
        "ProvisioningPayload(baseUrl=$baseUrl, provisioningKey=***redacted***)"
}

/**
 * Pure parser for the **enrolment QR** payload (ledger `C-RYH7`, Increment 1b).
 *
 * Format (defined here — net-new; the backend/admin tooling emits it):
 * ```
 * tagpulse://enrol?base=<url-encoded https origin>&pkey=<provisioning key>
 * ```
 * Per OQ2 the QR carries **only** the base URL + provisioning key (never the `tp_`
 * ingest key). [parse] is **pure** (no Android, no I/O — unit-tested on the JVM) and
 * **never throws** for arbitrary input: any malformed / non-matching / non-`https`
 * QR yields `null`. It never logs the decoded value.
 */
object EnrolmentQrCode {

    /** URI scheme (matched case-insensitively). */
    const val SCHEME: String = "tagpulse"

    /** URI authority/host (matched case-insensitively). */
    const val HOST: String = "enrol"

    private const val PARAM_BASE = "base"
    private const val PARAM_KEY = "pkey"

    /**
     * Parse a scanned QR string into a [ProvisioningPayload], or `null` if it is not a
     * well-formed `tagpulse://enrol` payload with a non-blank `pkey` and an `https`
     * `base`.
     */
    fun parse(raw: String?): ProvisioningPayload? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        val uri = try {
            URI(text)
        } catch (e: Exception) {
            return null
        }

        if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
        // `enrol` is the authority/host in `tagpulse://enrol?...`.
        val host = uri.host ?: uri.authority
        if (!HOST.equals(host, ignoreCase = true)) return null

        val params = parseQuery(uri.rawQuery)
        val base = params[PARAM_BASE]?.trim().orEmpty()
        val key = params[PARAM_KEY]?.trim().orEmpty()
        if (base.isEmpty() || key.isEmpty()) return null
        if (!isHttpsUrl(base)) return null

        return ProvisioningPayload(baseUrl = base, provisioningKey = key)
    }

    /** Decode an `a=b&c=d` raw query into a map (last value wins); tolerant of junk. */
    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = decode(pair.substring(0, eq))
            val value = decode(pair.substring(eq + 1))
            out[name] = value
        }
        return out
    }

    private fun decode(s: String): String =
        try {
            URLDecoder.decode(s, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            s
        }

    private fun isHttpsUrl(url: String): Boolean =
        try {
            val u = URI(url)
            "https".equals(u.scheme, ignoreCase = true) && !u.host.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
}
