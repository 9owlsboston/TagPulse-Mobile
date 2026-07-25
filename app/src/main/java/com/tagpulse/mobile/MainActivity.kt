package com.tagpulse.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.tagpulse.mobile.di.AppContainer
import com.tagpulse.mobile.enrol.EnrolState
import com.tagpulse.mobile.enrol.EnrolmentCoordinator
import com.tagpulse.mobile.enrol.EnrolmentQrCode
import com.tagpulse.mobile.enrol.ProvisioningPayload
import com.tagpulse.mobile.enrol.QrScanContract
import com.tagpulse.mobile.scan.ScanCoordinator
import kotlinx.coroutines.launch

/**
 * The single Activity hosting the "Scan vehicle" flow (plan §8 M5). It builds the
 * [AppContainer] composition root and renders [com.tagpulse.mobile.ui.ScanScreen],
 * requesting BLE + fine-location runtime permissions at the point of use (GPS is now
 * actually used, unlike M4 — plan §6).
 */
class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = AppContainer(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier) {
                    AppRoot(container = container)
                }
            }
        }
    }
}

/**
 * Top-level route: gates the app on enrolment (ledger `C-RYH7`). Renders the
 * enrolment form until the handset is enrolled, then advances to the scan flow.
 *
 * The switch is **reactive**: it collects the [EnrolmentCoordinator] state, so a
 * successful enrolment recomposes into [ScanRoute] without rebuilding the
 * [AppContainer] — the shared credential store surfaces the new `device_id` (read at
 * drain time) and `baseUrl` (read per request) to the already-constructed scan path.
 */
@Composable
private fun AppRoot(container: AppContainer) {
    val enrolState by container.enrolmentCoordinator.state.collectAsState()
    val enrolled = enrolState is EnrolState.Enrolled || container.isEnrolled

    if (enrolled) {
        ScanRoute(coordinator = container.scanCoordinator)
    } else {
        EnrolRoute(coordinator = container.enrolmentCoordinator, state = enrolState)
    }
}

/**
 * Binds the [EnrolmentCoordinator] to [com.tagpulse.mobile.ui.EnrolScreen] and wires the
 * enrolment **QR scan** (Increment 1b): a launcher over [QrScanContract] returns the raw
 * QR, which [EnrolmentQrCode.parse] turns into a [ProvisioningPayload] used to prefill the
 * form (the `tp_` ingest key is still pasted). An unreadable / cancelled scan leaves the
 * fields untouched.
 */
@Composable
private fun EnrolRoute(coordinator: EnrolmentCoordinator, state: EnrolState) {
    val scope = rememberCoroutineScope()
    var prefill by remember { mutableStateOf<ProvisioningPayload?>(null) }

    val qrLauncher = rememberLauncherForActivityResult(QrScanContract()) { raw ->
        EnrolmentQrCode.parse(raw)?.let { prefill = it }
    }

    com.tagpulse.mobile.ui.EnrolScreen(
        state = state,
        onEnrol = { input -> scope.launch { coordinator.enrol(input) } },
        onScanQr = { qrLauncher.launch(Unit) },
        prefill = prefill,
    )
}

/**
 * Binds the [ScanCoordinator] state to the UI and gates a scan behind the runtime
 * permissions: on tap, if all [requiredRuntimePermissions] are granted the scan runs;
 * otherwise it requests them and runs on grant. Minimal flow (plan §6) — no rationale
 * dialog polish for the MVE.
 */
@Composable
private fun ScanRoute(coordinator: ScanCoordinator) {
    val state by coordinator.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            scope.launch { coordinator.scan() }
        }
    }

    com.tagpulse.mobile.ui.ScanScreen(
        state = state,
        onScan = {
            val perms = requiredRuntimePermissions()
            val allGranted = perms.all { perm ->
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            }
            if (allGranted) {
                scope.launch { coordinator.scan() }
            } else {
                permissionLauncher.launch(perms)
            }
        },
    )
}

/**
 * The runtime permissions a scan needs: `ACCESS_FINE_LOCATION` (the GPS fix, and the
 * pre-Android-12 BLE-scan requirement) plus, on Android 12+, the location-free
 * `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (plan §6). Legacy BLE permissions on < 12 are
 * install-time (not requested here).
 */
private fun requiredRuntimePermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
}.toTypedArray()
