package com.example.ocrscanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ocrscanner.ui.theme.OCRScannerTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OCRScannerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CameraPermissionWrapper()
                }
            }
        }
    }
}

@Composable
fun CameraPermissionWrapper() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        OcrCameraScreen()
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera Permission Required")
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant Permission")
                }
            }
        }
    }
}

@Composable
fun OcrCameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // States
    var allText by remember { mutableStateOf("Ready to scan...") }
    var detectedNumbers by remember { mutableStateOf("None") }
    var isLiveMode by remember { mutableStateOf(true) }

    // CameraX Use Cases
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    // Setup Image Analysis (Live Mode)
                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor) { imageProxy ->
                                if (isLiveMode) {
                                    processImageProxy(
                                        imageProxy,
                                        onRawTextChanged = { allText = it },
                                        onNumbersFound = { detectedNumbers = it }
                                    )
                                } else {
                                    imageProxy.close()
                                }
                            }
                        }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalyzer,
                            imageCapture
                        )
                    } catch (exc: Exception) {
                        Log.e("OCR", "Camera setup failed", exc)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // UI Controls (Top Buttons)
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
                .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.medium)
                .padding(8.dp)
        ) {
            Button(
                onClick = { isLiveMode = true },
                colors = ButtonDefaults.buttonColors(containerColor = if (isLiveMode) Color.Blue else Color.DarkGray)
            ) { Text("Live Video") }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = { isLiveMode = false },
                colors = ButtonDefaults.buttonColors(containerColor = if (!isLiveMode) Color.Blue else Color.DarkGray)
            ) { Text("Photo Shoot") }
        }

        // Capture Button (Only visible in Photo Mode)
        if (!isLiveMode) {
            FloatingActionButton(
                onClick = {
                    takePhotoAndProcess(imageCapture, context, cameraExecutor) { raw, nums ->
                        allText = raw
                        detectedNumbers = nums
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp),
                containerColor = Color.Red
            ) {
                Text("SNAP", color = Color.White, modifier = Modifier.padding(8.dp))
            }
        }

        // Results Panel
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.35f)
                .background(Color.Black.copy(alpha = 0.8f))
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(if (isLiveMode) "MODE: LIVE" else "MODE: PHOTO", color = Color.Yellow, fontWeight = FontWeight.ExtraBold)
            Text("NUMBERS FOUND:", color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(text = detectedNumbers, color = Color.White, style = MaterialTheme.typography.headlineSmall)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color.Gray)
            Text("RAW TEXT:", color = Color.Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(text = allText, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun takePhotoAndProcess(
    imageCapture: ImageCapture,
    context: android.content.Context,
    executor: java.util.concurrent.Executor,
    onResult: (String, String) -> Unit
) {
    imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
        @OptIn(ExperimentalGetImage::class)
        override fun onCaptureSuccess(imageProxy: ImageProxy) {
            processImageProxy(imageProxy,
                onRawTextChanged = { raw -> onResult(raw, "") },
                onNumbersFound = { nums -> onResult("", nums) } // Modified logic for callback sync
            ) { raw, nums ->
                onResult(raw, nums)
            }
        }

        override fun onError(exception: ImageCaptureException) {
            Log.e("OCR", "Photo capture failed", exception)
        }
    })
}

@OptIn(ExperimentalGetImage::class)
private fun processImageProxy(
    imageProxy: ImageProxy,
    onRawTextChanged: (String) -> Unit = {},
    onNumbersFound: (String) -> Unit = {},
    onComplete: ((String, String) -> Unit)? = null
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val rawText = visionText.text
                val numberPattern = Regex("\\d+")
                val matches = numberPattern.findAll(rawText).map { it.value }.joinToString(", ")

                onRawTextChanged(rawText)
                onNumbersFound(if (matches.isNotEmpty()) matches else "None")
                onComplete?.invoke(rawText, if (matches.isNotEmpty()) matches else "None")
            }
            .addOnCompleteListener { imageProxy.close() }
    } else {
        imageProxy.close()
    }
}