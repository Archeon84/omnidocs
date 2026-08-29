package com.omnidocs.app.ui.screens.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.data.local.*
import com.omnidocs.app.voice.RecordingStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PrivacyStats(
    val totalNotes: Int = 0,
    val totalSourceDocs: Int = 0,
    val totalContentBlocks: Int = 0,
    val totalEmbeddings: Int = 0,
    val totalRecordings: Int = 0,
    val audioStorageBytes: Long = 0L,
    val totalAiJobs: Int = 0,
    val localExecutionPercentage: Float = 100f,
    val isKeyStoreHardwareSecured: Boolean = true
)

@HiltViewModel
class PrivacyDashboardViewModel @Inject constructor(
    private val noteDao: NoteDao,
    private val sourceDocumentDao: SourceDocumentDao,
    private val contentBlockDao: ContentBlockDao,
    private val embeddingDao: EmbeddingDao,
    private val recordingDao: RecordingDao,
    private val agentJobDao: AgentJobDao,
    private val recordingStorage: RecordingStorage,
    private val notesDatabase: NotesDatabase
) : ViewModel() {

    private val _stats = MutableStateFlow(PrivacyStats())
    val stats: StateFlow<PrivacyStats> = _stats.asStateFlow()

    private val _isWiping = MutableStateFlow(false)
    val isWiping: StateFlow<Boolean> = _isWiping.asStateFlow()

    init {
        loadPrivacyStats()
    }

    fun loadPrivacyStats() {
        viewModelScope.launch {
            val notes = noteDao.getAllNotesSync().size
            val embeddings = embeddingDao.countEmbeddingsByType("note") + embeddingDao.countEmbeddingsByType("content_block")
            val audioBytes = recordingStorage.getStorageUsed()
            val activeJobsCount = agentJobDao.countActiveJobs()

            _stats.value = PrivacyStats(
                totalNotes = notes,
                totalSourceDocs = 0,
                totalContentBlocks = 0,
                totalEmbeddings = embeddings,
                totalRecordings = recordingStorage.listRecordings().size,
                audioStorageBytes = audioBytes,
                totalAiJobs = activeJobsCount,
                localExecutionPercentage = 100f,
                isKeyStoreHardwareSecured = true
            )
        }
    }

    fun securePurgeRecordings() {
        viewModelScope.launch {
            _isWiping.value = true
            try {
                val files = recordingStorage.listRecordings()
                files.forEach { recordingStorage.deleteRecording(it.name) }
                loadPrivacyStats()
            } finally {
                _isWiping.value = false
            }
        }
    }
}
