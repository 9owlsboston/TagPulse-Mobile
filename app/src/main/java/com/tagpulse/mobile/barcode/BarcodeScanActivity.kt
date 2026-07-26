package com.tagpulse.mobile.barcode

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
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A camera barcode scanner (ledger `C-RYH7`) — CameraX preview + ML Kit `BarcodeScanning`
 * (bundled). Generalizes the Increment 1b QR scanner: the barcode [formats][EXTRA_FORMATS]
 * are supplied per launch (QR for enrolment, Code 39 / Code 128 / Data Matrix for a VIN
 * label), and an optional [accept pattern][EXTRA_ACCEPT_PATTERN] lets it **keep scanning
 * past** non-matching barcodes on a busy label.
 *
 * Returns the **first** matching barcode's raw string via `RESULT_OK`; cancellation, a denied
 * camera permission, or any camera/provider failure returns `RESULT_CANCELED`.
 *
 * **HIL boundary:** the camera path can't run in the unit gate; callers gate-test the pure
 * payload parsing (`EnrolmentQrCode` / `VinBarcode`). `exported="false"` (manifest); never
 * logs the decoded value (it can carry a tenant provisioning key).
 */
class BarcodeScanActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var scanner: BarcodeScanner
    private var acceptRegex: Regex? = null

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

        // Build the scanner from the requested formats (read here, not in a property
        // initializer, so the Intent extras are available).
        val formats = intent.getIntArrayExtra(EXTRA_FORMATS)?.takeIf { it.isNotEmpty() }
            ?: intArrayOf(Barcode.FORMAT_QR_CODE)
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(formats.first(), *formats.drop(1).toIntArray())
            .build()
        scanner = BarcodeScanning.getClient(options)
        acceptRegex = intent.getStringExtra(EXTRA_ACCEPT_PATTERN)?.let { Regex(it) }

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
                cancel()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(imageProxy: ImageProxy) {
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
                val raw = barcodes.asSequence()
                    .mapNotNull { it.rawValue }
                    .firstOrNull { accepts(it) }
                if (raw != null && delivered.compareAndSet(false, true)) {
                    deliver(raw)
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
                analyzing.set(false)
            }
    }

    /** Whether [raw] is an accepted result: matches the accept pattern (if any). */
    private fun accepts(raw: String): Boolean {
        val pattern = acceptRegex ?: return true
        return pattern.matches(raw.trim().uppercase())
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
        if (::scanner.isInitialized) scanner.close()
    }

    companion object {
        /** Result extra carrying the raw decoded barcode string. */
        const val EXTRA_RAW: String = "com.tagpulse.mobile.barcode.EXTRA_RAW"

        /** Input extra: an `IntArray` of ML Kit `Barcode.FORMAT_*` to scan for. */
        const val EXTRA_FORMATS: String = "com.tagpulse.mobile.barcode.EXTRA_FORMATS"

        /** Input extra: an anchored regex the decoded (upper-cased) value must match. */
        const val EXTRA_ACCEPT_PATTERN: String = "com.tagpulse.mobile.barcode.EXTRA_ACCEPT_PATTERN"
    }
}
