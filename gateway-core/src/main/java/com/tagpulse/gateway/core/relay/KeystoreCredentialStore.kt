package com.tagpulse.gateway.core.relay

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * A [CredentialStore] backed by the **Android Keystore** (AGENTS §2).
 *
 * Secrets are held in [EncryptedSharedPreferences], whose entries are encrypted at
 * rest under a hardware-backed [MasterKey] (`AES256_GCM`) in the `AndroidKeyStore`.
 * The plaintext `device_id` / `tp_…` API key therefore never touch source, resource
 * files, or plaintext on disk. `toString()` is overridden to **redact** so the key
 * can't leak through a log line.
 *
 * ### Test boundary (HIL, mirrors M1's real-BLE seam)
 * The real `AndroidKeyStore` provider is a device/emulator facility — Robolectric
 * does not implement it faithfully — so this class is **compile-only** in the unit
 * gate and validated **hardware-in-the-loop** (enrol on a handset → confirm the key
 * survives a restart and is never logged). The drainer / backend-client / mapping
 * unit tests drive a `FakeCredentialStore` (test source set) instead, so the relay
 * logic is fully covered without hardware. See plan §6 "Emulator/test note".
 *
 * @param fallbackBaseUrl the backend origin used until enrolment persists one (not a
 *   secret; a build-time default). Enrolment overwrites it via [store].
 */
class KeystoreCredentialStore(
    context: Context,
    fallbackBaseUrl: String,
    prefsName: String = DEFAULT_PREFS_NAME,
) : CredentialStore {

    private val fallbackBaseUrl: String = fallbackBaseUrl

    private val prefs: SharedPreferences = run {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * The enrolled backend origin, written at enrolment. Falls back to
     * [fallbackBaseUrl] until enrolment persists one. Not a secret, but kept beside
     * the enrolment facts so all setup state lives in one store.
     */
    override val baseUrl: String get() = prefs.getString(KEY_BASE_URL, null) ?: fallbackBaseUrl

    override val deviceId: String? get() = prefs.getString(KEY_DEVICE_ID, null)

    override val apiKey: String? get() = prefs.getString(KEY_API_KEY, null)

    /**
     * Persist the enrolment credentials **atomically** (one prefs edit): the device
     * UUID from provision→approve, the out-of-band tenant API key, and the enrolled
     * backend origin. All land encrypted at rest. Written only on a successful
     * enrolment, so a failed/aborted enrolment never leaves a half-written tuple.
     */
    fun store(deviceId: String, apiKey: String, baseUrl: String) {
        prefs.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_BASE_URL, baseUrl)
            .apply()
    }

    /** Wipe the stored credentials (e.g. on de-enrolment / key rotation). */
    fun clear() {
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_API_KEY)
            .remove(KEY_BASE_URL)
            .apply()
    }

    /** Redacted — never expose the API key (AGENTS §2). */
    override fun toString(): String =
        "KeystoreCredentialStore(baseUrl=$baseUrl, deviceId=${deviceId ?: "unset"}, apiKey=***redacted***)"

    companion object {
        /** EncryptedSharedPreferences file name (created in the app's prefs dir). */
        const val DEFAULT_PREFS_NAME: String = "gateway-credentials"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_API_KEY = "ingest_api_key"
        private const val KEY_BASE_URL = "base_url"
    }
}
