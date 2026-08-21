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
import androidx.compose.ui.text.input.ImeAction
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
import com.omnidocs.app.domain.model.Note
import com.omnidocs.app.ui.screens.recordings.RecordingPlaybackChip
import com.omnidocs.app.util.HtmlSanitizer
import com.omnidocs.app.util.MarkdownCodec
import java.io.File
import java.util.UUID

private const val TAG = "EditorScreen"

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
    var showAiMenu by remember { mutableStateOf(false) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var formatState by remember { mutableStateOf(FormatState()) }
    var isEditorFocused by remember { mutableStateOf(false) }
    var contentReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { contentReady = true }

    // Intelligence state
    val intelligenceMessages by viewModel.intelligenceMessages.collectAsState()
    val isIntelligenceLoading by viewModel.isIntelligenceLoading.collectAsState()
    val intelligenceConcepts by viewModel.intelligenceConcepts.collectAsState()
    val showConceptDialog by viewModel.showConceptDialog.collectAsState()
    var showIntelligencePanel by remember { mutableStateOf(false) }
    var selectedText by remember { mutableStateOf("") }

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
            // In markdown mode, commit the markdown source back to rich HTML (or
            // restore the unedited snapshot) before reading/saving the DOM.
            if (viewModel.editorMode.value == EditorMode.MARKDOWN) {
                viewModel.setEditorMode(EditorMode.RICH)
            }
            webViewRef?.evaluateJavascript("getContent()") { result ->
                val content = if (result != null && result.length >= 2) {
                    try {
                        org.json.JSONObject("{\"value\":$result}").getString("value")
                    } catch (_: Exception) {
                        viewModel.content.value
                    }
                } else {
                    viewModel.content.value
                }
                viewModel.updateContent(content)
                viewModel.saveAndNavigate(onNavigateBack)
            } ?: run {
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

    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val uuid = UUID.randomUUID()
            val ext = getExtensionFromUri(context, it, "jpg")
            val fileName = "image_${uuid}.$ext"
            val file = File(context.filesDir, "attachments/$fileName")
            file.parentFile?.mkdirs()

            context.contentResolver.openInputStream(it)?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val imageId = "img_${uuid.toString().take(8)}"
            val imageUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val imageHtml = """<div class='attachment' id='$imageId' contenteditable='false'>
<img src='${imageUri}' alt='Attached image' style='max-width:100%; border-radius:8px;' />
<br/>
<button class='delete-btn' onclick='document.getElementById("$imageId").remove(); Android.onContentChanged(document.getElementById("editor").innerHTML);'>Delete</button>
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
<button class='delete-btn' onclick='document.getElementById("$audioId").remove(); Android.onContentChanged(document.getElementById("editor").innerHTML);'>Delete</button>
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
                    onToggleSections = { webViewRef?.evaluateJavascript("toggleAllCollapse()", null) },
                    onIntelligenceClick = { showIntelligencePanel = !showIntelligencePanel },
                    onConceptExtract = { viewModel.extractConcepts() }
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
                        modifier = Modifier.fillMaxSize()
                    )
                    EditorMode.PREVIEW -> MarkdownPreview(
                        markdown = viewModel.currentMarkdown(),
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
            }
        )
    }

    // AI Preview Dialog
    aiPreviewState?.let { state ->
        AiPreviewDialog(
            state = state,
            modelName = aiModelName,
            onApply = { viewModel.applyAiResult() },
            onDismiss = { viewModel.dismissAiPreview() }
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

    // Intelligence panel - slides up from bottom
    AnimatedVisibility(
        visible = showIntelligencePanel,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })
    ) {
        IntelligencePanel(
            messages = intelligenceMessages,
            isLoading = isIntelligenceLoading,
            onAsk = { viewModel.askAboutNote(it) },
            onDismiss = { showIntelligencePanel = false }
        )
    }

    // Concept extraction dialog
    if (showConceptDialog) {
        ConceptDialog(
            concepts = intelligenceConcepts,
            onCreateNote = { name, desc -> viewModel.createNoteFromConcept(name, desc) },
            onDismiss = { viewModel.dismissConceptDialog() }
        )
    }
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
    onToggleSections: () -> Unit = {},
    onIntelligenceClick: () -> Unit = {},
    onConceptExtract: () -> Unit = {}
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

            ToolbarDivider()

            // Group 6: AI Intelligence
            FormatIconButton(
                icon = Icons.Default.AutoAwesome,
                contentDescription = "Ask AI",
                isActive = false,
                onClick = onIntelligenceClick
            )
            FormatIconButton(
                icon = Icons.Default.Lightbulb,
                contentDescription = "Extract Concepts",
                isActive = false,
                onClick = onConceptExtract
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
    onSummarize: () -> Unit,
    onProofread: () -> Unit,
    onRewrite: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Text(
                text = "AI Assistant",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            ListItem(
                headlineContent = { Text("Summarize") },
                leadingContent = {
                    Icon(Icons.AutoMirrored.Filled.FormatListBulleted, null)
                },
                modifier = Modifier.clickable { onDismiss(); onSummarize() }
            )
            ListItem(
                headlineContent = { Text("Proofread") },
                leadingContent = {
                    Icon(Icons.Default.Check, null)
                },
                modifier = Modifier.clickable { onDismiss(); onProofread() }
            )
            ListItem(
                headlineContent = { Text("Rewrite") },
                leadingContent = {
                    Icon(Icons.Default.Edit, null)
                },
                modifier = Modifier.clickable { onDismiss(); onRewrite() }
            )
        }
    }
}

@Composable
fun AiPreviewDialog(
    state: AiPreviewState,
    modelName: String = "",
    onApply: () -> Unit,
    onDismiss: () -> Unit
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
                        // Apply theme colors as soon as the page loads
                        view?.let {
                            val themeJson = buildEditorThemeJson(colorScheme)
                            it.evaluateJavascript("setTheme($themeJson)", null)
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

/** Monospace plain-text markdown source editor used in MARKDOWN mode. */
@Composable
private fun MarkdownEditor(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = fontFamily),
        placeholder = { Text("Write in markdown...") },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.outline,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
        )
    )
}

/** Read-only rendered markdown preview used in PREVIEW mode. Reuses the
 *  editor.html WebView styling by rendering flexmark HTML into the same
 *  contenteditable container, made non-editable. */
@Composable
private fun MarkdownPreview(
    markdown: String,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                val htmlTemplate = context.assets.open("editor.html").bufferedReader().use { it.readText() }
                val rendered = MarkdownCodec.markdownToHtml(markdown)
                val html = htmlTemplate.replace("<!-- CONTENT_PLACEHOLDER -->", rendered)
                    .replace("contenteditable=\"true\"", "contenteditable=\"false\"")
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        modifier = modifier.semantics {
            contentDescription = "Markdown preview"
        }
    )
}
