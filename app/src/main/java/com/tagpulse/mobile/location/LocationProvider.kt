package com.tagpulse.mobile.location

import com.tagpulse.gateway.core.GeoLocation

/**
 * A single, on-demand GPS fix for a scan (plan `docs/design/obdii-mve-plan.md`
 * §4 — the read carries the phone's GPS in `location`, a one-shot fix, not a track).
 *
 * The MVE is opportunistic / foreground: the operator taps "Scan vehicle", so the
 * coordinator asks for **one** current fix and stamps it onto the
 * [com.tagpulse.gateway.core.Observation]. There is no sampling loop.
 *
 * Kept as an interface so the [com.tagpulse.mobile.scan.ScanCoordinator] is
 * unit-testable with an in-memory [FixedLocationProvider] — the real
 * [AndroidLocationProvider] needs a device (HIL), mirroring the BLE / Keystore
 * seams already established in M1 / M4.
 */
interface LocationProvider {

    /**
     * Return the current GPS fix, or `null` if none is available (permission not
     * granted, GPS off, or no fix yet). A null fix is **not** an error — the read
     * still relays (the Map pin just won't have a location); the coordinator maps a
     * present fix onto `Observation.location` (plan §4).
     */
    suspend fun currentFix(): GeoLocation?
}

/**
 * An in-memory [LocationProvider] that always returns [fix] — the test double for
 * the [com.tagpulse.mobile.scan.ScanCoordinator] gate tests (the real GPS path is
 * HIL). Also usable to inject a hard-coded fix in a HIL/dev build.
 */
class FixedLocationProvider(private val fix: GeoLocation?) : LocationProvider {
    override suspend fun currentFix(): GeoLocation? = fix
}
