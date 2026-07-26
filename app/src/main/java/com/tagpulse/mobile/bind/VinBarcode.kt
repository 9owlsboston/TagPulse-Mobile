package com.tagpulse.mobile.bind

/**
 * Extracts a VIN from a scanned **barcode** value (ledger `C-RYH7` Increment 2c) — pure,
 * no Android.
 *
 * A door-jamb VIN barcode (Code 39 / Code 128 / Data Matrix) encodes the 17-character VIN,
 * sometimes prefixed with the **AIAG data identifier `I`** (giving an 18-character payload).
 * [extract] returns the bare 17-character VIN candidate (upper-cased, `I` stripped) when the
 * payload is VIN-shaped (17 alphanumeric chars), else `null` — so a non-VIN barcode on a busy
 * label is ignored rather than mis-bound. The authoritative validation (ISO-3779 alphabet +
 * the backend resolve + plate confirmation) still happens in [Vin] / the coordinator.
 */
object VinBarcode {

    private const val VIN_LEN = 17
    private val ALPHANUMERIC = (('A'..'Z') + ('0'..'9')).toSet()

    /** The 17-char VIN candidate from a scanned barcode [raw], or `null` if not VIN-shaped. */
    fun extract(raw: String?): String? {
        var s = raw?.trim()?.uppercase().orEmpty()
        // Strip a single leading AIAG data-identifier 'I' on an 18-char payload.
        if (s.length == VIN_LEN + 1 && s.startsWith('I')) {
            s = s.substring(1)
        }
        return if (s.length == VIN_LEN && s.all { it in ALPHANUMERIC }) s else null
    }
}
