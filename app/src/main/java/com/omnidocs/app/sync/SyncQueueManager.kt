package com.omnidocs.app.sync

import com.omnidocs.app.data.local.EmbeddingDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
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
    private val conflictResolver: NoteConflictResolver,
    private val embeddingDao: EmbeddingDao? = null
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
            var pullSucceeded = true

            // 1. Push local unsynced changes to peer (tombstones included so
            //    deletions propagate instead of silently reviving on other devices).
            //    Paginated so a large offline backlog never ships in one giant RPC.
            val unsyncedLocal = noteDao.getUnsyncedNotesIncludingDeleted()
            if (unsyncedLocal.isNotEmpty()) {
                val acceptedIds = mutableListOf<String>()
                var pushFailed = false
                for (page in unsyncedLocal.chunked(PUSH_PAGE_SIZE)) {
                    val pushResult = peer.pushNotes(page)
                    pushResult.onSuccess { acceptedIds.addAll(it) }
                        .onFailure { pushFailed = true }
                    if (pushFailed) break
                }
                if (pushFailed) {
                    failed += unsyncedLocal.size
                    pullSucceeded = false
                } else if (acceptedIds.isNotEmpty()) {
                    noteDao.markAsSynced(acceptedIds)
                    synced += acceptedIds.size
                }
            }

            // 2. Fetch remote changes since last sync
            val lastSyncTime = _syncStats.value.lastSyncTimestampMs ?: 0L
            val pullResult = peer.fetchRemoteChanges(lastSyncTime)

            pullResult.onSuccess { remoteNotes ->
                // Single batch fetch for all remote IDs instead of one query per note
                val localsById = if (remoteNotes.isNotEmpty()) {
                    noteDao.getNotesByIdsIncludeDeleted(remoteNotes.map { it.id })
                        .associateBy { it.id }
                } else {
                    emptyMap()
                }
                for (remote in remoteNotes) {
                    val local = localsById[remote.id]
                    if (local == null) {
                        noteDao.insertNote(remote.copy(isSynced = true))
                        synced++
                    } else if (local.isDeleted || remote.isDeleted) {
                        val localEffective = local.deletedAt ?: local.updatedAt
                        val remoteEffective = remote.deletedAt ?: remote.updatedAt
                        if (localEffective >= remoteEffective) {
                            if (!local.isSynced) {
                                noteDao.markAsSynced(local.id)
                            }
                        } else {
                            noteDao.insertNote(remote.copy(isSynced = true))
                            synced++
                        }
                    } else if (local.isSynced) {
                        noteDao.insertNote(remote.copy(isSynced = true))
                        synced++
                    } else {
                        val conflictReport = conflictResolver.detectConflicts(
                            baseNote = null,
                            localNote = local,
                            remoteNote = remote
                        )
                        if (conflictReport.hasConflicts) {
                            detectedConflictReports.add(conflictReport)
                            conflicts++
                        } else {
                            val merged = conflictResolver.resolveConflicts(conflictReport, local, remote)
                            noteDao.insertNote(applyMerge(local, remote, merged))
                            synced++
                        }
                    }
                }
            }.onFailure {
                failed++
                pullSucceeded = false
            }

            _pendingConflicts.value = detectedConflictReports

            val status = when {
                conflicts > 0 -> SyncStatus.CONFLICT
                failed > 0 && synced == 0 -> SyncStatus.ERROR
                else -> SyncStatus.SUCCESS
            }

            // Only advance the delta cursor when the full pass succeeded. Advancing on a
            // failed pull would permanently skip remote changes created before the new
            // cursor, so keep the previous timestamp to retry them next pass.
            val newStats = SyncStats(
                syncedCount = synced,
                conflictCount = conflicts,
                failedCount = failed,
                lastSyncTimestampMs = if (pullSucceeded) {
                    System.currentTimeMillis()
                } else {
                    _syncStats.value.lastSyncTimestampMs
                }
            )

            _syncStatus.value = status
            _syncStats.value = newStats

            if (status == SyncStatus.SUCCESS || status == SyncStatus.CONFLICT) {
                purgeOldTombstones()
            }

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

        noteDao.insertNote(applyMerge(localNote, remoteNote, merged))

        _pendingConflicts.value = _pendingConflicts.value.filter { it.noteId != report.noteId }
        if (_pendingConflicts.value.isEmpty() && _syncStatus.value == SyncStatus.CONFLICT) {
            _syncStatus.value = SyncStatus.SUCCESS
        }
    }

    companion object {
        private const val TOMBSTONE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
        private const val PUSH_PAGE_SIZE = 100
        private val TAG = SyncQueueManager::class.java.simpleName

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

    private suspend fun purgeOldTombstones() {
        val cutoff = System.currentTimeMillis() - TOMBSTONE_RETENTION_MS
        // Clean embeddings first: the hard delete below would orphan them,
        // inflating vector scans and suppressing the lazy-reindex trigger.
        try {
            val purgeIds = noteDao.getPurgeableNoteIds(cutoff)
            if (purgeIds.isNotEmpty()) {
                embeddingDao?.deleteEmbeddingsBySourceIds(purgeIds)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Purge embedding cleanup failed", e)
        }
        noteDao.purgeDeletedNotesOlderThan(cutoff)
    }

    /**
     * Build the merged row preserving columns the text merge doesn't model.
     * Content-derived columns (plainText, attachments, imageUrl, language)
     * follow whichever side won the content; pin/related-notes follow the
     * newer side. Without this, a remote word-edit silently discarded local
     * media/pins and left plainText disagreeing with content (breaking search).
     */
    internal fun applyMerge(
        local: NoteEntity,
        remote: NoteEntity,
        merged: NoteMergeResult
    ): NoteEntity {
        val remoteWonContent = merged.content == remote.content && merged.content != local.content
        val contentSide = if (remoteWonContent) remote else local
        val newerSide = if (remote.updatedAt >= local.updatedAt) remote else local
        return local.copy(
            title = merged.title,
            content = merged.content,
            plainText = contentSide.plainText,
            attachments = contentSide.attachments,
            imageUrl = contentSide.imageUrl,
            language = contentSide.language,
            tags = merged.tags,
            isPinned = newerSide.isPinned,
            relatedNotes = newerSide.relatedNotes,
            isSynced = true,
            updatedAt = System.currentTimeMillis()
        )
    }
}
