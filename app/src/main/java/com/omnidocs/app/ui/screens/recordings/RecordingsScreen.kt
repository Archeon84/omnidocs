package com.omnidocs.app.ui.screens.recordings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.RecordingDao
import com.omnidocs.app.data.local.TranscriptSegmentDao
import com.omnidocs.app.data.local.entity.RecordingEntity
import com.omnidocs.app.voice.AudioPlaybackController
import com.omnidocs.app.voice.RecordingStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private const val SEARCH_DEBOUNCE_MS = 300L
private const val TRANSCRIPT_PREVIEW_CHARS = 120
private const val UNDO_WINDOW_MS = 6_000L
private const val TRASH_SUFFIX = ".deleted"
private const val TRASH_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

enum class RecordingSortOrder {
    NEWEST,
    OLDEST,
    LONGEST,
    SHORTEST
}

/** A recording plus the title of the note it belongs to and its transcript. */
data class RecordingListItem(
    val recording: RecordingEntity,
    val noteTitle: String?,
    val transcriptPreview: String? = null,
    val fullTranscript: String = ""
)

/** One day section in the grouped list. */
data class RecordingDayGroup(
    val label: String,
    val items: List<RecordingListItem>
)

/** A soft-deleted recording inside the undo window. */
data class RecentlyDeleted(
    val recording: RecordingEntity,
    /** False when the audio file was already gone; then undo is not offered. */
    val fileTrashed: Boolean
)

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val recordingDao: RecordingDao,
    private val noteDao: NoteDao,
    private val transcriptSegmentDao: TranscriptSegmentDao,
    private val recordingStorage: RecordingStorage,
    val playbackController: AudioPlaybackController
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private val _sortOrder = MutableStateFlow(RecordingSortOrder.NEWEST)
    val sortOrder: StateFlow<RecordingSortOrder> = _sortOrder.asStateFlow()

    fun setSortOrder(order: RecordingSortOrder) {
        _sortOrder.value = order
    }

    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    fun cyclePlaybackSpeed() {
        val next = when (_playbackSpeed.value) {
            1f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        _playbackSpeed.value = next
        playbackController.setSpeed(next)
    }

    private val _pendingDelete = MutableStateFlow<RecordingEntity?>(null)
    val pendingDelete: StateFlow<RecordingEntity?> = _pendingDelete.asStateFlow()

    private val _recentlyDeleted = MutableStateFlow<RecentlyDeleted?>(null)
    val recentlyDeleted: StateFlow<RecentlyDeleted?> = _recentlyDeleted.asStateFlow()

    private val _playbackError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val playbackError: SharedFlow<String> = _playbackError.asSharedFlow()

    /**
     * Live list: recordings joined with live note titles (a recording whose
     * note is gone is dropped — it would open a blank editor), transcript
     * previews, search filter, and sort. Debounced so typing doesn't hammer
     * the segments table.
     */
    val items: StateFlow<List<RecordingListItem>> =
        combine(
            recordingDao.getAllRecordings(),
            noteDao.getAllNotes(),
            _searchQuery.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged(),
            _sortOrder
        ) { recordings, notes, query, sort ->
            val notesById = notes.associateBy { it.id }
            val q = query.trim().lowercase()
            val filtered = recordings.mapNotNull { rec ->
                val noteTitle = if (rec.noteId.isBlank()) {
                    null
                } else {
                    notesById[rec.noteId]?.title ?: return@mapNotNull null
                }
                if (q.isNotEmpty()) {
                    val haystack = "${rec.filename} ${noteTitle.orEmpty()}".lowercase()
                    if (!haystack.contains(q)) return@mapNotNull null
                }
                rec to noteTitle
            }
            val sorted = when (sort) {
                RecordingSortOrder.NEWEST -> filtered.sortedByDescending { it.first.createdAt }
                RecordingSortOrder.OLDEST -> filtered.sortedBy { it.first.createdAt }
                RecordingSortOrder.LONGEST -> filtered.sortedByDescending { it.first.durationMs }
                RecordingSortOrder.SHORTEST -> filtered.sortedBy { it.first.durationMs }
            }
            // One batch query for every visible recording's segments.
            val segmentsByRecording = if (sorted.isNotEmpty()) {
                transcriptSegmentDao.getSegmentsByRecordingIds(sorted.map { it.first.id })
                    .groupBy { it.recordingId }
            } else {
                emptyMap()
            }
            sorted.map { (rec, noteTitle) ->
                val text = segmentsByRecording[rec.id].orEmpty()
                    .joinToString(" ") { it.correctedText?.takeIf { s -> s.isNotBlank() } ?: it.rawText }
                    .trim()
                RecordingListItem(
                    recording = rec,
                    noteTitle = noteTitle,
                    transcriptPreview = text.takeIf { it.isNotBlank() }
                        ?.take(TRANSCRIPT_PREVIEW_CHARS),
                    fullTranscript = text
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Items grouped into Today / Yesterday / weekday / dated sections. */
    val groupedItems: StateFlow<List<RecordingDayGroup>> = items
        .map { list ->
            list.groupBy { dayLabel(it.recording.createdAt) }
                .map { (label, groupItems) -> RecordingDayGroup(label, groupItems) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Belt-and-braces: purge trash files left behind if the app died mid-undo.
        viewModelScope.launch {
            try {
                val cutoff = System.currentTimeMillis() - TRASH_RETENTION_MS
                recordingStorage.listRecordings()
                    .filter { it.name.endsWith(TRASH_SUFFIX) && it.lastModified() < cutoff }
                    .forEach { it.delete() }
            } catch (_: Exception) {
            }
        }
    }

    /** Pause/resume toggle with missing-file feedback. */
    fun togglePause(recording: RecordingEntity) {
        val wasActive = playbackController.isPlaying(recording.storageKey)
        val ok = playbackController.togglePause(recording.storageKey)
        if (!ok && !wasActive) {
            _playbackError.tryEmit("Audio file is missing for this recording.")
        }
    }

    fun togglePlayback(recording: RecordingEntity) {
        togglePause(recording)
    }

    fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
    }

    fun requestDelete(recording: RecordingEntity) {
        _pendingDelete.value = recording
    }

    fun cancelDelete() {
        _pendingDelete.value = null
    }

    private var purgeJob: Job? = null

    /**
     * Confirm deletion: soft-delete the row and move the audio to a trash
     * name. Undo within the window restores both; afterwards the trash file
     * is permanently removed.
     */
    fun confirmDelete() {
        val rec = _pendingDelete.value ?: return
        _pendingDelete.value = null
        viewModelScope.launch {
            if (playbackController.isPlaying(rec.storageKey)) {
                playbackController.stop()
            }
            recordingDao.softDeleteRecording(rec.id)
            val trashed = moveToTrash(rec.storageKey)
            _recentlyDeleted.value = RecentlyDeleted(rec, trashed)
            purgeJob?.cancel()
            purgeJob = launch {
                delay(UNDO_WINDOW_MS)
                if (trashed) {
                    recordingStorage.deleteRecording(rec.storageKey + TRASH_SUFFIX)
                }
                _recentlyDeleted.value = null
            }
        }
    }

    fun undoDelete() {
        val recent = _recentlyDeleted.value ?: return
        if (!recent.fileTrashed) return
        purgeJob?.cancel()
        purgeJob = null
        viewModelScope.launch {
            restoreFromTrash(recent.recording.storageKey)
            val row = recordingDao.getRecordingById(recent.recording.id)
            if (row != null) {
                recordingDao.updateRecording(row.copy(deletedAt = null))
            } else {
                recordingDao.insertRecording(recent.recording.copy(deletedAt = null))
            }
            _recentlyDeleted.value = null
        }
    }

    fun clearRecentlyDeleted() {
        _recentlyDeleted.value = null
    }

    private fun moveToTrash(storageKey: String): Boolean {
        return try {
            val file = recordingStorage.getRecordingFile(storageKey) ?: return false
            file.renameTo(File(file.parent, file.name + TRASH_SUFFIX))
        } catch (_: Exception) {
            false
        }
    }

    private fun restoreFromTrash(storageKey: String): Boolean {
        return try {
            val trash = recordingStorage.getRecordingFile(storageKey + TRASH_SUFFIX)
                ?: return false
            trash.renameTo(File(trash.parent, storageKey))
        } catch (_: Exception) {
            false
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackController.stop()
    }
}

private fun dayLabel(timestamp: Long): String {
    val cal = Calendar.getInstance()
    val todayYear = cal.get(Calendar.YEAR)
    val todayDay = cal.get(Calendar.DAY_OF_YEAR)
    cal.timeInMillis = timestamp
    val year = cal.get(Calendar.YEAR)
    val day = cal.get(Calendar.DAY_OF_YEAR)
    val dayDiff = (todayYear - year) * 366 + (todayDay - day)
    return when {
        dayDiff <= 0 -> "Today"
        dayDiff == 1 -> "Yesterday"
        dayDiff < 7 -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}

/** Format milliseconds as mm:ss. */
fun formatDurationMs(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

/**
 * A compact play/pause chip for a single recording. Shared by the editor's
 * per-note chip row and the Recordings list.
 */
@Composable
fun RecordingPlaybackChip(
    recording: RecordingEntity,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (isPlaying) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onTogglePlay)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause recording" else "Play recording",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recording.filename.ifBlank { "Voice recording" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (secondaryLabel != null) {
                    Text(
                        text = secondaryLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatDurationMs(recording.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Recordings list screen: searchable, sortable, grouped recordings with a full player. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecordingsScreen(
    onBack: () -> Unit,
    onNoteClick: (String) -> Unit,
    onRecordClick: () -> Unit = {},
    viewModel: RecordingsViewModel = hiltViewModel()
) {
    val groups by viewModel.groupedItems.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val speed by viewModel.playbackSpeed.collectAsState()
    val pendingDelete by viewModel.pendingDelete.collectAsState()
    val recentlyDeleted by viewModel.recentlyDeleted.collectAsState()
    val playingKey by viewModel.playbackController.currentPlayingKey.collectAsState()
    val positionMs by viewModel.playbackController.playbackPositionMs.collectAsState()
    val activeDurationMs by viewModel.playbackController.playbackDurationMs.collectAsState()
    val isPaused by viewModel.playbackController.isPaused.collectAsState()
    var sortMenuOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.playbackError.collect { msg ->
            snackbar.showSnackbar(msg)
        }
    }
    LaunchedEffect(recentlyDeleted) {
        val recent = recentlyDeleted ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = "Recording deleted",
            actionLabel = if (recent.fileTrashed) "Undo" else null,
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDelete()
        } else {
            viewModel.clearRecentlyDeleted()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recordings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = onRecordClick) {
                Icon(Icons.Default.Mic, contentDescription = "Record a voice note")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setSearchQuery,
                placeholder = { Text("Search recordings or notes") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val total = groups.sumOf { it.items.size }
                Text(
                    text = if (total == 1) "1 recording" else "$total recordings",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    TextButton(onClick = { sortMenuOpen = true }) {
                        Icon(Icons.Default.Sort, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(sortLabel(sortOrder))
                    }
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false }
                    ) {
                        RecordingSortOrder.entries.forEach { order ->
                            DropdownMenuItem(
                                text = { Text(sortLabel(order)) },
                                trailingIcon = {
                                    if (order == sortOrder) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    viewModel.setSortOrder(order)
                                    sortMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }
            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            Icons.Default.GraphicEq,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (query.isBlank()) {
                            Text(
                                "No recordings yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Record a voice note to attach audio to your notes",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            FilledTonalButton(onClick = onRecordClick) {
                                Icon(Icons.Default.Mic, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Record a voice note")
                            }
                        } else {
                            Text(
                                "No matches for \"$query\"",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    groups.forEach { group ->
                        stickyHeader(key = "header-${group.label}") {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = group.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                        items(group.items, key = { it.recording.id }) { item ->
                            val active = playingKey == item.recording.storageKey
                            RecordingCard(
                                item = item,
                                isActive = active,
                                paused = active && isPaused,
                                positionMs = if (active) positionMs else 0L,
                                durationMs = if (active && activeDurationMs > 0) {
                                    activeDurationMs
                                } else {
                                    item.recording.durationMs
                                },
                                speed = speed,
                                onTogglePause = { viewModel.togglePause(item.recording) },
                                onSeek = viewModel::seekTo,
                                onCycleSpeed = viewModel::cyclePlaybackSpeed,
                                onDelete = { viewModel.requestDelete(item.recording) },
                                onNoteClick = { onNoteClick(item.recording.noteId) }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = { Text("Delete recording?") },
            text = {
                Text(
                    "\"${rec.filename.ifBlank { "Voice recording" }}\" will be removed. " +
                        "You can undo for a few seconds afterwards."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun sortLabel(order: RecordingSortOrder): String = when (order) {
    RecordingSortOrder.NEWEST -> "Newest"
    RecordingSortOrder.OLDEST -> "Oldest"
    RecordingSortOrder.LONGEST -> "Longest"
    RecordingSortOrder.SHORTEST -> "Shortest"
}

private fun statusLabel(status: String): String? = when (status) {
    "completed" -> null
    "pending" -> "Pending"
    "processing" -> "Processing"
    "failed" -> "Failed"
    else -> status.replaceFirstChar { it.uppercase() }
}

@Composable
private fun RecordingCard(
    item: RecordingListItem,
    isActive: Boolean,
    paused: Boolean,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    onTogglePause: () -> Unit,
    onSeek: (Long) -> Unit,
    onCycleSpeed: () -> Unit,
    onDelete: () -> Unit,
    onNoteClick: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    var transcriptExpanded by remember { mutableStateOf(false) }
    var sliderDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val status = statusLabel(item.recording.processingStatus)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = onTogglePause) {
                    Icon(
                        if (isActive && !paused) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isActive && !paused) "Pause" else "Play"
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.recording.filename.ifBlank { "Voice recording" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    val noteTitle = item.noteTitle?.takeIf { it.isNotBlank() }
                    if (noteTitle != null) {
                        Text(
                            text = noteTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier.clickable(onClick = onNoteClick)
                        )
                    } else {
                        Text(
                            text = "No linked note",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete recording",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isActive && durationMs > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatDurationMs(positionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp)
                    )
                    Slider(
                        value = if (sliderDragging) dragValue else positionMs.toFloat(),
                        onValueChange = {
                            dragValue = it
                            sliderDragging = true
                        },
                        onValueChangeFinished = {
                            onSeek(dragValue.toLong())
                            sliderDragging = false
                        },
                        valueRange = 0f..durationMs.toFloat(),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatDurationMs(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp)
                    )
                    AssistChip(
                        onClick = onCycleSpeed,
                        label = {
                            Text(
                                if (speed == 1f) "1x" else "${speed}x",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = timeFormat.format(Date(item.recording.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatDurationMs(item.recording.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (status != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge(
                        containerColor = if (item.recording.processingStatus == "failed") {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.tertiaryContainer
                        },
                        contentColor = if (item.recording.processingStatus == "failed") {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        }
                    ) {
                        Text(status, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                }
            }
            val transcript = item.fullTranscript
            if (transcript.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { transcriptExpanded = !transcriptExpanded }
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(
                            text = transcript,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (transcriptExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (transcriptExpanded) "Show less" else "Show transcript",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
