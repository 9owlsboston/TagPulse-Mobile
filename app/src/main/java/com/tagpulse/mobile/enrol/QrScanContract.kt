package com.tagpulse.mobile.enrol

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/**
 * Launches [QrScanActivity] and returns the **raw** decoded QR string, or `null` if the
 * operator cancelled / denied the camera / no QR was read (ledger `C-RYH7`, Increment 1b).
 *
 * The caller parses the raw string with [EnrolmentQrCode.parse]; a `null` (or an
 * unparseable payload) simply leaves the manual-entry fields untouched.
 */
class QrScanContract : ActivityResultContract<Unit, String?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(context, QrScanActivity::class.java)

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == android.app.Activity.RESULT_OK) {
            intent?.getStringExtra(QrScanActivity.EXTRA_RAW)
        } else {
            null
        }
}
