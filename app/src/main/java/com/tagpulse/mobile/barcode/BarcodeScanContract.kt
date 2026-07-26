package com.tagpulse.mobile.barcode

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/**
 * Launches [BarcodeScanActivity] to scan a barcode of one of [formats] and returns the
 * **raw** decoded string, or `null` if the operator cancelled / denied the camera / no
 * matching barcode was read (ledger `C-RYH7`; generalizes the Increment 1b QR scanner).
 *
 * @param formats the ML Kit `Barcode.FORMAT_*` values to scan for (empty → QR only).
 * @param acceptPattern optional anchored regex the decoded value (upper-cased) must match
 *   for the scan to complete — lets the scanner **keep scanning past** non-matching
 *   barcodes on a busy label (e.g. accept only a VIN-shaped code). `null` = accept the first.
 */
class BarcodeScanContract(
    private val formats: IntArray,
    private val acceptPattern: String? = null,
) : ActivityResultContract<Unit, String?>() {

    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(context, BarcodeScanActivity::class.java).apply {
            putExtra(BarcodeScanActivity.EXTRA_FORMATS, formats)
            acceptPattern?.let { putExtra(BarcodeScanActivity.EXTRA_ACCEPT_PATTERN, it) }
        }

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == android.app.Activity.RESULT_OK) {
            intent?.getStringExtra(BarcodeScanActivity.EXTRA_RAW)
        } else {
            null
        }
}
