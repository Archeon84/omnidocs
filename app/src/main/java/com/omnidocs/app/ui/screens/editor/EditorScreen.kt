package com.omnidocs.app.ui.screens.editor

import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.omnidocs.app.ui.navigation.Screen
import com.omnidocs.app.domain.model.Note
import com.omnidocs.app.ui.screens.recordings.RecordingPlaybackChip
import com.omnidocs.app.util.HtmlSanitizer
import com.omnidocs.app.util.MarkdownCodec
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val TAG = "EditorScreen"

/** Attached images are downsampled to this longest edge before embedding in the note. */
private const val ATTACH_IMAGE_MAX_DIMENSION_PX = 1920

/**
 * Derive a file extension from a content URI's MIME type.
 * Falls back to [defaultExt] if the MIME type cannot be resolved or is unknown.
 */
private fun getExtensionFromUri(context: android.content.Context, uri: Uri, defaultExt: String): String {
    val mimeType = context.contentResolver.getType(uri) ?: return defaultExt
    return when {
        mimeType.startsWith("image/") -> when {
            mimeType.endsWith("/png") || mimeType.contains("png") -> "png"
            mimeType.endsWith("/gif") || mimeType.contains("gif") -> "gif"
            mimeType.endsWith("/webp") || mimeType.contains("webp") -> "webp"
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
            mimeType.contains("bmp") -> "bmp"
            mimeType.contains("heic") || mimeType.contains("heif") -> "heic"
            else -> "jpg"
        }
        mimeType.startsWith("audio/") -> when {
            mimeType.contains("mpeg") || mimeType.contains("mp3") -> "mp3"
            mimeType.contains("ogg") || mimeType.contains("opus") -> "ogg"
            mimeType.contains("wav") || mimeType.contains("wave") -> "wav"
            mimeType.contains("aac") -> "aac"
            mimeType.contains("flac") -> "flac"
            mimeType.contains("midi") || mimeType.contains("mid") -> "mid"
            mimeType.contains("webm") -> "webm"
            else -> "mp3"
        }
        else -> defaultExt
    }
}

/** Plain stream copy; returns false when nothing could be read/written. */
private fun copyUriToFile(context: android.content.Context, uri: Uri, dest: File): Boolean {
    return try {
        var copied = false
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
                copied = true
            }
        }
        copied && dest.length() > 0
    } catch (e: Exception) {
        android.util.Log.e(TAG, "Attachment copy failed", e)
        false
    }
}

/**
 * Downsample an image URI to [ATTACH_IMAGE_MAX_DIMENSION_PX] longest edge and
 * store it as JPEG 80. Returns false when the image can't be decoded (caller
 * should fall back to [copyUriToFile]).
 */
private fun downsampleUriImageToFile(
    context: android.content.Context,
    uri: Uri,
    dest: File,
    maxDimension: Int = ATTACH_IMAGE_MAX_DIMENSION_PX
): Boolean {
    var bitmap: android.graphics.Bitmap? = null
    var scaled: android.graphics.Bitmap? = null
    return try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sampleSize = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sampleSize > maxDimension) sampleSize *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
        bitmap = context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        } ?: return false

        scaled = com.omnidocs.app.ocr.downscaleForOcr(bitmap!!, maxDimension)
        FileOutputStream(dest).use { out ->
            scaled!!.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        }
        dest.length() > 0
    } catch (e: Exception) {
        android.util.Log.e(TAG, "Image downsample failed, falling back to copy", e)
        false
    } finally {
        try {
            scaled?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
            bitmap?.takeIf { !it.isRecycled }?.recycle()
        } catch (_: Exception) {
            // Best-effort cleanup
        }
    }
}

data class FormatState(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val orderedList: Boolean = false,
    val unorderedList: Boolean = false
)

/**
 * Convert a Compose [Color] to a hex CSS string (#RRGGBB).
 */
private fun Color.toCssHex(): String {
    return "#%06X".format(toArgb() and 0xFFFFFF)
}

/**
 * Build editor theme colors JSON string from the current Material [ColorScheme].
 * Targets dark and AMOLED themes primarily — the editor's light defaults are fine
 * for light/sepia/ocean/forest/lavender themes already.
 */
private fun buildEditorThemeJson(scheme: ColorScheme): String {
    val bg = scheme.surface.toCssHex()
    val text = scheme.onSurface.toCssHex()
    val surfaceVariant = scheme.surfaceVariant.toCssHex()
    val onSurfaceVariant = scheme.onSurfaceVariant.toCssHex()
    val outline = scheme.outline.toCssHex()
    val primary = scheme.primary.toCssHex()

    return buildString {
        append("{")
        append("\"background\":\"$bg\",")
        append("\"text\":\"$text\",")
        append("\"codeBg\":\"$surfaceVariant\",")
        append("\"quoteBg\":\"$surfaceVariant\",")
        append("\"quoteBorder\":\"$primary\",")
        append("\"border\":\"$outline\",")
        append("\"muted\":\"$onSurfaceVariant\",")
        append("\"link\":\"$primary\",")
        append("\"thBg\":\"$surfaceVariant\",")
        append("\"hr\":\"$outline\"")
        append("}")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    noteId: String?,
    onNavigateBack: () -> Unit,
    onOcrClick: () -> Unit,
    navController: NavController,
    highlightText: String? = null,
    onStudyClick: (String) -> Unit = {},
    viewModel: EditorViewModel = hiltViewModel()
) {
    val currentNote by viewModel.currentNote.collectAsState()
    val title by viewModel.title.collectAsState()
    val content by viewModel.content.collectAsState()
    val contentSource by viewModel.contentSource.collectAsState()
    val editorMode by viewModel.editorMode.collectAsState()
    val markdownText by viewModel.markdownText.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val noteRecordings by viewModel.recordings.collectAsState()
    val playingRecordingKey by viewModel.playingRecordingKey.collectAsState()
    val wordCount by viewModel.wordCount.collectAsState()
    val readingTime by viewModel.readingTimeMinutes.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val aiPreviewState by viewModel.aiPreview.collectAsState()
    val aiModelName by viewModel.aiModelName.collectAsState()
    val currentLanguage by viewModel.currentLanguage.collectAsState()
    var showAiMenu by remember { mutableStateOf(false) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var formatState by remember { mutableStateOf(FormatState()) }
    var isEditorFocused by remember { mutableStateOf(false) }
    var highlightDismissed by remember { mutableStateOf(false) }
    var contentReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { contentReady = true }

    // Intelligence state: collected inside panel hosts below, so streaming
    // tokens recompose only the sheet — not the whole screen + WebView.
    var showIntelligencePanel by remember { mutableStateOf(false) }
    var selectedText by remember { mutableStateOf("") }

    // HTML -> Markdown is O(content length); derive once per content change,
    // not on every recomposition (keystrokes, streaming, toolbar state).
    val previewMarkdown = remember(content, markdownText, editorMode) {
        viewModel.currentMarkdown()
    }

    // OCR Provenance & Bounding Boxes state (toolbar needs the cheap flags;
    // block lists/selection are collected inside the inspector host).
    val contentBlocks by viewModel.contentBlocks.collectAsState()
    val hasOcrBlocks by viewModel.hasOcrBlocks.collectAsState()
    val showOcrInspector by viewModel.showOcrInspector.collectAsState()

    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val snackbarHostState = remember { SnackbarHostState() }
    val titleFocusRequester = remember { FocusRequester() }
    var showTemplateDialog by remember { mutableStateOf(noteId == null) }

    // Pull the latest DOM content out of the WebView, push it to the ViewModel,
    // and save before leaving. Shared by the top-bar back arrow and the system
    // back button so both paths save instead of dropping unsaved edits.
    val handleBack = {
        // A brand-new note with no title or content has nothing to persist.
        // Navigating back should discard it rather than create an empty note.
        if (noteId == null && title.isBlank() && content.isBlank()) {
            onNavigateBack()
        } else {
            if (viewModel.editorMode.value == EditorMode.RICH && webViewRef != null) {
                webViewRef?.evaluateJavascript("getContent()") { result ->
                    val domContent = if (result != null && result.length >= 2) {
                        try {
                            org.json.JSONObject("{\"value\":$result}").getString("value")
                        } catch (_: Exception) {
                            viewModel.content.value
                        }
                    } else {
                        viewModel.content.value
                    }
                    viewModel.updateContent(domContent)
                    viewModel.saveAndNavigate(onNavigateBack)
                } ?: run {
                    viewModel.saveAndNavigate(onNavigateBack)
                }
            } else {
                // In MARKDOWN or PREVIEW mode, viewModel.content.value is already kept
                // in sync as the user types (via updateMarkdown). We must NOT query the
                // WebView DOM here because it holds stale pre-markdown HTML.
                viewModel.saveAndNavigate(onNavigateBack)
            }
        }
    }

    // System back must save too (the WebView is a full-screen editor, so the OS
    // back gesture otherwise pops the screen and drops unsaved changes).
    BackHandler(onBack = handleBack)

    // Auto-focus title field on new note creation
    LaunchedEffect(noteId) {
        if (noteId == null) {
            titleFocusRequester.requestFocus()
        }
    }

    // Collect snackbar events from ViewModel
    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    // Apply editor theme colors when the WebView or color scheme changes
    LaunchedEffect(webViewRef, colorScheme.surface, colorScheme.onSurface) {
        webViewRef?.let { wv ->
            val themeJson = buildEditorThemeJson(colorScheme)
            wv.evaluateJavascript("setTheme($themeJson)", null)
        }
    }

    // Apply language and text direction (RTL for Arabic, auto for others)
    LaunchedEffect(webViewRef, currentLanguage) {
        webViewRef?.let { wv ->
            val dir = if (currentLanguage == "ar") "rtl" else "auto"
            wv.evaluateJavascript("setLanguage('$currentLanguage', '$dir')", null)
        }
    }

    // Highlight and scroll to cited text if highlightText is provided
    LaunchedEffect(webViewRef, highlightText, content) {
        if (!highlightText.isNullOrBlank() && !highlightDismissed && content.isNotBlank()) {
            webViewRef?.let { wv ->
                val escaped = org.json.JSONObject.quote(highlightText)
                wv.postDelayed({
                    wv.evaluateJavascript("findAndScrollToText($escaped)", null)
                }, 300)
            }
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val uuid = UUID.randomUUID()
            val mime = context.contentResolver.getType(it) ?: ""
            // Images are re-encoded downsampled (1920px / JPEG 80) so a 12MP
            // photo doesn't bloat storage and the WebView never holds full-res.
            val isImage = mime.startsWith("image/")
            val ext = if (isImage) "jpg" else getExtensionFromUri(context, it, "jpg")
            val fileName = "image_${uuid}.$ext"
            val file = File(context.filesDir, "attachments/$fileName")
            file.parentFile?.mkdirs()

            val stored = if (isImage) {
                downsampleUriImageToFile(context, it, file) || copyUriToFile(context, it, file)
            } else {
                copyUriToFile(context, it, file)
            }
            if (!stored) return@let

            val imageId = "img_${uuid.toString().take(8)}"
            val imageUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val imageHtml = """<div class='attachment' id='$imageId' contenteditable='false'>
<img src='${imageUri}' alt='Attached image' style='max-width:100%; border-radius:8px;' />
<br/>
<span class='delete-btn' data-attachment-id='$imageId' role='button' tabindex='0'>Delete</span>
</div><p></p>"""
            viewModel.pushUndoBeforeChange()
            viewModel.updateContent(viewModel.content.value + imageHtml)
        }
    }

    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val uuid = UUID.randomUUID()
                val ext = getExtensionFromUri(context, it, "mp3")
                val fileName = "audio_${uuid}.$ext"
                val file = File(context.filesDir, "attachments/$fileName")
                file.parentFile?.mkdirs()

                context.contentResolver.openInputStream(it)?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val audioId = "audio_${uuid.toString().take(8)}"
                val audioUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val audioHtml = """<div class='attachment' id='$audioId' contenteditable='false'>
<p><strong>Audio:</strong></p>
<audio controls src='${audioUri}'></audio>
<br/>
<span class='delete-btn' data-attachment-id='$audioId' role='button' tabindex='0'>Delete</span>
</div><p></p>"""
                viewModel.pushUndoBeforeChange()
                viewModel.updateContent(viewModel.content.value + audioHtml)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Audio attachment failed", e)
            }
        }
    }

    LaunchedEffect(noteId) {
        noteId?.let { viewModel.loadNote(it) }
    }

    // Collect OCR text from savedStateHandle
    val ocrBackStackEntry = navController.currentBackStackEntry
    DisposableEffect(ocrBackStackEntry) {
        val savedStateHandle = ocrBackStackEntry?.savedStateHandle

        val observer = androidx.lifecycle.Observer<String> { text ->
            if (text.isNotEmpty()) {
                viewModel.appendToContent(text)
                // Clear the text after applying
                savedStateHandle?.remove<String>("ocrText")
            }
        }

        savedStateHandle?.getLiveData<String>("ocrText")?.observeForever(observer)

        onDispose {
            savedStateHandle?.getLiveData<String>("ocrText")?.removeObserver(observer)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { viewModel.updateTitle(it) },
                        placeholder = { Text("Title") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(titleFocusRequester),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // AI Assistant — prominent with accent background
                    Surface(
                        onClick = { showAiMenu = true },
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(horizontal = 4.dp).requiredSize(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI Assistant",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(8.dp).size(18.dp)
                        )
                    }

                    // OCR Provenance Inspector button
                    if (hasOcrBlocks) {
                        IconButton(onClick = { viewModel.openOcrInspector() }) {
                            Icon(
                                imageVector = Icons.Default.DocumentScanner,
                                contentDescription = "OCR Bounding Boxes",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Save indicator
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    // Overflow menu
                    var showOverflowMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Language (${if (viewModel.currentLanguage.value == "ms") "BM" else "EN"})") },
                                onClick = { showOverflowMenu = false; showLanguageMenu = true },
                                leadingIcon = { Icon(Icons.Default.Language, null) }
                            )
                            DropdownMenuItem(
                                text = { Text(if (currentNote?.isPinned == true) "Unpin" else "Pin") },
                                onClick = { showOverflowMenu = false; viewModel.togglePin() },
                                leadingIcon = { Icon(Icons.Default.PushPin, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("OCR") },
                                onClick = { showOverflowMenu = false; onOcrClick() },
                                leadingIcon = { Icon(Icons.Default.DocumentScanner, null) }
                            )
                            if (noteId != null) {
                                DropdownMenuItem(
                                    text = { Text("Study flashcards") },
                                    onClick = { showOverflowMenu = false; onStudyClick(noteId) },
                                    leadingIcon = { Icon(Icons.Default.School, null) }
                                )
                            }
                            if (hasOcrBlocks) {
                                DropdownMenuItem(
                                    text = { Text("OCR Bounding Boxes (${contentBlocks.size})") },
                                    onClick = { showOverflowMenu = false; viewModel.openOcrInspector() },
                                    leadingIcon = { Icon(Icons.Default.DocumentScanner, null) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Export markdown") },
                                onClick = { showOverflowMenu = false; viewModel.exportAsMarkdown(context) },
                                leadingIcon = { Icon(Icons.Default.FileDownload, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Undo") },
                                onClick = { showOverflowMenu = false; viewModel.undo() },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, null) },
                                enabled = canUndo
                            )
                            if (wordCount > 0) {
                                DropdownMenuItem(
                                    text = { Text("$wordCount words" + if (readingTime > 0) " · ${readingTime}m read" else "") },
                                    onClick = { showOverflowMenu = false },
                                    leadingIcon = { Icon(Icons.Default.Info, null) }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            // Mode selector: Rich / Markdown / Preview
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    EditorMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = editorMode == mode,
                            onClick = { viewModel.setEditorMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = EditorMode.entries.size)
                        ) {
                            Text(mode.name, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // Evidence Provenance Citation Banner
            AnimatedVisibility(
                visible = !highlightDismissed && !highlightText.isNullOrBlank(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Cited Evidence",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Verified Citation Grounding",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "\"${highlightText?.take(100)}...\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        IconButton(
                            onClick = {
                                highlightDismissed = true
                                webViewRef?.evaluateJavascript("removeCitationHighlights()", null)
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Formatting toolbar - spring slide-in from top with stagger
            val toolbarAlpha by animateFloatAsState(
                targetValue = if (contentReady) 1f else 0f,
                animationSpec = tween(300, delayMillis = 200),
                label = "toolbarAlpha"
            )
            val toolbarOffset by animateDpAsState(
                targetValue = if (contentReady) 0.dp else (-12).dp,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
                label = "toolbarOffset"
            )
            val bodyDensity = LocalDensity.current
            // Formatting toolbar (rich editor only)
            if (editorMode == EditorMode.RICH) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        alpha = toolbarAlpha
                        translationY = bodyDensity.run { toolbarOffset.toPx() }
                    }
            ) {
                FormattingToolbar(
                    formatState = formatState,
                    canUndo = canUndo,
                    onUndoClick = { viewModel.undo() },
                    canRedo = canRedo,
                    onRedoClick = { viewModel.redo() },
                    onBoldClick = { webViewRef?.evaluateJavascript("formatText('bold')", null) },
                    onItalicClick = { webViewRef?.evaluateJavascript("formatText('italic')", null) },
                    onUnderlineClick = { webViewRef?.evaluateJavascript("formatText('underline')", null) },
                    onStrikeClick = { webViewRef?.evaluateJavascript("formatText('strikeThrough')", null) },
                    onListClick = { webViewRef?.evaluateJavascript("formatText('insertUnorderedList')", null) },
                    onNumberedListClick = { webViewRef?.evaluateJavascript("formatText('insertOrderedList')", null) },
                    onHeadingClick = { webViewRef?.evaluateJavascript("formatHeading()", null) },
                    onQuoteClick = { webViewRef?.evaluateJavascript("formatText('formatBlock', 'blockquote')", null) },
                    onCodeClick = { webViewRef?.evaluateJavascript("formatCode()", null) },
                    onImageClick = { imageLauncher.launch("image/*") },
                    onAudioClick = { audioLauncher.launch("audio/*") },
                    onToggleSections = { webViewRef?.evaluateJavascript("toggleAllCollapse()", null) }
                )
            }
            }

            // Voice-note recordings attached to this note (playback chips).
            // Hidden when the note has no recordings.
            if (noteRecordings.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(noteRecordings, key = { it.id }) { recording ->
                        RecordingPlaybackChip(
                            recording = recording,
                            isPlaying = playingRecordingKey == recording.storageKey,
                            onTogglePlay = { viewModel.togglePlayback(recording) },
                            modifier = Modifier.width(220.dp)
                        )
                    }
                }
            }

            // Rich text editor - spring slide-in from bottom with stagger
            val editorAlpha by animateFloatAsState(
                targetValue = if (contentReady) 1f else 0f,
                animationSpec = tween(300, delayMillis = 300),
                label = "editorAlpha"
            )
            val editorOffset by animateDpAsState(
                targetValue = if (contentReady) 0.dp else 12.dp,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
                label = "editorOffset"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .graphicsLayer {
                        alpha = editorAlpha
                        translationY = bodyDensity.run { editorOffset.toPx() }
                    }
            ) {
                when (editorMode) {
                    EditorMode.RICH -> RichTextEditor(
                        content = content,
                        contentSource = contentSource,
                        language = currentLanguage,
                        highlightText = if (!highlightDismissed) highlightText else null,
                        onContentChange = { newContent, fromWebView ->
                            viewModel.updateContent(newContent, fromWebView)
                        },
                        onFormatStateChange = { formatState = it },
                        onWebViewCreated = { webViewRef = it },
                        onEditorFocusChanged = { isEditorFocused = it },
                        onTextSelectionChanged = { text ->
                            selectedText = text
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    EditorMode.MARKDOWN -> MarkdownEditor(
                        text = markdownText,
                        onTextChange = { viewModel.updateMarkdown(it) },
                        highlightText = if (!highlightDismissed) highlightText else null,
                        modifier = Modifier.fillMaxSize()
                    )
                    EditorMode.PREVIEW -> MarkdownPreview(
                        markdown = previewMarkdown,
                        highlightText = if (!highlightDismissed) highlightText else null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Explain chip (appears when text is selected)
            AnimatedVisibility(
                visible = selectedText.isNotBlank() && !showIntelligencePanel,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Surface(
                        onClick = {
                            viewModel.explainSelectedText(selectedText)
                            showIntelligencePanel = true
                            selectedText = ""
                        },
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Explain selected text",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
    }

    // AI Menu
    if (showAiMenu) {
        AiActionMenu(
            onDismiss = { showAiMenu = false },
            onAskAboutNote = {
                showAiMenu = false
                showIntelligencePanel = true
            },
            onSummarize = {
                viewModel.aiSummarize()
                showAiMenu = false
            },
            onProofread = {
                viewModel.aiProofread()
                showAiMenu = false
            },
            onRewrite = {
                viewModel.aiRewrite()
                showAiMenu = false
            },
            onExtractConcepts = {
                viewModel.extractConcepts()
                showAiMenu = false
            }
        )
    }

    // AI Preview Dialog
    aiPreviewState?.let { state ->
        AiPreviewDialog(
            state = state,
            modelName = aiModelName,
            onApply = { viewModel.applyAiResult() },
            onDismiss = { viewModel.dismissAiPreview() },
            onGoToSettings = { navController.navigate(Screen.Settings.route) }
        )
    }

    // Language Menu
    if (showLanguageMenu) {
        val languages = mapOf(
            "en" to "English",
            "ms" to "Bahasa Melayu",
            "zh" to "Chinese",
            "ja" to "Japanese",
            "ko" to "Korean",
            "ar" to "Arabic",
            "hi" to "Hindi",
            "ru" to "Russian",
            "fr" to "French",
            "de" to "German",
            "es" to "Spanish",
            "pt" to "Portuguese",
            "it" to "Italian",
            "nl" to "Dutch",
            "tr" to "Turkish",
            "vi" to "Vietnamese",
            "th" to "Thai",
            "id" to "Indonesian"
        )
        AlertDialog(
            onDismissRequest = { showLanguageMenu = false },
            title = { Text("Note Language") },
            text = {
                LazyColumn {
                    items(languages.entries.toList()) { (code, name) ->
                        ListItem(
                            headlineContent = { Text(name) },
                            leadingContent = {
                                if (viewModel.currentLanguage.value == code) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                viewModel.setLanguage(code)
                                showLanguageMenu = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageMenu = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Template selection dialog for new notes
    if (showTemplateDialog) {
        AlertDialog(
            onDismissRequest = { showTemplateDialog = false },
            title = { Text("Choose a template") },
            text = {
                LazyColumn {
                    items(noteTemplates) { template ->
                        ListItem(
                            headlineContent = { Text(template.name) },
                            leadingContent = { Text(template.icon, style = MaterialTheme.typography.headlineSmall) },
                            modifier = Modifier.clickable {
                                viewModel.updateTitle(template.title)
                                if (template.content.isNotEmpty()) {
                                    viewModel.updateContent(template.content)
                                }
                                showTemplateDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showTemplateDialog = false
                    onNavigateBack()
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Intelligence panel - Modal Bottom Sheet (state isolated in host)
    if (showIntelligencePanel) {
        IntelligencePanelHost(
            viewModel = viewModel,
            onDismiss = { showIntelligencePanel = false }
        )
    }

    // Concept extraction dialog (state isolated in host)
    ConceptDialogHost(viewModel = viewModel)

    // OCR Bounding Box Inspector Sheet (block selection isolated in host)
    if (showOcrInspector) {
        OcrInspectorHost(
            viewModel = viewModel,
            imageUrl = currentNote?.imageUrl,
            contentBlocks = contentBlocks
        )
    }
}

/**
 * Collects intelligence flows in its own scope so per-token streaming updates
 * recompose only the sheet, not EditorScreen and its WebView.
 */
@Composable
private fun IntelligencePanelHost(
    viewModel: EditorViewModel,
    onDismiss: () -> Unit
) {
    val messages by viewModel.intelligenceMessages.collectAsState()
    val isLoading by viewModel.isIntelligenceLoading.collectAsState()
    val streamingText by viewModel.intelligenceStreaming.collectAsState()
    val isDeviceWarm by viewModel.isDeviceWarm.collectAsState()
    IntelligencePanel(
        messages = messages,
        isLoading = isLoading,
        onAsk = { viewModel.askAboutNote(it) },
        onDismiss = onDismiss,
        onClear = { viewModel.clearIntelligenceChat() },
        streamingText = streamingText,
        onStop = { viewModel.stopIntelligence() },
        isDeviceWarm = isDeviceWarm
    )
}

/**
 * Collects concept-dialog flows in its own scope.
 */
@Composable
private fun ConceptDialogHost(viewModel: EditorViewModel) {
    val showDialog by viewModel.showConceptDialog.collectAsState()
    if (showDialog) {
        val concepts by viewModel.intelligenceConcepts.collectAsState()
        ConceptDialog(
            concepts = concepts,
            onCreateNote = { name, desc -> viewModel.createNoteFromConcept(name, desc) },
            onDismiss = { viewModel.dismissConceptDialog() }
        )
    }
}

/**
 * Collects block-selection flow in its own scope so tapping bounding boxes
 * recomposes only the sheet.
 */
@Composable
private fun OcrInspectorHost(
    viewModel: EditorViewModel,
    imageUrl: String?,
    contentBlocks: List<com.omnidocs.app.data.local.entity.ContentBlockEntity>
) {
    val selectedIndex by viewModel.selectedBlockIndex.collectAsState()
    OcrBoundingBoxInspectorSheet(
        imageUrl = imageUrl,
        contentBlocks = contentBlocks,
        selectedIndex = selectedIndex,
        onSelectBlock = { viewModel.selectBlock(it) },
        onDismiss = { viewModel.closeOcrInspector() }
    )
}

@Composable
fun FormattingToolbar(
    formatState: FormatState = FormatState(),
    canUndo: Boolean = false,
    onUndoClick: () -> Unit = {},
    canRedo: Boolean = false,
    onRedoClick: () -> Unit = {},
    onBoldClick: () -> Unit,
    onItalicClick: () -> Unit,
    onUnderlineClick: () -> Unit,
    onStrikeClick: () -> Unit = {},
    onListClick: () -> Unit,
    onNumberedListClick: () -> Unit,
    onHeadingClick: () -> Unit,
    onQuoteClick: () -> Unit,
    onCodeClick: () -> Unit,
    onImageClick: () -> Unit = {},
    onAudioClick: () -> Unit = {},
    onToggleSections: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Undo / Redo — always first so history edits are one tap away
            FormatIconButton(
                icon = Icons.AutoMirrored.Filled.Undo,
                contentDescription = "Undo",
                isActive = false,
                enabled = canUndo,
                onClick = onUndoClick
            )
            FormatIconButton(
                icon = Icons.AutoMirrored.Filled.Redo,
                contentDescription = "Redo",
                isActive = false,
                enabled = canRedo,
                onClick = onRedoClick
            )

            ToolbarDivider()

            // Group 1: Text Style
            FormatIconButton(
                icon = Icons.Default.FormatBold,
                contentDescription = "Bold",
                isActive = formatState.bold,
                onClick = onBoldClick
            )
            FormatIconButton(
                icon = Icons.Default.FormatItalic,
                contentDescription = "Italic",
                isActive = formatState.italic,
                onClick = onItalicClick
            )
            FormatIconButton(
                icon = Icons.Default.FormatUnderlined,
                contentDescription = "Underline",
                isActive = formatState.underline,
                onClick = onUnderlineClick
            )
            FormatIconButton(
                icon = Icons.Default.StrikethroughS,
                contentDescription = "Strikethrough",
                isActive = formatState.strike,
                onClick = onStrikeClick
            )

            ToolbarDivider()

            // Group 2: Lists
            FormatIconButton(
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                contentDescription = "Bullet List",
                isActive = formatState.unorderedList,
                onClick = onListClick
            )
            FormatIconButton(
                icon = Icons.Default.FormatListNumbered,
                contentDescription = "Numbered List",
                isActive = formatState.orderedList,
                onClick = onNumberedListClick
            )

            ToolbarDivider()

            // Group 3: Block
            FormatIconButton(
                icon = Icons.Default.Title,
                contentDescription = "Heading",
                isActive = false,
                onClick = onHeadingClick
            )
            FormatIconButton(
                icon = Icons.Default.FormatQuote,
                contentDescription = "Quote",
                isActive = false,
                onClick = onQuoteClick
            )
            FormatIconButton(
                icon = Icons.Default.Code,
                contentDescription = "Code",
                isActive = false,
                onClick = onCodeClick
            )

            ToolbarDivider()

            // Group 4: Insert
            FormatIconButton(
                icon = Icons.Default.Image,
                contentDescription = "Insert Image",
                isActive = false,
                onClick = onImageClick
            )
            FormatIconButton(
                icon = Icons.Default.AudioFile,
                contentDescription = "Insert Audio",
                isActive = false,
                onClick = onAudioClick
            )

            ToolbarDivider()

            // Group 5: Sections
            FormatIconButton(
                icon = Icons.Default.UnfoldMore,
                contentDescription = "Toggle Sections",
                isActive = false,
                onClick = onToggleSections
            )
        }
    }
}

@Composable
private fun ToolbarDivider() {
    VerticalDivider(
        modifier = Modifier
            .height(24.dp)
            .padding(horizontal = 6.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun FormatIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isActive: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        isActive -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "formatBtnScale"
    )

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        interactionSource = interactionSource,
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .semantics {
                selected = isActive
                stateDescription = when {
                    isActive -> "$contentDescription active"
                    !enabled -> "$contentDescription disabled"
                    else -> contentDescription
                }
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AiActionMenu(
    onDismiss: () -> Unit,
    onAskAboutNote: () -> Unit,
    onSummarize: () -> Unit,
    onProofread: () -> Unit,
    onRewrite: () -> Unit,
    onExtractConcepts: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "AI Assistant",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "On-device intelligence actions for this note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 1. Ask about this note (Hero action)
            ListItem(
                headlineContent = {
                    Text(
                        "Ask about this note",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                supportingContent = {
                    Text("Chat, search facts & ask questions about this note")
                },
                leadingContent = {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.clickable { onDismiss(); onAskAboutNote() }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // 2. Summarize
            ListItem(
                headlineContent = { Text("Summarize") },
                supportingContent = { Text("Generate key takeaways and structured summary") },
                leadingContent = {
                    Icon(Icons.AutoMirrored.Filled.FormatListBulleted, null)
                },
                modifier = Modifier.clickable { onDismiss(); onSummarize() }
            )

            // 3. Proofread
            ListItem(
                headlineContent = { Text("Proofread") },
                supportingContent = { Text("Fix grammar, spelling, and punctuation errors") },
                leadingContent = {
                    Icon(Icons.Default.Check, null)
                },
                modifier = Modifier.clickable { onDismiss(); onProofread() }
            )

            // 4. Rewrite
            ListItem(
                headlineContent = { Text("Rewrite") },
                supportingContent = { Text("Improve clarity and professional tone") },
                leadingContent = {
                    Icon(Icons.Default.Edit, null)
                },
                modifier = Modifier.clickable { onDismiss(); onRewrite() }
            )

            // 5. Extract Concepts
            ListItem(
                headlineContent = { Text("Extract Concepts") },
                supportingContent = { Text("Identify key topics and create linked notes") },
                leadingContent = {
                    Icon(Icons.Default.Lightbulb, null)
                },
                modifier = Modifier.clickable { onDismiss(); onExtractConcepts() }
            )
        }
    }
}

@Composable
fun AiPreviewDialog(
    state: AiPreviewState,
    modelName: String = "",
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    onGoToSettings: () -> Unit = {}
) {
    val operationLabel = when (state.operation) {
        AiOperation.SUMMARIZE -> "Summarizing"
        AiOperation.PROOFREAD -> "Proofreading"
        AiOperation.REWRITE -> "Rewriting"
    }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Text(
                when (state.operation) {
                    AiOperation.SUMMARIZE -> "Summarize"
                    AiOperation.PROOFREAD -> "Proofread"
                    AiOperation.REWRITE -> "Rewrite"
                }
            )
        },
        text = {
            when {
                state.isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "$operationLabel with ${modelName.ifEmpty { "AI model" }}...",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "This may take a moment.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                state.error != null -> {
                    Column {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = state.error,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                else -> {
                    Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            text = state.result,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!state.isLoading && state.error == null && state.result.isNotBlank()) {
                TextButton(onClick = onApply) {
                    Text("Apply")
                }
            } else if (state.error != null && (state.error.contains("Settings", ignoreCase = true) || state.error.contains("model", ignoreCase = true))) {
                Button(
                    onClick = {
                        onDismiss()
                        onGoToSettings()
                    }
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("AI Settings")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    when {
                        state.isLoading -> "Cancel"
                        state.error != null -> "Close"
                        else -> "Cancel"
                    }
                )
            }
        }
    )
}

@Composable
fun RichTextEditor(
    content: String,
    contentSource: ContentSource,
    language: String = "en",
    highlightText: String? = null,
    onContentChange: (String, Boolean) -> Unit,
    onFormatStateChange: (FormatState) -> Unit = {},
    onWebViewCreated: (WebView) -> Unit = {},
    onEditorFocusChanged: (Boolean) -> Unit = {},
    onTextSelectionChanged: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var lastContent by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isPageReady by remember { mutableStateOf(false) }
    var pendingContent by remember { mutableStateOf<String?>(null) }
    val colorScheme = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale

    // Highlight and scroll to cited text once page and content are ready
    LaunchedEffect(isPageReady, highlightText, content) {
        if (isPageReady && !highlightText.isNullOrBlank() && content.isNotBlank()) {
            webView?.let { wv ->
                val escaped = org.json.JSONObject.quote(highlightText)
                wv.postDelayed({
                    wv.evaluateJavascript("findAndScrollToText($escaped)", null)
                }, 100)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
                webView?.destroy()
            } catch (_: Exception) {}
            webView = null
        }
    }

    // Buffer content changes until WebView is ready; push immediately if ready.
    // Only push PROGRAMMATIC changes (load/OCR/AI/template) back into the WebView.
    // WebView-originated edits already live in the DOM, so pushing them would
    // replace innerHTML and reset the caret/focus on every keystroke.
    LaunchedEffect(content) {
        if (contentSource != ContentSource.WEBVIEW) {
            if (content != lastContent) {
                if (isPageReady) {
                    webView?.let { wv ->
                        val safeContent = HtmlSanitizer.sanitize(content)
                        val escaped = org.json.JSONObject.quote(safeContent)
                        wv.evaluateJavascript("updateContent($escaped)", null)
                        lastContent = content
                    }
                } else {
                    pendingContent = content
                }
            }
        } else {
            // WebView-sourced change: keep lastContent in sync with what the WebView
            // is actually showing. Without this, an undo that restores content back
            // to a previously-seen value would be silently skipped by the
            // content != lastContent guard above.
            lastContent = content
        }
    }

    // Apply buffered content once WebView finishes loading
    LaunchedEffect(isPageReady) {
        if (isPageReady && pendingContent != null) {
            webView?.let { wv ->
                val safeContent = HtmlSanitizer.sanitize(pendingContent!!)
                val escaped = org.json.JSONObject.quote(safeContent)
                wv.evaluateJavascript("updateContent($escaped)", null)
                lastContent = pendingContent!!
                pendingContent = null
            }
        }
    }

    // Re-apply theme when color scheme changes
    LaunchedEffect(colorScheme.surface, colorScheme.onSurface) {
        webView?.let { wv ->
            val themeJson = buildEditorThemeJson(colorScheme)
            wv.evaluateJavascript("setTheme($themeJson)", null)
        }
    }

    // Update textZoom when system font scale changes
    LaunchedEffect(fontScale) {
        webView?.settings?.textZoom = (fontScale * 100).toInt()
    }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Apply theme colors and language/direction as soon as the page loads
                        view?.let {
                            val themeJson = buildEditorThemeJson(colorScheme)
                            it.evaluateJavascript("setTheme($themeJson)", null)
                            val dir = if (language == "ar") "rtl" else "auto"
                            it.evaluateJavascript("setLanguage('$language', '$dir')", null)
                        }
                        isPageReady = true
                    }
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                // Sync WebView text zoom with system font scale for accessibility
                settings.textZoom = (fontScale * 100).toInt()

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onContentChanged(newContent: String) {
                        // Sanitize on the JS thread (cheap, no UI access needed), then
                        // dispatch to the main thread. @JavascriptInterface callbacks run
                        // on the WebView JS thread, so touching ViewModel state here
                        // (undoStack, redoStack, etc.) from the JS thread would race
                        // against undo/redo calls on the main thread and crash.
                        val sanitized = HtmlSanitizer.sanitize(newContent)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onContentChange(sanitized, true)
                        }
                    }

                    @JavascriptInterface
                    fun onFormatStateChanged(json: String) {
                        try {
                            val obj = org.json.JSONObject(json)
                            val fs = FormatState(
                                bold = obj.optBoolean("bold"),
                                italic = obj.optBoolean("italic"),
                                underline = obj.optBoolean("underline"),
                                strike = obj.optBoolean("strike"),
                                orderedList = obj.optBoolean("orderedList"),
                                unorderedList = obj.optBoolean("unorderedList")
                            )
                            onFormatStateChange(fs)
                        } catch (_: Exception) { }
                    }

                    @JavascriptInterface
                    fun onEditorFocusChanged(focused: Boolean) {
                        onEditorFocusChanged(focused)
                    }

                    @JavascriptInterface
                    fun onTextSelectionChanged(selectedText: String) {
                        onTextSelectionChanged(selectedText)
                    }
                }, "Android")

                webView = this
                onWebViewCreated(this)

                // Scroll cursor into view when keyboard opens (height change)
                var lastHeight = 0
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, oldHeight ->
                    if (lastHeight == 0) lastHeight = oldHeight
                    val heightChanged = height != lastHeight
                    lastHeight = height
                    if (heightChanged && hasFocus()) {
                        post { evaluateJavascript("scrollCursorIntoView()", null) }
                    }
                }

                // Load editor from assets with sanitized content
                val htmlTemplate = context.assets.open("editor.html").bufferedReader().use { it.readText() }
                val safeContent = HtmlSanitizer.sanitize(content)
                val html = htmlTemplate.replace("<!-- CONTENT_PLACEHOLDER -->", safeContent)
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        modifier = modifier.semantics {
            contentDescription = "Rich text editor"
        }
    )
}

/**
 * Locates the start and end character offsets of a cited snippet in markdown text.
 * Matches exact snippet, cleaned quotes/ellipses, distinctive sentences, or leading words.
 */
internal fun findCitationSpanInMarkdown(text: String, highlight: String): TextRange? {
    if (highlight.isBlank() || text.isBlank()) return null

    // 1. Direct exact match
    val direct = text.indexOf(highlight, ignoreCase = true)
    if (direct != -1) return TextRange(direct, direct + highlight.length)

    // 2. Cleaned snippet (strip leading/trailing quotes, ellipses, whitespace)
    val clean = highlight
        .replace(Regex("""^["'«“\s]+|["'»”\s]+$"""), "")
        .replace(Regex("""^\.{2,}|[\.]{2,}$"""), "")
        .trim()
    if (clean.length >= 6) {
        val cleanIdx = text.indexOf(clean, ignoreCase = true)
        if (cleanIdx != -1) return TextRange(cleanIdx, cleanIdx + clean.length)
    }

    // 3. Match distinctive sentences (>= 15 characters)
    val sentences = clean.split(Regex("""[\n.!?]+"""))
    for (sentence in sentences) {
        val s = sentence.trim()
        if (s.length >= 15) {
            val sIdx = text.indexOf(s, ignoreCase = true)
            if (sIdx != -1) return TextRange(sIdx, sIdx + s.length)
        }
    }

    // 4. Match leading 6 words
    val words = clean.split(Regex("""\s+""")).filter { it.isNotBlank() }
    if (words.size >= 4) {
        val leadingWords = words.take(6).joinToString(" ")
        val leadIdx = text.indexOf(leadingWords, ignoreCase = true)
        if (leadIdx != -1) return TextRange(leadIdx, leadIdx + leadingWords.length)
    }

    return null
}

/** Monospace plain-text markdown source editor used in MARKDOWN mode. */
@Composable
private fun MarkdownEditor(
    text: String,
    onTextChange: (String) -> Unit,
    highlightText: String? = null,
    modifier: Modifier = Modifier
) {
    val fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
    val scrollState = rememberScrollState()

    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = text,
                selection = TextRange(text.length)
            )
        )
    }

    // Keep external text changes in sync (e.g. initial note load or mode switch)
    LaunchedEffect(text) {
        if (text != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(text = text)
        }
    }

    // When highlightText is set or changes, locate character offset, select span, and scroll to line
    LaunchedEffect(highlightText, text, scrollState.maxValue) {
        if (!highlightText.isNullOrBlank() && text.isNotBlank()) {
            val range = findCitationSpanInMarkdown(text, highlightText)
            if (range != null) {
                textFieldValue = textFieldValue.copy(selection = range)

                // Calculate line number in markdownText and scroll the ScrollState to that line
                val lineNumber = text.take(range.start).count { it == '\n' }
                val totalLines = maxOf(1, text.count { it == '\n' } + 1)

                if (scrollState.maxValue > 0) {
                    val visibleLine = maxOf(0, lineNumber - 1)
                    val targetY = (scrollState.maxValue * (visibleLine.toFloat() / totalLines)).toInt()
                    scrollState.animateScrollTo(targetY.coerceIn(0, scrollState.maxValue))
                }
            }
        } else if (highlightText == null && textFieldValue.selection.length > 0) {
            // Collapse selection if highlight was dismissed
            textFieldValue = textFieldValue.copy(
                selection = TextRange(textFieldValue.selection.end)
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        OutlinedTextField(
            value = textFieldValue,
            onValueChange = {
                textFieldValue = it
                if (it.text != text) onTextChange(it.text)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = fontFamily),
            placeholder = { Text("Write in markdown...") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.outline,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )
    }
}

/** Read-only rendered markdown preview used in PREVIEW mode. Reuses the
 *  editor.html WebView styling by rendering flexmark HTML into the same
 *  contenteditable container, made non-editable. */
@Composable
private fun MarkdownPreview(
    markdown: String,
    highlightText: String? = null,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (!highlightText.isNullOrBlank()) {
                            val escaped = org.json.JSONObject.quote(highlightText)
                            view?.postDelayed({
                                view.evaluateJavascript("findAndScrollToText($escaped)", null)
                            }, 100)
                        }
                    }
                }
                val htmlTemplate = context.assets.open("editor.html").bufferedReader().use { it.readText() }
                val rendered = MarkdownCodec.markdownToHtml(markdown)
                val html = htmlTemplate.replace("<!-- CONTENT_PLACEHOLDER -->", rendered)
                    .replace("contenteditable=\"true\"", "contenteditable=\"false\"")
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        update = { wv ->
            if (!highlightText.isNullOrBlank()) {
                val escaped = org.json.JSONObject.quote(highlightText)
                wv.evaluateJavascript("findAndScrollToText($escaped)", null)
            }
        },
        modifier = modifier.semantics {
            contentDescription = "Markdown preview"
        }
    )
}
