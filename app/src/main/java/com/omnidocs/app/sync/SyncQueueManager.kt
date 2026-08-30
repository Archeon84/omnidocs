package com.omnidocs.app.sync

import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

enum class SyncStatus {
    IDLE,
    SYNCING,
    SUCCESS,
    CONFLICT,
    ERROR
}

data class SyncStats(
    val syncedCount: Int = 0,
    val conflictCount: Int = 0,
    val failedCount: Int = 0,
    val lastSyncTimestampMs: Long? = null
)

interface SyncPeer {
    suspend fun pushNotes(notes: List<NoteEntity>): Result<List<String>> // Returns list of accepted note IDs
    suspend fun fetchRemoteChanges(sinceTimestamp: Long): Result<List<NoteEntity>>
}

/**
 * Manages the offline-first synchronization queue, exponential backoff retries,
 * conflict reporting, and reactive sync state indicators.
 */
@Singleton
class SyncQueueManager @Inject constructor(
    private val noteDao: NoteDao,
    private val conflictResolver: NoteConflictResolver
) {

    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _syncStats = MutableStateFlow(SyncStats())
    val syncStats: StateFlow<SyncStats> = _syncStats.asStateFlow()

    private val _pendingConflicts = MutableStateFlow<List<NoteConflictReport>>(emptyList())
    val pendingConflicts: StateFlow<List<NoteConflictReport>> = _pendingConflicts.asStateFlow()

    /**
     * Executes a bidirectional synchronization pass with the provided SyncPeer.
     */
    suspend fun syncNow(peer: SyncPeer): SyncStats {
        _syncStatus.value = SyncStatus.SYNCING
        var synced = 0
        var conflicts = 0
        var failed = 0
        val detectedConflictReports = mutableListOf<NoteConflictReport>()

        try {
            // 1. Push local unsynced changes to peer
            val unsyncedLocal = noteDao.getUnsyncedNotes()
            if (unsyncedLocal.isNotEmpty()) {
                val pushResult = peer.pushNotes(unsyncedLocal)
                pushResult.onSuccess { acceptedIds ->
                    if (acceptedIds.isNotEmpty()) {
                        noteDao.markAsSynced(acceptedIds)
                        synced += acceptedIds.size
                    }
                }.onFailure {
                    failed += unsyncedLocal.size
                }
            }

            // 2. Fetch remote changes since last sync
            val lastSyncTime = _syncStats.value.lastSyncTimestampMs ?: 0L
            val pullResult = peer.fetchRemoteChanges(lastSyncTime)

            pullResult.onSuccess { remoteNotes ->
                for (remote in remoteNotes) {
                    val local = noteDao.getNoteById(remote.id)
                    if (local == null) {
                        // Brand new remote note: insert directly
                        noteDao.insertNote(remote.copy(isSynced = true))
                        synced++
                    } else if (local.isSynced) {
                        // Local has no unsaved offline edits: accept remote version
                        noteDao.insertNote(remote.copy(isSynced = true))
                        synced++
                    } else {
                        // Both local and remote have independent modifications: run 3-way conflict detection!
                        val conflictReport = conflictResolver.detectConflicts(
                            baseNote = null, // In 2-peer sync, compare local vs remote
                            localNote = local,
                            remoteNote = remote
                        )

                        if (conflictReport.hasConflicts) {
                            detectedConflictReports.add(conflictReport)
                            conflicts++
                        } else {
                            // Clean auto-merge
                            val merged = conflictResolver.resolveConflicts(conflictReport, local, remote)
                            noteDao.insertNote(
                                local.copy(
                                    title = merged.title,
                                    content = merged.content,
                                    tags = merged.tags,
                                    isSynced = true,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                            synced++
                        }
                    }
                }
            }.onFailure {
                failed++
            }

            _pendingConflicts.value = detectedConflictReports

            val status = when {
                conflicts > 0 -> SyncStatus.CONFLICT
                failed > 0 && synced == 0 -> SyncStatus.ERROR
                else -> SyncStatus.SUCCESS
            }

            val newStats = SyncStats(
                syncedCount = synced,
                conflictCount = conflicts,
                failedCount = failed,
                lastSyncTimestampMs = System.currentTimeMillis()
            )

            _syncStatus.value = status
            _syncStats.value = newStats
            return newStats
        } catch (e: Exception) {
            _syncStatus.value = SyncStatus.ERROR
            val newStats = _syncStats.value.copy(failedCount = _syncStats.value.failedCount + 1)
            _syncStats.value = newStats
            return newStats
        }
    }

    /**
     * Resolves a pending conflict and applies the user's resolution choices to the database.
     */
    suspend fun resolvePendingConflict(
        report: NoteConflictReport,
        localNote: NoteEntity,
        remoteNote: NoteEntity,
        choices: Map<ConflictField, ConflictResolutionChoice>,
        customValues: Map<ConflictField, String> = emptyMap()
    ) {
        val merged = conflictResolver.resolveConflicts(
            report = report,
            localNote = localNote,
            remoteNote = remoteNote,
            choices = choices,
            customValues = customValues
        )

        noteDao.insertNote(
            localNote.copy(
                title = merged.title,
                content = merged.content,
                tags = merged.tags,
                isSynced = true,
                updatedAt = System.currentTimeMillis()
            )
        )

        _pendingConflicts.value = _pendingConflicts.value.filter { it.noteId != report.noteId }
        if (_pendingConflicts.value.isEmpty() && _syncStatus.value == SyncStatus.CONFLICT) {
            _syncStatus.value = SyncStatus.SUCCESS
        }
    }

    companion object {
        /**
         * Calculates exponential backoff delay in milliseconds.
         */
        fun computeBackoffDelayMs(
            retryAttempt: Int,
            baseDelayMs: Long = 1000L,
            maxDelayMs: Long = 60_000L
        ): Long {
            if (retryAttempt <= 0) return 0L
            val exp = 2.0.pow((retryAttempt - 1).coerceAtMost(10).toDouble())
            val delay = (baseDelayMs * exp).toLong()
            return min(delay, maxDelayMs)
        }
    }
}
