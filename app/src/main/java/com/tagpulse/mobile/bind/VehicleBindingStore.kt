package com.tagpulse.mobile.bind

import android.content.Context
import android.content.SharedPreferences

/**
 * The current handset↔vehicle binding (ledger `C-RYH7`, Increment 2a). None of these
 * are secrets (the secret `tp_` key stays in the Keystore), so they persist in plain
 * [SharedPreferences].
 *
 * @property vin the **canonical** VIN — relayed as `TagReadCreate.tag_id`.
 * @property plate the vehicle's plate (`display_label`) shown for operator confirmation.
 * @property assetId the resolved backend asset id.
 */
data class VehicleBinding(
    val vin: String,
    val plate: String,
    val assetId: String,
)

/**
 * Persists the [VehicleBinding] the operator confirmed. Plain prefs (non-secret);
 * read at scan time so the reads carry the bound VIN as `tag_id`.
 */
class VehicleBindingStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The current binding, or `null` if the handset isn't bound to a vehicle yet. */
    val current: VehicleBinding?
        get() {
            val vin = prefs.getString(KEY_VIN, null) ?: return null
            val plate = prefs.getString(KEY_PLATE, null) ?: return null
            val assetId = prefs.getString(KEY_ASSET_ID, null) ?: return null
            return VehicleBinding(vin, plate, assetId)
        }

    /** Persist the confirmed binding (overwrites any prior one). */
    fun store(binding: VehicleBinding) {
        prefs.edit()
            .putString(KEY_VIN, binding.vin)
            .putString(KEY_PLATE, binding.plate)
            .putString(KEY_ASSET_ID, binding.assetId)
            .apply()
    }

    /** Clear the binding (e.g. to re-bind a different vehicle). */
    fun clear() {
        prefs.edit().remove(KEY_VIN).remove(KEY_PLATE).remove(KEY_ASSET_ID).apply()
    }

    private companion object {
        const val PREFS_NAME = "vehicle-binding"
        const val KEY_VIN = "vin"
        const val KEY_PLATE = "plate"
        const val KEY_ASSET_ID = "asset_id"
    }
}
