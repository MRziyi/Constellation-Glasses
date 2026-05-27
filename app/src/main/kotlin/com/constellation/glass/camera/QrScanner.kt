package com.constellation.glass.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * Compose preview-driven QR / barcode scanner.
 *
 * Hosts a CameraX [PreviewView] inside an AndroidView wrapper, plus an
 * [ImageAnalysis] use case that feeds frames into ML Kit's
 * [BarcodeScanning]. The first valid `QR_CODE` raw value triggers
 * [onDetected] exactly once (subsequent frames are ignored).
 *
 * Designed for the LoginScreen "SCAN QR" path — the QR code carries a
 * JSON bundle `{endpoint, cookie_name, cookie_value}` emitted by the
 * web console's About page (P-app.Q.7). The Composable's parent decides
 * what to do with the decoded string.
 *
 * Permission: caller must check + request CAMERA before mounting this
 * composable. If permission is missing the camera will just fail to
 * bind; we log and the user sees an empty black surface.
 */
@Composable
fun QrScannerView(
    onDetected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var fired by remember { mutableStateOf(false) }

    // Single-thread executor for ML Kit's analysis callback. Cleaned up
    // in the DisposableEffect below — important so the executor doesn't
    // leak when the scanner unmounts.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            val previewView = PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            scope.launch {
                bindCamera(
                    ctx = context,
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    executor = analysisExecutor,
                    onQrText = { raw ->
                        if (!fired) {
                            fired = true
                            onDetected(raw)
                        }
                    },
                )
            }
            previewView
        },
    )
}

private suspend fun bindCamera(
    ctx: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    executor: java.util.concurrent.ExecutorService,
    onQrText: (String) -> Unit,
) {
    val provider: ProcessCameraProvider = try {
        ProcessCameraProvider.getInstance(ctx).await()
    } catch (t: Throwable) {
        Timber.w(t, "QrScanner · ProcessCameraProvider unavailable")
        return
    }

    val preview = Preview.Builder().build().apply {
        setSurfaceProvider(previewView.surfaceProvider)
    }

    val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    )

    val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .apply {
            setAnalyzer(executor) { proxy: ImageProxy ->
                processFrame(proxy, scanner, onQrText)
            }
        }

    try {
        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
        )
    } catch (t: Throwable) {
        Timber.w(t, "QrScanner · bindToLifecycle failed")
    }
}

private fun processFrame(
    proxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onQrText: (String) -> Unit,
) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        return
    }
    val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { barcodes ->
            val first = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
            val raw = first?.rawValue
            if (!raw.isNullOrBlank()) {
                Timber.i("QrScanner · QR detected (${raw.length} chars)")
                onQrText(raw)
            }
        }
        .addOnCompleteListener {
            proxy.close()
        }
}
