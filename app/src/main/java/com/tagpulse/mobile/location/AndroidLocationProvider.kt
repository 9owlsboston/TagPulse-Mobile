package com.tagpulse.mobile.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.getSystemService
import com.tagpulse.gateway.core.GeoLocation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * The real Android [LocationProvider] over the platform [LocationManager] (plan §4).
 *
 * **HIL-only.** A real GPS fix needs a device with location enabled and
 * `ACCESS_FINE_LOCATION` granted, so this class is **not** unit-tested — the
 * [com.tagpulse.mobile.scan.ScanCoordinator] gate tests drive [FixedLocationProvider]
 * instead. It is kept thin and correct so it compiles + lints clean and is ready for
 * the hardware-in-the-loop A6/A7 check.
 *
 * `LocationManager` (platform) is used deliberately over the Fused Location Provider
 * so the app pulls **no Google Play Services** dependency — the footprint budget is a
 * first-class constraint (AGENTS §2). One-shot semantics: on API 30+ we ask for a
 * single `getCurrentLocation`; below that we fall back to the newest `getLastKnownLocation`.
 *
 * `@SuppressLint("MissingPermission")`: the caller (the permission flow in
 * [com.tagpulse.mobile.ui.ScanScreen]) is responsible for holding `ACCESS_FINE_LOCATION`
 * before a scan; a missing permission surfaces as a null fix, not a crash.
 *
 * @param context application context.
 * @param executor callback executor for the API 30+ one-shot request (main-thread by default).
 */
@SuppressLint("MissingPermission")
class AndroidLocationProvider(
    context: Context,
    private val executor: Executor = Executor { command -> command.run() },
) : LocationProvider {

    private val appContext = context.applicationContext
    private val locationManager: LocationManager? = appContext.getSystemService()

    override suspend fun currentFix(): GeoLocation? {
        val lm = locationManager ?: return null
        val provider = bestProvider(lm) ?: return null

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            oneShot(lm, provider)
        } else {
            @Suppress("DEPRECATION")
            lm.getLastKnownLocation(provider)?.toGeoLocation()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun oneShot(lm: LocationManager, provider: String): GeoLocation? =
        suspendCancellableCoroutine { cont ->
            val signal = android.os.CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            lm.getCurrentLocation(provider, signal, executor) { location ->
                cont.resume(location?.toGeoLocation())
            }
        }

    /** Prefer GPS, fall back to network, else the first enabled provider. */
    private fun bestProvider(lm: LocationManager): String? {
        val preferred = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return preferred.firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            ?: runCatching { lm.getProviders(true).firstOrNull() }.getOrNull()
    }

    private fun Location.toGeoLocation(): GeoLocation = GeoLocation(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
    )
}
