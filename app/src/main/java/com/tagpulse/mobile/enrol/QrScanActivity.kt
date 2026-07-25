package com.tagpulse.mobile.enrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The enrolment QR scanner (ledger `C-RYH7`, Increment 1b) — CameraX preview + ML Kit
 * `BarcodeScanning` (bundled). Returns the **first** decoded QR's raw string to
 * [QrScanContract] via `RESULT_OK`; cancellation, a denied camera permission, or any
 * camera/provider failure returns `RESULT_CANCELED`.
 *
 * **HIL boundary:** the real camera path can't run in the unit gate (mirrors the BLE /
 * GPS / Keystore seams); the payload contract it feeds is gate-tested via
 * [EnrolmentQrCode]. This activity is `exported="false"` (manifest) and never logs the
 * decoded value (it can carry the tenant provisioning key).
 */
class QrScanActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    )

    // Exactly one result is delivered; at most one ML task is in flight at a time.
    private val delivered = AtomicBoolean(false)
    private val analyzing = AtomicBoolean(false)

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startCamera() else cancel() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this)
        setContentView(previewView)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, ::analyze) }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (e: Exception) {
                // Provider unavailable / bind failure → cancel cleanly, don't crash.
                cancel()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(imageProxy: ImageProxy) {
        // Drop frames once a result is committed or a task is already running.
        if (delivered.get() || !analyzing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            analyzing.set(false)
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val raw = barcodes.firstNotNullOfOrNull { it.rawValue }
                if (raw != null && delivered.compareAndSet(false, true)) {
                    deliver(raw)
                }
            }
            // Always release the frame (an unclosed ImageProxy stalls the pipeline) and
            // clear the in-flight flag so the next frame can be analyzed.
            .addOnCompleteListener {
                imageProxy.close()
                analyzing.set(false)
            }
    }

    private fun deliver(raw: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RAW, raw))
        finish()
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        scanner.close()
    }

    companion object {
        /** Result extra carrying the raw decoded QR string. */
        const val EXTRA_RAW: String = "com.tagpulse.mobile.enrol.EXTRA_RAW"
    }
}
