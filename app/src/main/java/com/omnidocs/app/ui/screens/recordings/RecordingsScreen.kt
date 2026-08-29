package com.omnidocs.app.ui.screens.recordings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.RecordingDao
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.data.local.entity.RecordingEntity
import com.omnidocs.app.voice.AudioPlaybackController
import com.omnidocs.app.voice.RecordingStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** A recording plus the title of the note it belongs to (null if the note is gone). */
data class RecordingListItem(
    val recording: RecordingEntity,
    val noteTitle: String?
)

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val recordingDao: RecordingDao,
    private val noteDao: NoteDao,
    private val recordingStorage: RecordingStorage,
    val playbackController: AudioPlaybackController
) : ViewModel() {

    // id -> note, including soft-deleted ones, so the list can drop recordings
    // whose owning note was deleted (they'd open a blank editor).
    private val notesById = MutableStateFlow<Map<String, NoteEntity>>(emptyMap())

    val items: StateFlow<List<RecordingListItem>> =
        recordingDao.getAllRecordings()
            .combine(notesById) { recordings, notes ->
                recordings.mapNotNull { rec ->
                    val note = notes[rec.noteId]
                    if (note?.isDeleted == true) null
                    else RecordingListItem(rec, note?.title)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Load note metadata once; it doesn't change per-recording.
        viewModelScope.launch {
            notesById.value = noteDao.getAllNotesIncludeDeleted().associateBy { it.id }
        }
    }

    fun togglePlayback(recording: RecordingEntity) {
        playbackController.toggle(recording.storageKey)
    }

    fun delete(recording: RecordingEntity) {
        viewModelScope.launch {
            if (playbackController.isPlaying(recording.storageKey)) {
                playbackController.stop()
            }
            recordingDao.softDeleteRecording(recording.id)
            recordingStorage.deleteRecording(recording.storageKey)
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackController.stop()
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

/** Recordings list screen: all recordings with playback, reachable from Home. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    onBack: () -> Unit,
    onNoteClick: (String) -> Unit,
    viewModel: RecordingsViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsState()
    val playingKey by viewModel.playbackController.currentPlayingKey.collectAsState()
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }

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
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
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
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.recording.id }) { item ->
                    RecordingCard(
                        item = item,
                        isPlaying = playingKey == item.recording.storageKey,
                        dateFormat = dateFormat,
                        onTogglePlay = { viewModel.togglePlayback(item.recording) },
                        onDelete = { viewModel.delete(item.recording) },
                        onClick = { onNoteClick(item.recording.noteId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingCard(
    item: RecordingListItem,
    isPlaying: Boolean,
    dateFormat: SimpleDateFormat,
    onTogglePlay: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RecordingPlaybackChip(
                recording = item.recording,
                isPlaying = isPlaying,
                onTogglePlay = onTogglePlay,
                secondaryLabel = item.noteTitle?.takeIf { it.isNotBlank() }
                    ?: "No linked note",
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete recording",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(72.dp)) {
                Text(
                    text = dateFormat.format(Date(item.recording.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
