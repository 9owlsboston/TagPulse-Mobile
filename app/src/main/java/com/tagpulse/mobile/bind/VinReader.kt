package com.tagpulse.mobile.bind

/**
 * Outcome of an OBD-II VIN auto-read (ledger `C-RYH7` Increment 2b).
 */
sealed interface VinReadOutcome {
    /** The vehicle's ECU reported a VIN (Mode 09). */
    data class Read(val vin: String) : VinReadOutcome

    /** The read failed (unsupported / no dongle / link error) — fall back to manual entry. */
    data class Failed(val reason: String) : VinReadOutcome
}

/**
 * Seam for reading the vehicle VIN over OBD-II (Mode 09 PID 02) — the zero-touch
 * capture tier (OQ3). The real implementation drives the `ObdiiDriver` (connect + read),
 * so it is **HIL**; the bind coordinator consumes it behind this seam.
 */
fun interface VinReader {
    suspend fun readVin(): VinReadOutcome
}
