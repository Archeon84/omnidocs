package com.omnidocs.app.ui.screens.ocr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.omnidocs.app.ocr.OcrBlock
import com.omnidocs.app.ocr.OcrHtmlBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

private const val TAG = "OcrScreen"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun OcrScreen(
    onTextExtracted: (String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: OcrViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val recognizedText by viewModel.recognizedText.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedImageUri by viewModel.selectedImageUri.collectAsState()
    val ocrLanguage by viewModel.ocrLanguage.collectAsState()
    val translationTarget by viewModel.translationTarget.collectAsState()
    val translatedText by viewModel.translatedText.collectAsState()
    val ocrError by viewModel.ocrError.collectAsState()
    val originalText by viewModel.originalText.collectAsState()
    val batchProgress by viewModel.batchProgress.collectAsState()
    var showLiveCamera by remember { mutableStateOf(false) }
    var liveDetectedText by remember { mutableStateOf("") }
    var liveDetectedHtml by remember { mutableStateOf("") }
    var isFrozen by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.setImageUri(it)
            viewModel.recognizeTextFromImage(it)
        }
    }

    val batchGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.batchRecognizeText(uris)
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        bitmap?.let {
            viewModel.recognizeTextFromBitmap(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OCR Scanner") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (showLiveCamera) {
                LiveCameraOcrView(
                    onTextDetected = { text, html ->
                        if (!isFrozen) {
                            liveDetectedText = text
                            liveDetectedHtml = html
                        }
                    },
                    isFrozen = isFrozen,
                    language = ocrLanguage,
                    recognizeFromFile = { filePath ->
                        viewModel.recognizeFromFile(filePath)
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Freeze/Capture button
                Button(
                    onClick = {
                        isFrozen = !isFrozen
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isFrozen) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        if (isFrozen) Icons.Default.PlayArrow else Icons.Default.Pause,
                        null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isFrozen) "Resume Camera" else "Freeze & Capture")
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (liveDetectedText.isNotEmpty()) {
                    OutlinedTextField(
                        value = liveDetectedText,
                        onValueChange = { liveDetectedText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        placeholder = { Text("Detected text...") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            showLiveCamera = false
                            isFrozen = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            if (liveDetectedHtml.isNotEmpty()) {
                                onTextExtracted(liveDetectedHtml)
                            } else if (liveDetectedText.isNotEmpty()) {
                                onTextExtracted("<p>${liveDetectedText.replace("\n", "</p><p>")}</p>")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = liveDetectedText.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Check, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Use Text")
                    }
                }
            } else {
                selectedImageUri?.let { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = "Selected image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // OCR Language selector — dropdown dialog
                var showOcrLangPicker by remember { mutableStateOf(false) }
                val ocrLangNames = mapOf(
                    "auto" to "Auto-detect",
                    "en" to "English",
                    "zh" to "Chinese",
                    "ja" to "Japanese",
                    "ko" to "Korean",
                    "ar" to "Arabic",
                    "hi" to "Hindi (Devanagari)",
                    "ru" to "Russian (Cyrillic)"
                )
                val currentOcrLangName = ocrLangNames[ocrLanguage] ?: ocrLanguage

                Text("OCR Language:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                FilterChip(
                    selected = true,
                    onClick = { showOcrLangPicker = true },
                    label = {
                        Text(
                            currentOcrLangName,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Select language",
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (showOcrLangPicker) {
                    AlertDialog(
                        onDismissRequest = { showOcrLangPicker = false },
                        title = { Text("OCR Language") },
                        text = {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                ocrLangNames.forEach { (code, name) ->
                                    ListItem(
                                        headlineContent = { Text(name) },
                                        leadingContent = {
                                            RadioButton(
                                                selected = ocrLanguage == code,
                                                onClick = {
                                                    viewModel.setOcrLanguage(code)
                                                    showOcrLangPicker = false
                                                }
                                            )
                                        },
                                        modifier = Modifier.clickable {
                                            viewModel.setOcrLanguage(code)
                                            showOcrLangPicker = false
                                        }
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showOcrLangPicker = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Translation target selector — compact dropdown
                var showLangPicker by remember { mutableStateOf(false) }
                val langs = viewModel.supportedLanguages
                val currentLangName = translationTarget?.let { langs[it] } ?: "None"

                Text("Translate to:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                FilterChip(
                    selected = translationTarget != null,
                    onClick = { showLangPicker = true },
                    label = {
                        Text(
                            currentLangName,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Select language",
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (showLangPicker) {
                    AlertDialog(
                        onDismissRequest = { showLangPicker = false },
                        title = { Text("Translate to") },
                        text = {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                // None option
                                ListItem(
                                    headlineContent = { Text("None") },
                                    leadingContent = {
                                        RadioButton(
                                            selected = translationTarget == null,
                                            onClick = {
                                                viewModel.setTranslationTarget(null)
                                                showLangPicker = false
                                            }
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        viewModel.setTranslationTarget(null)
                                        showLangPicker = false
                                    }
                                )
                                langs.forEach { (code, name) ->
                                    ListItem(
                                        headlineContent = { Text(name) },
                                        leadingContent = {
                                            RadioButton(
                                                selected = translationTarget == code,
                                                onClick = {
                                                    viewModel.setTranslationTarget(code)
                                                    showLangPicker = false
                                                }
                                            )
                                        },
                                        modifier = Modifier.clickable {
                                            viewModel.setTranslationTarget(code)
                                            showLangPicker = false
                                        }
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showLangPicker = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Gallery", style = MaterialTheme.typography.labelMedium)
                    }

                    Button(
                        onClick = {
                            if (cameraPermissionState.status.isGranted) {
                                takePictureLauncher.launch(null)
                            } else {
                                cameraPermissionState.launchPermissionRequest()
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Picture", style = MaterialTheme.typography.labelMedium)
                    }

                    Button(
                        onClick = {
                            if (cameraPermissionState.status.isGranted) {
                                showLiveCamera = true
                                liveDetectedText = ""
                                liveDetectedHtml = ""
                                isFrozen = false
                            } else {
                                cameraPermissionState.launchPermissionRequest()
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(Icons.Default.Videocam, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Live OCR", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Batch scan button
                OutlinedButton(
                    onClick = { batchGalleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Icon(Icons.Default.Collections, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Batch Scan (Multiple Images)", style = MaterialTheme.typography.labelMedium)
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (isLoading) {
                    if (batchProgress != null) {
                        val (current, total) = batchProgress!!
                        LinearProgressIndicator(
                            progress = { current.toFloat() / total },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Processing image $current of $total...")
                    } else {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Recognizing text...")
                    }
                }

                ocrError?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (recognizedText.isNotEmpty()) {
                    // Word count and confidence info
                    val wordCount = recognizedText.split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recognized Text:",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "$wordCount words",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = recognizedText,
                        onValueChange = { viewModel.updateRecognizedText(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        placeholder = { Text("Recognized text will appear here...") }
                    )

                    // Show translated text if available
                    if (translatedText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Translated (${translationTarget?.uppercase()}):",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = translatedText,
                            onValueChange = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            readOnly = true,
                            placeholder = { Text("Translation will appear here...") }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Action buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Copy button
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("OCR Text", recognizedText)
                                clipboard.setPrimaryClip(clip)
                                scope.launch {
                                    snackbarHostState.showSnackbar("Text copied to clipboard")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy", style = MaterialTheme.typography.labelMedium)
                        }

                        // Reset button (only show if text was edited)
                        if (recognizedText != originalText) {
                            OutlinedButton(
                                onClick = { viewModel.resetToOriginal() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reset", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        // Use text button
                        Button(
                            onClick = {
                                val textToShow = if (translatedText.isNotEmpty()) translatedText else recognizedText
                                val html = viewModel.recognizedHtml.value
                                if (html.isNotEmpty() && translatedText.isEmpty()) {
                                    onTextExtracted(html)
                                } else {
                                    onTextExtracted("<p>${textToShow.replace("\n", "</p><p>")}</p>")
                                }
                            },
                            modifier = Modifier.weight(1.5f)
                        ) {
                            Icon(Icons.Default.Check, null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (translatedText.isNotEmpty()) "Use Translated" else "Use Text")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiveCameraOcrView(
    onTextDetected: (String, String) -> Unit,
    isFrozen: Boolean,
    language: String,
    recognizeFromFile: suspend (String) -> Pair<String, String>?
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Store camera provider so it can be unbound in DisposableEffect
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // Bounding boxes for overlay
    var ocrBlocks by remember { mutableStateOf<List<OcrBlock>>(emptyList()) }

    // Store last captured frame for OCR on freeze
    val lastFrameFile = remember { File(context.cacheDir, "live_ocr_frame.jpg") }
    val hasCapturedFrame = remember { mutableStateOf(false) }

    // Create temp file for OCR processing
    val tempFile = remember { File(context.cacheDir, "live_ocr_frame.jpg") }

    // When freeze is tapped, run OCR on the last captured frame
    LaunchedEffect(isFrozen) {
        if (isFrozen && hasCapturedFrame.value && lastFrameFile.exists()) {
            try {
                val result = recognizeFromFile(lastFrameFile.absolutePath)
                if (result != null) {
                    val (text, html) = result
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onTextDetected(text, html)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Freeze OCR error", e)
            }
        }
    }

    DisposableEffect(isFrozen) {
        onDispose {
            cameraProviderRef?.unbindAll()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
    ) {
        // Camera preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()

                val analysisExecutor = Executors.newSingleThreadExecutor()

                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    if (!isFrozen) {
                        // Convert ImageProxy to bitmap and save as last frame
                        val image = imageProxy.image
                        if (image != null) {
                            try {
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)

                                var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bitmap != null) {
                                    // Rotate if needed
                                    val rotation = imageProxy.imageInfo.rotationDegrees
                                    if (rotation != 0) {
                                        val matrix = Matrix()
                                        matrix.postRotate(rotation.toFloat())
                                        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                                    }

                                    // Save as last captured frame
                                    FileOutputStream(tempFile).use { out ->
                                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                    }
                                    hasCapturedFrame.value = true
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Frame capture error", e)
                            }
                        }
                    }
                    imageProxy.close()
                }

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProviderRef = cameraProvider
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Bounding box overlay
        LiveCameraOverlay(
            blocks = ocrBlocks,
            modifier = Modifier.fillMaxSize()
        )
    }
}
