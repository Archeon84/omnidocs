package com.omnidocs.app.ui.screens.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.omnidocs.app.domain.model.Note
import com.omnidocs.app.search.Tokenizer
import com.omnidocs.app.ui.components.BottomNavItem
import com.omnidocs.app.ui.components.IngestionReviewSheet
import com.omnidocs.app.ui.components.OmniBottomNavBar
import com.omnidocs.app.ui.components.ShimmerGrid
import com.omnidocs.app.ui.theme.MotionTokens
import com.omnidocs.app.ui.theme.isReducedMotionEnabled
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay

/** Supported MIME types for document import. */
private val IMPORT_MIME_TYPES = arrayOf(
    "text/plain",
    "text/csv",
    "text/html",
    "text/markdown",
    "text/x-markdown",
    "application/octet-stream",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/pdf"
)

/** Cards beyond this index appear instantly: no stagger coroutine, no entrance animation. */
private const val MAX_ANIMATED_CARDS = 20

/** Stagger delays are capped so deep list positions don't wait seconds to appear. */
private const val MAX_ENTRANCE_DELAY_MS = 300L

private fun entranceDelayFor(index: Int): Long =
    if (index < MAX_ANIMATED_CARDS) minOf(index * MotionTokens.STAGGER_MS, MAX_ENTRANCE_DELAY_MS)
    else -1L

private val relativeDateFormat = ThreadLocal.withInitial {
    java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
}

private fun relativeDate(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> relativeDateFormat.get()!!.format(java.util.Date(timestamp))
    }
}

enum class NoteReviewFilter {
    ALL,
    PINNED,
    RECORDINGS,
    RECENT
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalCoroutinesApi::class)
@Composable
fun HomeScreen(
    onNoteClick: (String, String?) -> Unit,
    onNewNote: () -> Unit,
    onSettingsClick: () -> Unit,
    onFeedClick: () -> Unit = {},
    onVoiceCapture: () -> Unit = {},
    onGraphClick: () -> Unit = {},
    onTasksClick: () -> Unit = {},
    onRecordingsClick: () -> Unit = {},
    onAskNotesClick: () -> Unit = {},
    onScanDoc: () -> Unit = {},
    onStudyClick: () -> Unit = {},
    onLocalChatClick: () -> Unit = {},
    onWelcomeClick: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val notes by viewModel.notes.collectAsState()
    val matchDetails by viewModel.matchDetails.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isGridView by viewModel.isGridView.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val selectedNoteIds by viewModel.selectedNoteIds.collectAsState()
    val loadError by viewModel.loadError.collectAsState()
    val dueCardsCount by viewModel.dueCardsCount.collectAsState()
    val pendingReview by viewModel.pendingReview.collectAsState()
    val isProcessingImport by viewModel.isProcessingImport.collectAsState()
    val importingFileName by viewModel.importingFileName.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showTemplateSheet by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(NoteReviewFilter.ALL) }
    var selectedNavItem by remember { mutableStateOf(BottomNavItem.Notes) }

    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var isInitialLoading by remember { mutableStateOf(true) }
    LaunchedEffect(notes) {
        if (notes.isNotEmpty() || searchQuery.isNotEmpty()) {
            isInitialLoading = false
        }
    }
    LaunchedEffect(Unit) {
        delay(800)
        isInitialLoading = false
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.importResult.collect { note ->
            onNoteClick(note.id, null)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri) ?: "text/plain"
            viewModel.importFile(uri, mimeType)
        }
    }

    // Filter notes based on selected chip — preserves search relevance ranking when searching
    val filteredNotes = remember(notes, selectedFilter, searchQuery) {
        if (searchQuery.isNotBlank()) {
            when (selectedFilter) {
                NoteReviewFilter.ALL -> notes
                NoteReviewFilter.PINNED -> notes.filter { it.isPinned }
                NoteReviewFilter.RECORDINGS -> notes.filter { it.attachments.contains("audio") || it.tags.contains("recording") || it.tags.contains("voice") }
                NoteReviewFilter.RECENT -> notes // Preserve relevance rank order, do NOT overwrite with updatedAt sort
            }
        } else {
            when (selectedFilter) {
                NoteReviewFilter.ALL -> notes
                NoteReviewFilter.PINNED -> notes.filter { it.isPinned }
                NoteReviewFilter.RECORDINGS -> notes.filter { it.attachments.contains("audio") || it.tags.contains("recording") || it.tags.contains("voice") }
                NoteReviewFilter.RECENT -> notes.sortedByDescending { it.updatedAt }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
                SelectionTopBar(
                    selectedCount = selectedNoteIds.size,
                    totalCount = notes.size,
                    onSelectAll = { viewModel.selectAllNotes() },
                    onDeselectAll = { viewModel.deselectAllNotes() },
                    onDelete = { showDeleteDialog = true },
                    onShare = { showShareDialog = true },
                    onExitSelection = { viewModel.toggleSelectionMode() }
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // Header Bar with Title and Actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "OmniDocs",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${notes.size} notes in workspace",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.toggleViewMode() }) {
                                Icon(
                                    imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                    contentDescription = "Toggle view",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = onLocalChatClick) {
                                Icon(
                                    imageVector = Icons.Default.SmartToy,
                                    contentDescription = "Offline LLM Chat",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = onGraphClick) {
                                Icon(
                                    imageVector = Icons.Default.AccountTree,
                                    contentDescription = "Knowledge Graph",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = onSettingsClick) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // ── 1. Search Bar (Search Notes Workflow) ──
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocusRequester),
                        placeholder = { Text("Search notes by keyword or meaning...") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // ── Import progress (cancellable) ──
                    if (isProcessingImport) {
                        ImportProgressBanner(
                            fileName = importingFileName,
                            onCancel = { viewModel.cancelImport() }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // ── 2. "Talk with your Notes" Hero Card ──
                    if (searchQuery.isEmpty()) {
                        TalkWithNotesHeroCard(
                            onClick = onAskNotesClick
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // ── 3. Quick Creation Hub (Create Notes Workflow) ──
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "Quick Actions",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            QuickCreateChip(
                                icon = Icons.Default.SmartToy,
                                label = "Offline LLM",
                                color = MaterialTheme.colorScheme.primary,
                                onClick = onLocalChatClick
                            )
                            QuickCreateChip(
                                icon = Icons.Default.EditNote,
                                label = "New Note",
                                color = MaterialTheme.colorScheme.primary,
                                onClick = onNewNote
                            )
                            QuickCreateChip(
                                icon = Icons.Default.Mic,
                                label = "Voice Note",
                                color = Color(0xFFE65100),
                                onClick = onVoiceCapture
                            )
                            QuickCreateChip(
                                icon = Icons.Default.DocumentScanner,
                                label = "Scan Doc",
                                color = Color(0xFF2E7D32),
                                onClick = onScanDoc
                            )
                            QuickCreateChip(
                                icon = Icons.Default.FolderOpen,
                                label = "Import File",
                                color = MaterialTheme.colorScheme.secondary,
                                onClick = { filePickerLauncher.launch(IMPORT_MIME_TYPES) }
                            )
                            QuickCreateChip(
                                icon = Icons.Default.School,
                                label = if (dueCardsCount > 0) "Practice ($dueCardsCount)" else "Practice",
                                color = Color(0xFF6A1B9A),
                                onClick = onStudyClick
                            )
                            QuickCreateChip(
                                icon = Icons.Default.DashboardCustomize,
                                label = "Templates",
                                color = MaterialTheme.colorScheme.tertiary,
                                onClick = { showTemplateSheet = true }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // ── 4. Review Filter Chips (Review Notes Workflow) ──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedFilter == NoteReviewFilter.ALL,
                            onClick = { selectedFilter = NoteReviewFilter.ALL },
                            label = { Text("All (${notes.size})") },
                            leadingIcon = {
                                if (selectedFilter == NoteReviewFilter.ALL) {
                                    Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                                }
                            }
                        )
                        FilterChip(
                            selected = selectedFilter == NoteReviewFilter.PINNED,
                            onClick = { selectedFilter = NoteReviewFilter.PINNED },
                            label = { Text("Pinned (${notes.count { it.isPinned }})") },
                            leadingIcon = {
                                Icon(Icons.Default.PushPin, null, modifier = Modifier.size(16.dp))
                            }
                        )
                        FilterChip(
                            selected = selectedFilter == NoteReviewFilter.RECENT,
                            onClick = { selectedFilter = NoteReviewFilter.RECENT },
                            label = { Text("Recent") },
                            leadingIcon = {
                                Icon(Icons.Default.AccessTime, null, modifier = Modifier.size(16.dp))
                            }
                        )
                        FilterChip(
                            selected = selectedFilter == NoteReviewFilter.RECORDINGS,
                            onClick = { selectedFilter = NoteReviewFilter.RECORDINGS },
                            label = { Text("Recordings") },
                            leadingIcon = {
                                Icon(Icons.Default.GraphicEq, null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                FloatingActionButton(
                    onClick = onNewNote,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "New note")
                }
            }
        },
        bottomBar = {
            if (!isSelectionMode) {
                OmniBottomNavBar(
                    selectedTab = selectedNavItem,
                    onTabSelected = { item ->
                        selectedNavItem = item
                        when (item) {
                            BottomNavItem.Notes -> { focusManager.clearFocus(); viewModel.updateSearchQuery("") }
                            BottomNavItem.Search -> { searchFocusRequester.requestFocus() }
                            BottomNavItem.AI -> onAskNotesClick()
                            BottomNavItem.Tasks -> onTasksClick()
                            BottomNavItem.Settings -> onSettingsClick()
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isInitialLoading && notes.isEmpty() && searchQuery.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize().semantics { liveRegion = LiveRegionMode.Polite }) {
                        ShimmerGrid(modifier = Modifier.fillMaxSize())
                    }
                }
                loadError != null && notes.isEmpty() && searchQuery.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        ) {
                            Icon(Icons.Default.Warning, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Could not load notes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(loadError ?: "Unknown error", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { viewModel.retryLoad() }) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Retry")
                            }
                        }
                    }
                }
                filteredNotes.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        ) {
                            if (searchQuery.isEmpty()) {
                                Icon(Icons.AutoMirrored.Filled.NoteAdd, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Your workspace is empty", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Create your first note, record audio, or import a document to get started.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                Spacer(modifier = Modifier.height(20.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = onNewNote) {
                                        Icon(Icons.Default.Add, null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Create Note")
                                    }
                                    OutlinedButton(onClick = onWelcomeClick) {
                                        Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Welcome Guide")
                                    }
                                }
                            } else {
                                Icon(Icons.Default.SearchOff, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("No matching notes found", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Try different search terms or check your spelling.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                else -> {
                    Crossfade(targetState = isGridView, label = "viewMode") { grid ->
                        if (grid) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                itemsIndexed(filteredNotes, key = { _, note -> note.id }) { index, note ->
                                    SelectableNoteCard(
                                        note = note,
                                        isSelected = selectedNoteIds.contains(note.id),
                                        isSelectionMode = isSelectionMode,
                                        entranceDelay = entranceDelayFor(index),
                                        matchNote = matchDetails[note.id],
                                        searchQuery = searchQuery,
                                        onClick = {
                                            if (isSelectionMode) viewModel.toggleNoteSelection(note.id)
                                            else onNoteClick(note.id, searchQuery.takeIf { it.isNotBlank() })
                                        },
                                        onLongClick = {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (!isSelectionMode) {
                                                viewModel.toggleSelectionMode()
                                                viewModel.toggleNoteSelection(note.id)
                                            }
                                        }
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(filteredNotes, key = { _, note -> note.id }) { index, note ->
                                    SelectableNoteCard(
                                        note = note,
                                        isSelected = selectedNoteIds.contains(note.id),
                                        isSelectionMode = isSelectionMode,
                                        entranceDelay = entranceDelayFor(index),
                                        matchNote = matchDetails[note.id],
                                        searchQuery = searchQuery,
                                        onClick = {
                                            if (isSelectionMode) viewModel.toggleNoteSelection(note.id)
                                            else onNoteClick(note.id, searchQuery.takeIf { it.isNotBlank() })
                                        },
                                        onLongClick = {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (!isSelectionMode) {
                                                viewModel.toggleSelectionMode()
                                                viewModel.toggleNoteSelection(note.id)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Notes") },
            text = { Text("Are you sure you want to delete ${selectedNoteIds.size} note(s)?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelectedNotes()
                        showDeleteDialog = false
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showShareDialog) {
        ShareDialog(
            onDismiss = { showShareDialog = false },
            onShareTxt = { viewModel.shareSelectedNotes("txt"); showShareDialog = false },
            onShareHtml = { viewModel.shareSelectedNotes("html"); showShareDialog = false },
            onSharePdf = { viewModel.shareSelectedNotes("pdf"); showShareDialog = false },
            onShareDoc = { viewModel.shareSelectedNotes("doc"); showShareDialog = false }
        )
    }

    if (showTemplateSheet) {
        TemplatePickerSheet(
            onTemplateSelected = { _ ->
                showTemplateSheet = false
                onNewNote()
            },
            onDismiss = { showTemplateSheet = false }
        )
    }

    pendingReview?.let { pending ->
        IngestionReviewSheet(
            reviewState = pending.reviewState,
            onConfirm = { confirmedTitle, confirmedTags, selectedTasks ->
                viewModel.confirmImport(pending, confirmedTitle, confirmedTags, selectedTasks)
            },
            onDismiss = { viewModel.dismissImportReview() }
        )
    }
}

// ── Import progress banner with cancel ──

@Composable
fun ImportProgressBanner(
    fileName: String?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Importing ${fileName ?: "file"}…",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Cancel")
            }
        }
    }
}

// ── "Talk with Notes" Hero Component ──

@Composable
fun TalkWithNotesHeroCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Talk with your Notes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Ask questions, find decisions & verify evidence",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ── Quick Creation Pill ──

@Composable
fun QuickCreateChip(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f),
        modifier = modifier.height(38.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onExitSelection: () -> Unit
) {
    TopAppBar(
        title = { Text("$selectedCount selected", fontWeight = FontWeight.Bold) },
        navigationIcon = {
            IconButton(onClick = onExitSelection) {
                Icon(Icons.Default.Close, "Exit selection")
            }
        },
        actions = {
            if (selectedCount < totalCount) {
                IconButton(onClick = onSelectAll) { Icon(Icons.Default.SelectAll, "Select all") }
            }
            if (selectedCount > 0) {
                IconButton(onClick = onDeselectAll) { Icon(Icons.Default.Deselect, "Deselect all") }
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Share") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectableNoteCard(
    note: Note,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    entranceDelay: Long = 0L,
    matchNote: String? = null,
    searchQuery: String = ""
) {
    val wordCount = remember(note.plainText) {
        if (note.plainText.isBlank()) 0
        else note.plainText.split(Regex("\\s+")).size
    }

    val density = LocalDensity.current
    var appeared by remember { mutableStateOf(entranceDelay < 0) }
    if (entranceDelay >= 0) {
        LaunchedEffect(Unit) {
            delay(entranceDelay)
            appeared = true
        }
    }

    val reducedMotion = isReducedMotionEnabled()
    val entranceOffsetY by animateDpAsState(
        targetValue = if (appeared) 0.dp else 30.dp,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "entranceOffsetY"
    )
    val entranceAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "entranceAlpha"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !reducedMotion) 0.98f else 1f,
        animationSpec = if (reducedMotion) tween(durationMillis = 150) else MotionTokens.CardPress,
        label = "cardScale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = with(density) { entranceOffsetY.toPx() }
                alpha = entranceAlpha
            }
            .animateContentSize()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = note.title.ifEmpty { "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (note.isPinned && !isSelectionMode) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (searchQuery.isNotBlank()) {
                val snippet = remember(note.plainText, searchQuery) {
                    extractSearchSnippet(note.plainText, searchQuery)
                }
                HighlightedSearchText(
                    text = snippet.ifEmpty { "No content" },
                    query = searchQuery,
                    maxLines = 2
                )
            } else {
                Text(
                    text = note.plainText.ifEmpty { "No content" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    lineHeight = 16.sp
                )
            }

            // Search-match provenance badge
            if (matchNote != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                ) {
                    Text(
                        text = matchNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Memoized: recomputing the date string on every recompose is
                // wasted work since it only changes when the note updates.
                val dateLabel = remember(note.updatedAt) { relativeDate(note.updatedAt) }
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (wordCount > 0) {
                    Text(
                        text = "$wordCount words",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Finds occurrences of a query token in text.
 * Uses strict word-boundary matching for Latin/alphanumeric tokens to prevent short tokens
 * like "ai" from matching inside unrelated words like "said", "daily", "availability", etc.
 * Uses substring matching for CJK ideographs where words are not space-delimited.
 */
fun findTokenWordMatches(text: String, token: String): List<IntRange> {
    if (text.isBlank() || token.isBlank()) return emptyList()
    val isCjk = Tokenizer.isCjk(token)
    val regex = if (isCjk) {
        Regex(Regex.escape(token), RegexOption.IGNORE_CASE)
    } else if (token.length <= 3) {
        // Strict whole-word boundary for short words/acronyms ("ai", "ml", "ui", "cat")
        Regex("""\b${Regex.escape(token)}\b""", RegexOption.IGNORE_CASE)
    } else {
        // For longer words (>= 4 chars), match whole word or plural/inflected prefix
        Regex("""\b${Regex.escape(token)}[a-zA-Z]*\b""", RegexOption.IGNORE_CASE)
    }
    return regex.findAll(text).map { it.range }.toList()
}

/**
 * Extracts a compact text snippet surrounding the first matching query term
 * for display on search result cards.
 */
fun extractSearchSnippet(plainText: String, query: String, maxChars: Int = 140): String {
    if (plainText.isBlank() || query.isBlank()) return plainText.take(maxChars).replace("\n", " ").trim()
    val queryTokens = query.split(Regex("[^\\p{L}\\p{N}_]+")).filter { it.isNotBlank() && (it.length > 1 || Tokenizer.isCjk(it)) }
    if (queryTokens.isEmpty()) return plainText.take(maxChars).replace("\n", " ").trim()

    var matchIndex = -1
    var matchedLength = 0

    // 1. First check if the complete query phrase matches as a whole
    val cleanQuery = query.trim()
    val phraseMatches = findTokenWordMatches(plainText, cleanQuery)
    if (phraseMatches.isNotEmpty()) {
        val first = phraseMatches.first()
        matchIndex = first.first
        matchedLength = first.last - first.first + 1
    } else {
        // 2. Otherwise search for individual tokens, prioritizing longer content words
        val sortedTokens = queryTokens.sortedByDescending { it.length }
        for (token in sortedTokens) {
            val tokenMatches = findTokenWordMatches(plainText, token)
            if (tokenMatches.isNotEmpty()) {
                val first = tokenMatches.first()
                matchIndex = first.first
                matchedLength = first.last - first.first + 1
                break
            }
        }
    }

    if (matchIndex < 0) return plainText.take(maxChars).replace("\n", " ").trim()

    val prefixStart = (matchIndex - 35).coerceAtLeast(0)
    val suffixEnd = (matchIndex + matchedLength + 75).coerceAtMost(plainText.length)
    val prefixEllipsis = if (prefixStart > 0) "…" else ""
    val suffixEllipsis = if (suffixEnd < plainText.length) "…" else ""

    return prefixEllipsis + plainText.substring(prefixStart, suffixEnd).replace("\n", " ").trim() + suffixEllipsis
}

/**
 * Renders note snippet text with query term matches highlighted in primary theme color.
 */
@Composable
fun HighlightedSearchText(
    text: String,
    query: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 2
) {
    val queryTokens = remember(query) {
        query.split(Regex("[^\\p{L}\\p{N}_]+")).filter { it.isNotBlank() && (it.length > 1 || Tokenizer.isCjk(it)) }
    }
    val colorScheme = MaterialTheme.colorScheme
    val annotated = remember(text, queryTokens) {
        buildAnnotatedString {
            if (queryTokens.isEmpty()) {
                append(text)
                return@buildAnnotatedString
            }
            val matches = mutableListOf<IntRange>()
            for (token in queryTokens) {
                matches.addAll(findTokenWordMatches(text, token))
            }
            if (matches.isEmpty()) {
                append(text)
                return@buildAnnotatedString
            }
            val sorted = matches.sortedBy { it.first }
            val merged = mutableListOf<IntRange>()
            var cur = sorted[0]
            for (i in 1 until sorted.size) {
                val next = sorted[i]
                if (next.first <= cur.last + 1) {
                    cur = cur.first..maxOf(cur.last, next.last)
                } else {
                    merged.add(cur)
                    cur = next
                }
            }
            merged.add(cur)

            var lastIdx = 0
            for (range in merged) {
                if (range.first > lastIdx) {
                    append(text.substring(lastIdx, range.first))
                }
                withStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary
                    )
                ) {
                    append(text.substring(range.first, range.last + 1))
                }
                lastIdx = range.last + 1
            }
            if (lastIdx < text.length) {
                append(text.substring(lastIdx))
            }
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        lineHeight = 16.sp,
        modifier = modifier
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ShareDialog(
    onDismiss: () -> Unit,
    onShareTxt: () -> Unit,
    onShareHtml: () -> Unit,
    onSharePdf: () -> Unit,
    onShareDoc: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Text(
                text = "Share Notes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            ListItem(
                headlineContent = { Text("Plain Text (.txt)") },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.TextSnippet, null) },
                modifier = Modifier.clickable { onDismiss(); onShareTxt() }
            )
            ListItem(
                headlineContent = { Text("HTML Document (.html)") },
                leadingContent = { Icon(Icons.Default.Description, null) },
                modifier = Modifier.clickable { onDismiss(); onShareHtml() }
            )
            ListItem(
                headlineContent = { Text("PDF Document (.pdf)") },
                leadingContent = { Icon(Icons.Default.PictureAsPdf, null) },
                modifier = Modifier.clickable { onDismiss(); onSharePdf() }
            )
            ListItem(
                headlineContent = { Text("Word Document (.docx)") },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.Article, null) },
                modifier = Modifier.clickable { onDismiss(); onShareDoc() }
            )
        }
    }
}
