package com.tagpulse.gateway.core.relay

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.tagpulse.gateway.core.api.model.TagReadCreate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * The default [BackendClient]: a **thin OkHttp transport over the generated
 * models** (AGENTS §2 — the hard rule is that *models* stay generated; a small
 * hand-written transport that serializes the generated [TagReadCreate] is allowed,
 * see `contract/CONTRACT.md` "Transport decision").
 *
 * Reads [CredentialStore.baseUrl] / [CredentialStore.apiKey] **per call** so a
 * rotation takes effect immediately. The API key is only ever placed in the
 * `Authorization` header — never logged, and never echoed into a [BatchResult]
 * reason (AGENTS §2).
 *
 * @param credentials source of the base URL + ingest bearer key.
 * @param client the OkHttp client (inject a MockWebServer-backed one in tests).
 * @param mapper Jackson mapper (the serialization stack the repo already uses).
 */
class OkHttpBackendClient(
    private val credentials: CredentialStore,
    private val client: OkHttpClient = OkHttpClient(),
    private val mapper: ObjectMapper = defaultMapper(),
) : BackendClient {

    override suspend fun postTagReadsBatch(reads: List<TagReadCreate>): BatchResult {
        val key = credentials.apiKey
        if (key.isNullOrBlank()) {
            return BatchResult.CredentialError("no ingest API key present")
        }
        val body = mapper.writeValueAsString(reads).toRequestBody(JSON)
        val request = Request.Builder()
            .url(url("tag-reads/batch"))
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { resp ->
                    val payload = resp.body?.string().orEmpty()
                    when {
                        resp.isSuccessful -> parseBatchBody(payload)
                        resp.code == 401 ->
                            // Do NOT include the body verbatim (avoid leaking the key
                            // if the server ever echoed it) — a fixed, safe message.
                            BatchResult.CredentialError("ingest rejected the API key (401)")
                        resp.code == 408 ->
                            // Request Timeout — transient; back off and retry.
                            BatchResult.Retryable("request timeout (408)")
                        resp.code == 429 ->
                            // Too Many Requests — transient; honor Retry-After if given.
                            BatchResult.Retryable(
                                "rate limited (429)",
                                retryAfterMillis = parseRetryAfterMillis(resp),
                            )
                        resp.code in 500..599 ->
                            BatchResult.Retryable("server error ${resp.code}")
                        else ->
                            BatchResult.Terminal(resp.code, "rejected (${resp.code})")
                    }
                }
            } catch (e: IOException) {
                // Network / timeout / connection reset — transient, retry with backoff.
                BatchResult.Retryable("network error: ${e.javaClass.simpleName}")
            }
        }
    }

    override suspend fun provisionDevice(
        provisioningKey: String,
        name: String,
        deviceType: String,
    ): ProvisionResult {
        val body = mapper.writeValueAsString(
            mapOf("name" to name, "device_type" to deviceType),
        ).toRequestBody(JSON)
        val request = Request.Builder()
            .url(url("devices/provision"))
            .header("X-Provisioning-Key", provisioningKey)
            .post(body)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { resp ->
                    val payload = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        parseProvisionBody(payload)
                    } else {
                        ProvisionResult.Failed(resp.code, "provision rejected (${resp.code})")
                    }
                }
            } catch (e: IOException) {
                ProvisionResult.Failed(null, "network error: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * The `Retry-After` directive on a `429`, in millis — **delta-seconds form only**
     * (e.g. `Retry-After: 30` → `30_000`). The HTTP-date form is not honored (returns
     * `null` → the drainer falls back to its exponential backoff); delta-seconds is the
     * form servers use for rate limiting, and this keeps the transport wall-clock-free.
     * A negative, non-numeric, or absurdly large (overflowing) value is ignored.
     */
    private fun parseRetryAfterMillis(resp: Response): Long? {
        val seconds = resp.header("Retry-After")?.trim()?.toLongOrNull() ?: return null
        // Ignore negatives and clamp against Long overflow on `* 1000` (a wrapped
        // negative would defeat the drainer's defer check).
        if (seconds < 0 || seconds > Long.MAX_VALUE / 1000) return null
        return seconds * 1000
    }

    private fun parseBatchBody(payload: String): BatchResult =
        try {
            val parsed: Map<String, Int> = mapper.readValue(payload, INT_MAP)
            BatchResult.Accepted(
                ingested = parsed["ingested"] ?: 0,
                rejected = parsed["rejected"] ?: 0,
            )
        } catch (e: Exception) {
            // 2xx but an unparseable body → treat as terminal (won't succeed on retry).
            BatchResult.Terminal(200, "unparseable success body: ${e.javaClass.simpleName}")
        }

    private fun parseProvisionBody(payload: String): ProvisionResult =
        try {
            val parsed: Map<String, Any?> = mapper.readValue(payload, ANY_MAP)
            val deviceId = parsed["device_id"] as? String
            if (deviceId.isNullOrBlank()) {
                ProvisionResult.Failed(null, "provision response missing device_id")
            } else {
                ProvisionResult.Registered(
                    deviceId = deviceId,
                    status = (parsed["status"] as? String) ?: "pending",
                )
            }
        } catch (e: Exception) {
            ProvisionResult.Failed(null, "unparseable provision body: ${e.javaClass.simpleName}")
        }

    private fun url(path: String): String =
        "${credentials.baseUrl.trimEnd('/')}/$path"

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val INT_MAP = object :
            com.fasterxml.jackson.core.type.TypeReference<Map<String, Int>>() {}
        private val ANY_MAP = object :
            com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}

        /** Kotlin-aware, lenient mapper matching the outbox codec's stack. */
        fun defaultMapper(): ObjectMapper = ObjectMapper()
            .registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
