package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.services.ScannerParser
import com.example.ui.WmsStrings
import com.example.ui.theme.*
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraQrScannerModal(
    isRtl: Boolean,
    onDismiss: () -> Unit,
    onBarcodeScanned: (String) -> Unit
) {
    val s = WmsStrings(isRtl)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var torchEnabled by remember { mutableStateOf(false) }
    var lastDetected by remember { mutableStateOf("") }
    var cameraControlRef by remember { mutableStateOf<CameraControl?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(MaterialTheme.shapes.large),
            color = Slate900,
            border = BorderStroke(1.dp, Slate700)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = Emerald400)
                        Text(
                            s.t("ماسح الكاميرا الذكي", "Camera Barcode & QR Scanner"),
                            style = MaterialTheme.typography.titleMedium,
                            color = Slate100
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("camera_scanner_close_button")
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Slate300)
                    }
                }

                if (hasPermission) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Slate950)
                    ) {
                        val executor = remember { Executors.newSingleThreadExecutor() }
                        val scanner = remember { BarcodeScanning.getClient() }
                        val consumed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

                        DisposableEffect(Unit) {
                            onDispose {
                                executor.shutdown()
                                scanner.close()
                            }
                        }

                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                cameraProviderFuture.addListener({
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.surfaceProvider = previewView.surfaceProvider
                                    }
                                    val analysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()
                                    analysis.setAnalyzer(executor) { imageProxy ->
                                        val mediaImage = imageProxy.image
                                        if (mediaImage != null && !consumed.get()) {
                                            val img = InputImage.fromMediaImage(
                                                mediaImage,
                                                imageProxy.imageInfo.rotationDegrees
                                            )
                                            scanner.process(img)
                                                .addOnSuccessListener { barcodes ->
                                                    val raw = barcodes.firstOrNull()?.rawValue
                                                    if (!raw.isNullOrBlank() && consumed.compareAndSet(false, true)) {
                                                        val extracted = ScannerParser.extractCode(raw)
                                                        lastDetected = extracted
                                                        onBarcodeScanned(extracted)
                                                    }
                                                }
                                                .addOnCompleteListener {
                                                    imageProxy.close()
                                                }
                                        } else {
                                            imageProxy.close()
                                        }
                                    }
                                    runCatching {
                                        cameraProvider.unbindAll()
                                        val camera = cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            analysis
                                        )
                                        cameraControlRef = camera.cameraControl
                                    }
                                }, ContextCompat.getMainExecutor(ctx))
                                previewView
                            }
                        )

                        IconButton(
                            onClick = {
                                torchEnabled = !torchEnabled
                                cameraControlRef?.enableTorch(torchEnabled)
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Slate900.copy(alpha = 0.78f))
                                .testTag("camera_torch_button")
                        ) {
                            Icon(
                                imageVector = if (torchEnabled) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                                contentDescription = "Toggle Flash",
                                tint = if (torchEnabled) Amber400 else Slate200
                            )
                        }
                    }
                    if (lastDetected.isNotBlank()) {
                        Text(
                            text = "${s.t("تم التقاط:", "Captured:")} $lastDetected",
                            style = MonoCodeStyle,
                            color = Emerald400
                        )
                    } else {
                        Text(
                            text = s.t("وجّه الكاميرا نحو الباركود أو رمز QR ليتم المسح تلقائياً", "Point camera at any barcode or QR label"),
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate400
                        )
                    }
                } else {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = Slate950,
                        border = BorderStroke(1.dp, Rose500.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Rounded.NoPhotography, contentDescription = null, tint = Rose400, modifier = Modifier.size(36.dp))
                            Text(
                                text = s.t("يرجى منح إذن الكاميرا لمسح الباركود، أو استخدم حقل الإدخال اليدوي.", "Camera permission is required for live scanning."),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Slate200
                            )
                            Button(
                                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("camera_request_permission_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Slate950)
                            ) {
                                Text(s.t("تفعيل الكاميرا", "Grant Camera Permission"))
                            }
                        }
                    }
                }
            }
        }
    }
}
