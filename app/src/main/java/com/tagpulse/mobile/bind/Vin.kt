package com.tagpulse.mobile.bind

/**
 * Vehicle Identification Number (VIN) helpers (ledger `C-RYH7`, Increment 2a) — pure,
 * no Android / I/O.
 *
 * The VIN is the vehicle binding key: the handset reports `tag_id = ` [canonical] VIN, and
 * the backend resolves + Map-links it (`docs/design/vehicle-bind-flow.md`).
 *
 * **Validation policy (plan-stage rubber-duck).** [isValid] is a **hard** gate on length +
 * the ISO-3779 alphabet only. The **check digit** (position 9) is exposed **separately** as
 * an *advisory* ([checkDigitValid]) and is **not** enforced — it is mandatory only in North
 * America, and many legitimate non-NA VINs carry another character there, so enforcing it
 * would reject real vehicles. The backend resolve + plate confirmation is the authoritative
 * check.
 */
object Vin {

    /** VIN length (ISO 3779). */
    const val LENGTH: Int = 17

    // The VIN alphabet excludes I, O, Q (to avoid confusion with 1/0) — A–Z minus those, + 0–9.
    private val ALLOWED = ('A'..'Z').filter { it != 'I' && it != 'O' && it != 'Q' }.toSet() +
        ('0'..'9').toSet()

    /** Canonical form used as the `tag_id` and for the backend lookup: trimmed + upper-cased. */
    fun canonical(raw: String): String = raw.trim().uppercase()

    /**
     * Hard validity: exactly [LENGTH] characters, all in the VIN alphabet (no `I`/`O`/`Q`).
     * Does **not** enforce the check digit (see [checkDigitValid]). Expects a [canonical] VIN.
     */
    fun isValid(vin: String): Boolean =
        vin.length == LENGTH && vin.all { it in ALLOWED }

    /**
     * Advisory ISO-3779 check-digit test (position 9). Returns `false` for a structurally
     * invalid VIN. **Not** used to reject a VIN — surface it as a soft warning only.
     */
    fun checkDigitValid(vin: String): Boolean {
        if (!isValid(vin)) return false
        var sum = 0
        for (i in 0 until LENGTH) {
            val value = transliterate(vin[i]) ?: return false
            sum += value * WEIGHTS[i]
        }
        val remainder = sum % 11
        val expected = if (remainder == 10) 'X' else ('0' + remainder)
        return vin[8] == expected
    }

    // Positional weights for the check-digit sum (position 9 = 0, it's the check digit itself).
    private val WEIGHTS = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

    /** ISO-3779 transliteration of a VIN character to its numeric value; null if out of alphabet. */
    private fun transliterate(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        'A', 'J' -> 1
        'B', 'K', 'S' -> 2
        'C', 'L', 'T' -> 3
        'D', 'M', 'U' -> 4
        'E', 'N', 'V' -> 5
        'F', 'W' -> 6
        'G', 'P', 'X' -> 7
        'H', 'Y' -> 8
        'R', 'Z' -> 9
        else -> null
    }
}
