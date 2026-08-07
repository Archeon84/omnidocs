package com.omnidocs.app.ui.screens.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.omnidocs.app.domain.model.Note
import com.omnidocs.app.ui.components.ShimmerGrid
import com.omnidocs.app.ui.theme.AppTheme
import com.omnidocs.app.ui.theme.MotionTokens
import com.omnidocs.app.ui.theme.isReducedMotionEnabled
import kotlinx.coroutines.delay

/** Supported MIME types for document import. */
private val IMPORT_MIME_TYPES = arrayOf(
    "text/plain",
    "text/csv",
    "text/html",
    "text/markdown",
    "text/x-markdown",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/pdf"
)

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
        else -> {
            val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestamp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onNoteClick: (String) -> Unit,
    onNewNote: () -> Unit,
    onSettingsClick: () -> Unit,
    onFeedClick: () -> Unit = {},
    onVoiceCapture: () -> Unit = {},
    onGraphClick: () -> Unit = {}, // NEW
    viewModel: HomeViewModel = hiltViewModel()
) {
    val notes by viewModel.notes.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isGridView by viewModel.isGridView.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val selectedNoteIds by viewModel.selectedNoteIds.collectAsState()
    val currentTheme by viewModel.currentTheme.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showImportMenu by remember { mutableStateOf(false) }
    var showThemeSubmenu by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Brief shimmer shown while Room Flow first emits
    var isInitialLoading by remember { mutableStateOf(true) }
    LaunchedEffect(notes) {
        if (notes.isNotEmpty() || searchQuery.isNotEmpty()) {
            isInitialLoading = false
        }
    }
    // Auto-dismiss shimmer after 800ms even if list stays empty
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(800)
        isInitialLoading = false
    }

    // Collect snackbar events from ViewModel
    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    // Navigate to newly imported notes automatically
    LaunchedEffect(Unit) {
        viewModel.importResult.collect { note ->
            onNoteClick(note.id)
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

    // Breathing gradient animation for TopAppBar title
    val infiniteTransition = rememberInfiniteTransition(label = "ambient")
    val breathOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathOffset"
    )

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
                TopAppBar(
                    title = {
                        if (isSearching) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { viewModel.updateSearchQuery(it) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Search notes...") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search"
                                    )
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = "Clear"
                                            )
                                        }
                                    }
                                },
                                singleLine = true
                            )
                        } else {
                            Column {
                                Text(
                                    text = "OmniDocs",
                                    modifier = Modifier.graphicsLayer {
                                        translationY = if (isSearching) 0f else breathOffset * 0.5f
                                    }
                                )
                                Text(
                                    text = "v1.0.0",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onGraphClick) {
                            Icon(
                                imageVector = Icons.Default.AccountTree,
                                contentDescription = "Knowledge Graph"
                            )
                        }
                        IconButton(onClick = {
                            isSearching = !isSearching
                            if (!isSearching) viewModel.updateSearchQuery("")
                        }) {
                            Icon(
                                imageVector = if (isSearching) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = if (isSearching) "Close search" else "Search"
                            )
                        }
                        IconButton(onClick = onFeedClick) {
                            Icon(
                                imageVector = Icons.Default.Update,
                                contentDescription = "What's New"
                            )
                        }
                        IconButton(onClick = { viewModel.toggleViewMode() }) {
                            Icon(
                                imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                contentDescription = if (isGridView) "Switch to list view" else "Switch to grid view"
                            )
                        }
                        Box {
                            IconButton(onClick = { showImportMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More"
                                )
                            }
                            DropdownMenu(
                                expanded = showImportMenu,
                                onDismissRequest = { showImportMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Theme")
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    },
                                    onClick = { showThemeSubmenu = true },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Palette,
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Import document") },
                                    onClick = {
                                        showImportMenu = false
                                        filePickerLauncher.launch(IMPORT_MIME_TYPES)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.FileOpen,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                            // Nested theme submenu
                            DropdownMenu(
                                expanded = showThemeSubmenu,
                                onDismissRequest = { showThemeSubmenu = false }
                            ) {
                                AppTheme.entries.forEach { theme ->
                                    DropdownMenuItem(
                                        text = { Text(theme.name.replaceFirstChar { it.titlecase() }) },
                                        onClick = {
                                            viewModel.setTheme(theme)
                                            showThemeSubmenu = false
                                            showImportMenu = false
                                        },
                                        leadingIcon = {
                                            if (currentTheme == theme) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings"
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Voice capture FAB (smaller, above main FAB)
                    SmallFloatingActionButton(
                        onClick = onVoiceCapture,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice capture"
                        )
                    }
                    // New note FAB (main)
                    FloatingActionButton(
                        onClick = onNewNote,
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New note"
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isInitialLoading && notes.isEmpty() && searchQuery.isEmpty()) {
                // Shimmer skeleton while Room first emits
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    ShimmerGrid(modifier = Modifier.fillMaxSize())
                }
            } else if (notes.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        if (searchQuery.isEmpty()) {
                            // Branded empty state for new users
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.NoteAdd,
                                contentDescription = null,
                                modifier = Modifier.size(72.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Your thoughts,\norganized.",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Offline AI-powered notes that go with you everywhere.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = onNewNote,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Create a note")
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = {
                                    filePickerLauncher.launch(IMPORT_MIME_TYPES)
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Icon(Icons.Default.FileOpen, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Import document")
                            }
                        } else {
                            // Search empty state
                            Icon(
                                imageVector = Icons.Default.SearchOff,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No notes found",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Try a different search term",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                // Notes list/grid
                Crossfade(targetState = isGridView, label = "viewMode") { grid ->
                    if (grid) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(notes) { index, note ->
                                Box(Modifier) {
                                    SelectableNoteCard(
                                        note = note,
                                        isSelected = selectedNoteIds.contains(note.id),
                                        isSelectionMode = isSelectionMode,
                                        entranceDelay = index * MotionTokens.STAGGER_MS,
                                        onClick = {
                                            if (isSelectionMode) {
                                                viewModel.toggleNoteSelection(note.id)
                                            } else {
                                                onNoteClick(note.id)
                                            }
                                        },
                                        onLongClick = {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (!isSelectionMode) {
                                                viewModel.toggleSelectionMode()
                                                viewModel.toggleNoteSelection(note.id)
                                            }
                                        },
                                        onImport = {
                                            filePickerLauncher.launch(IMPORT_MIME_TYPES)
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val pinnedNotes = notes.filter { it.isPinned }
                        val otherNotes = notes.filter { !it.isPinned }

                        if (pinnedNotes.isNotEmpty()) {
                            stickyHeader {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "Pinned",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            itemsIndexed(pinnedNotes) { index, note ->
                                Box(Modifier) {
                                    SelectableNoteCard(
                                        note = note,
                                        isSelected = selectedNoteIds.contains(note.id),
                                        isSelectionMode = isSelectionMode,
                                        entranceDelay = index * MotionTokens.STAGGER_MS,
                                        onClick = {
                                            if (isSelectionMode) {
                                                viewModel.toggleNoteSelection(note.id)
                                            } else {
                                                onNoteClick(note.id)
                                            }
                                        },
                                        onLongClick = {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (!isSelectionMode) {
                                                viewModel.toggleSelectionMode()
                                                viewModel.toggleNoteSelection(note.id)
                                            }
                                        },
                                        onImport = {
                                            filePickerLauncher.launch(IMPORT_MIME_TYPES)
                                        }
                                    )
                                }
                            }
                        }

                        if (otherNotes.isNotEmpty()) {
                            if (pinnedNotes.isNotEmpty()) {
                                stickyHeader {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surface)
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "Notes",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                            val pinnedCount = pinnedNotes.size
                            itemsIndexed(otherNotes) { index, note ->
                                Box(Modifier) {
                                    SelectableNoteCard(
                                        note = note,
                                        isSelected = selectedNoteIds.contains(note.id),
                                        isSelectionMode = isSelectionMode,
                                        entranceDelay = (pinnedCount + index) * MotionTokens.STAGGER_MS,
                                        onClick = {
                                            if (isSelectionMode) {
                                                viewModel.toggleNoteSelection(note.id)
                                            } else {
                                                onNoteClick(note.id)
                                            }
                                        },
                                        onLongClick = {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (!isSelectionMode) {
                                                viewModel.toggleSelectionMode()
                                                viewModel.toggleNoteSelection(note.id)
                                            }
                                        },
                                        onImport = {
                                            filePickerLauncher.launch(IMPORT_MIME_TYPES)
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
    }

    // Delete confirmation dialog
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
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Share dialog
    if (showShareDialog) {
        ShareDialog(
            onDismiss = { showShareDialog = false },
            onShareTxt = {
                viewModel.shareSelectedNotes("txt")
                showShareDialog = false
            },
            onShareHtml = {
                viewModel.shareSelectedNotes("html")
                showShareDialog = false
            },
            onSharePdf = {
                viewModel.shareSelectedNotes("pdf")
                showShareDialog = false
            },
            onShareDoc = {
                viewModel.shareSelectedNotes("doc")
                showShareDialog = false
            }
        )
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
        title = { Text("$selectedCount selected") },
        navigationIcon = {
            IconButton(onClick = onExitSelection) {
                Icon(Icons.Default.Close, "Exit selection")
            }
        },
        actions = {
            if (selectedCount < totalCount) {
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Default.SelectAll, "Select all")
                }
            }
            if (selectedCount > 0) {
                IconButton(onClick = onDeselectAll) {
                    Icon(Icons.Default.Deselect, "Deselect all")
                }
            }
            IconButton(onClick = onShare, enabled = selectedCount > 0) {
                Icon(Icons.Default.Share, "Share")
            }
            IconButton(onClick = onDelete, enabled = selectedCount > 0) {
                Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
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
    onImport: (() -> Unit)? = null,
    entranceDelay: Long = 0L
) {
    val wordCount = remember(note.plainText) {
        if (note.plainText.isBlank()) 0
        else note.plainText.split(Regex("\\s+")).size
    }

    val density = LocalDensity.current
            var appeared by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(entranceDelay)
                appeared = true
            }

            val reducedMotion = isReducedMotionEnabled()
            val entranceOffsetY by animateDpAsState(
                targetValue = if (appeared) 0.dp else 40.dp,
                animationSpec = if (reducedMotion) snap() else tween(durationMillis = 400, easing = FastOutSlowInEasing),
                label = "entranceOffsetY"
            )
            val entranceAlpha by animateFloatAsState(
                targetValue = if (appeared) 1f else 0f,
                animationSpec = tween(durationMillis = 300),
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
                    .semantics {
                        selected = isSelected
                        stateDescription = buildString {
                            if (note.isPinned) append("Pinned. ")
                            if (isSelected) append("Selected. ")
                            append("${note.title.ifEmpty { "Untitled" }}")
                        }
                    }
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongClick
                    ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        )
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left accent border for pinned notes
            if (note.isPinned && !isSelectionMode) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(IntrinsicSize.Max)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            } else {
                Spacer(modifier = Modifier.width(0.dp))
            }

            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }

                if (note.imageUrl != null) {
                    AsyncImage(
                        model = note.imageUrl,
                        contentDescription = "Note thumbnail for ${note.title.ifEmpty { "Untitled" }}",
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = note.title.ifEmpty { "Untitled" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = note.plainText.ifEmpty { "No content" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3
                    )

                    // Import file chip — only visible when not in selection mode
                    if (!isSelectionMode && onImport != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            onClick = onImport,
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.height(36.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AttachFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Import file",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = relativeDate(note.updatedAt),
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

                if (note.isPinned && !isSelectionMode) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
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
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            Text(
                text = "Choose export format:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
            ListItem(
                headlineContent = { Text("Plain Text") },
                leadingContent = {
                    Icon(Icons.AutoMirrored.Filled.TextSnippet, null)
                },
                modifier = Modifier.clickable { onDismiss(); onShareTxt() }
            )
            ListItem(
                headlineContent = { Text("HTML") },
                leadingContent = {
                    Icon(Icons.Default.Description, null)
                },
                modifier = Modifier.clickable { onDismiss(); onShareHtml() }
            )
            ListItem(
                headlineContent = { Text("PDF") },
                leadingContent = {
                    Icon(Icons.Default.PictureAsPdf, null)
                },
                modifier = Modifier.clickable { onDismiss(); onSharePdf() }
            )
            ListItem(
                headlineContent = { Text("Word Document") },
                leadingContent = {
                    Icon(Icons.AutoMirrored.Filled.Article, null)
                },
                modifier = Modifier.clickable { onDismiss(); onShareDoc() }
            )
        }
    }
}
