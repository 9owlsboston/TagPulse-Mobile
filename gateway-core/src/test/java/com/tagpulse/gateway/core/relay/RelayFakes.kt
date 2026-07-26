package com.tagpulse.gateway.core.relay

import com.tagpulse.gateway.core.api.model.TagReadCreate

/**
 * In-memory [CredentialStore] for driving the relay tests without hardware —
 * the compile-only [KeystoreCredentialStore] needs a real `AndroidKeyStore`
 * (HIL). Mutable so tests can simulate enrolment / rotation.
 */
class FakeCredentialStore(
    override var baseUrl: String = "http://localhost",
    override var deviceId: String? = "11111111-2222-3333-4444-555555555555",
    override var apiKey: String? = "tp_acme_UNIT_TEST_KEY",
) : CredentialStore

/**
 * Scriptable [BackendClient] for the [Drainer] tests. Returns the queued
 * [BatchResult]s in order (repeating the last once exhausted) and records every
 * batch it was asked to relay so a test can assert re-sends / duplicates.
 */
class FakeBackendClient(
    private val scripted: MutableList<BatchResult> = mutableListOf(),
) : BackendClient {

    /** Every batch handed to [postTagReadsBatch], in call order (for duplicate asserts). */
    val sentBatches: MutableList<List<TagReadCreate>> = mutableListOf()

    /** Number of relay calls made. */
    val callCount: Int get() = sentBatches.size

    /** Queue a result to be returned by the next call(s). */
    fun enqueue(vararg results: BatchResult) = apply { scripted.addAll(results) }

    override suspend fun postTagReadsBatch(reads: List<TagReadCreate>): BatchResult {
        sentBatches.add(reads)
        return when {
            scripted.isEmpty() -> BatchResult.Accepted(ingested = reads.size, rejected = 0)
            scripted.size == 1 -> scripted.first()
            else -> scripted.removeAt(0)
        }
    }

    override suspend fun provisionDevice(
        provisioningKey: String,
        name: String,
        deviceType: String,
    ): ProvisionResult = ProvisionResult.Registered(deviceId = "fake-device", status = "pending")

    override suspend fun resolveAssetByBinding(value: String): AssetLookupResult =
        AssetLookupResult.Resolved(assetId = "fake-asset", displayLabel = "PLATE-000", bindingKind = "device")
}
