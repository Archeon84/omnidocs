package com.omnidocs.app.sync

import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.entity.NoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString

class SyncQueueManagerTest {

    private lateinit var noteDao: NoteDao
    private lateinit var conflictResolver: NoteConflictResolver
    private lateinit var syncManager: SyncQueueManager

    private fun createNote(
        id: String,
        title: String,
        content: String,
        isSynced: Boolean = false
    ): NoteEntity {
        return NoteEntity(
            id = id,
            title = title,
            content = content,
            plainText = content,
            isPinned = false,
            language = "en",
            createdAt = 1000L,
            updatedAt = 2000L,
            imageUrl = null,
            isSynced = isSynced
        )
    }

    @Before
    fun setUp() {
        noteDao = mock(NoteDao::class.java)
        conflictResolver = NoteConflictResolver()
        syncManager = SyncQueueManager(noteDao, conflictResolver)
    }

    @Test
    fun testSyncNow_pushesUnsyncedLocalNotesAndPullsRemoteNotes() = runBlocking {
        val localUnsynced = listOf(
            createNote("note_1", "Local Note", "Local Content", isSynced = false)
        )
        val remoteNotes = listOf(
            createNote("note_2", "Remote Note", "Remote Content", isSynced = true)
        )

        `when`(noteDao.getUnsyncedNotesIncludingDeleted()).thenReturn(localUnsynced)
        `when`(noteDao.getNotesByIdsIncludeDeleted(listOf("note_2"))).thenReturn(emptyList()) // Brand new remote note

        val fakePeer = object : SyncPeer {
            override suspend fun pushNotes(notes: List<NoteEntity>): Result<List<String>> {
                return Result.success(notes.map { it.id })
            }

            override suspend fun fetchRemoteChanges(sinceTimestamp: Long): Result<List<NoteEntity>> {
                return Result.success(remoteNotes)
            }
        }

        val stats = syncManager.syncNow(fakePeer)

        assertEquals(SyncStatus.SUCCESS, syncManager.syncStatus.value)
        assertEquals(2, stats.syncedCount) // 1 pushed + 1 pulled
        assertEquals(0, stats.conflictCount)
        assertEquals(0, stats.failedCount)

        verify(noteDao, times(1)).markAsSynced(listOf("note_1"))
        verify(noteDao, times(1)).insertNote(remoteNotes[0])
    }

    @Test
    fun testSyncNow_detectsConflictWhenLocalAndRemoteBothHaveUnsyncedModifications() = runBlocking {
        val localNote = createNote("note_shared", "Local Title", "Local Body", isSynced = false)
        val remoteNote = createNote("note_shared", "Remote Title", "Remote Body", isSynced = true)

        `when`(noteDao.getUnsyncedNotesIncludingDeleted()).thenReturn(emptyList()) // Nothing to push
        `when`(noteDao.getNotesByIdsIncludeDeleted(listOf("note_shared"))).thenReturn(listOf(localNote)) // Local has unsynced edits!

        val fakePeer = object : SyncPeer {
            override suspend fun pushNotes(notes: List<NoteEntity>): Result<List<String>> = Result.success(emptyList())
            override suspend fun fetchRemoteChanges(sinceTimestamp: Long): Result<List<NoteEntity>> = Result.success(listOf(remoteNote))
        }

        val stats = syncManager.syncNow(fakePeer)

        assertEquals(SyncStatus.CONFLICT, syncManager.syncStatus.value)
        assertEquals(1, stats.conflictCount)
        assertEquals(1, syncManager.pendingConflicts.value.size)
        assertEquals("note_shared", syncManager.pendingConflicts.value.first().noteId)
    }

    @Test
    fun testSyncNow_pushesTombstoneDeletion() = runBlocking {
        val tombstone = createNote("note_del", "Deleted", "Content", isSynced = false).copy(
            isDeleted = true,
            deletedAt = 3000L
        )

        `when`(noteDao.getUnsyncedNotesIncludingDeleted()).thenReturn(listOf(tombstone))

        val fakePeer = object : SyncPeer {
            override suspend fun pushNotes(notes: List<NoteEntity>): Result<List<String>> = Result.success(notes.map { it.id })
            override suspend fun fetchRemoteChanges(sinceTimestamp: Long): Result<List<NoteEntity>> = Result.success(emptyList())
        }

        val stats = syncManager.syncNow(fakePeer)

        assertEquals(SyncStatus.SUCCESS, syncManager.syncStatus.value)
        assertEquals(1, stats.syncedCount)
        verify(noteDao).markAsSynced(listOf("note_del"))
    }

    @Test
    fun testSyncNow_newestWinsOnTombstoneConflict_localWins() = runBlocking {
        val localTombstone = createNote("note_x", "T", "C", isSynced = false).copy(
            isDeleted = true,
            deletedAt = 5000L
        )
        val remoteTombstone = createNote("note_x", "T", "C", isSynced = true).copy(
            isDeleted = true,
            deletedAt = 4000L
        )

        `when`(noteDao.getUnsyncedNotesIncludingDeleted()).thenReturn(listOf(localTombstone))
        `when`(noteDao.getNotesByIdsIncludeDeleted(listOf("note_x"))).thenReturn(listOf(localTombstone))

        val fakePeer = object : SyncPeer {
            override suspend fun pushNotes(notes: List<NoteEntity>): Result<List<String>> = Result.success(notes.map { it.id })
            override suspend fun fetchRemoteChanges(sinceTimestamp: Long): Result<List<NoteEntity>> = Result.success(listOf(remoteTombstone))
        }

        val stats = syncManager.syncNow(fakePeer)

        assertEquals(SyncStatus.SUCCESS, syncManager.syncStatus.value)
        // Local tombstone is newer: kept, and since the stub reports isSynced=false it gets marked synced
        verify(noteDao).markAsSynced("note_x")
        verify(noteDao, never()).insertNote(localTombstone.copy(isSynced = true))
    }

    @Test
    fun testSyncNow_newestWinsOnTombstoneConflict_remoteWins() = runBlocking {
        val localTombstone = createNote("note_y", "T", "C", isSynced = true).copy(
            isDeleted = true,
            deletedAt = 3000L
        )
        val remoteTombstone = createNote("note_y", "T", "C", isSynced = true).copy(
            isDeleted = true,
            deletedAt = 6000L
        )

        `when`(noteDao.getUnsyncedNotesIncludingDeleted()).thenReturn(emptyList())
        `when`(noteDao.getNotesByIdsIncludeDeleted(listOf("note_y"))).thenReturn(listOf(localTombstone))

        val fakePeer = object : SyncPeer {
            override suspend fun pushNotes(notes: List<NoteEntity>): Result<List<String>> = Result.success(emptyList())
            override suspend fun fetchRemoteChanges(sinceTimestamp: Long): Result<List<NoteEntity>> = Result.success(listOf(remoteTombstone))
        }

        val stats = syncManager.syncNow(fakePeer)

        assertEquals(SyncStatus.SUCCESS, syncManager.syncStatus.value)
        verify(noteDao).insertNote(remoteTombstone.copy(isSynced = true))
    }

    @Test
    fun testApplyMerge_remoteContentWinKeepsRemoteDerivedColumns() {
        val local = createNote("m1", "T", "local body", isSynced = false).copy(
            plainText = "local body",
            attachments = """["local.pdf"]""",
            isPinned = false,
            updatedAt = 1000L
        )
        val remote = createNote("m1", "T", "remote body", isSynced = true).copy(
            plainText = "remote body",
            attachments = """["remote.pdf"]""",
            isPinned = true,
            updatedAt = 2000L
        )
        val merged = NoteMergeResult(
            noteId = "m1",
            title = "T",
            content = "remote body",
            tags = "[]",
            isResolved = true
        )

        val result = syncManager.applyMerge(local, remote, merged)

        assertEquals("remote body", result.content)
        assertEquals("remote body", result.plainText)
        assertEquals("""["remote.pdf"]""", result.attachments)
        assertTrue(result.isPinned)
        assertTrue(result.isSynced)
    }

    @Test
    fun testApplyMerge_localContentWinKeepsLocalDerivedColumns() {
        val local = createNote("m1", "T", "local body", isSynced = false).copy(
            plainText = "local body",
            isPinned = true,
            updatedAt = 3000L
        )
        val remote = createNote("m1", "T", "remote body", isSynced = true).copy(
            plainText = "remote body",
            isPinned = false,
            updatedAt = 2000L
        )
        val merged = NoteMergeResult(
            noteId = "m1",
            title = "T",
            content = "local body",
            tags = "[]",
            isResolved = true
        )

        val result = syncManager.applyMerge(local, remote, merged)

        assertEquals("local body", result.plainText)
        assertTrue(result.isPinned)
    }

    @Test
    fun testComputeBackoffDelayMs_exponentialProgression() {
        assertEquals(0L, SyncQueueManager.computeBackoffDelayMs(0))
        assertEquals(1000L, SyncQueueManager.computeBackoffDelayMs(1))
        assertEquals(2000L, SyncQueueManager.computeBackoffDelayMs(2))
        assertEquals(4000L, SyncQueueManager.computeBackoffDelayMs(3))
        assertEquals(8000L, SyncQueueManager.computeBackoffDelayMs(4))
        assertEquals(60000L, SyncQueueManager.computeBackoffDelayMs(10, maxDelayMs = 60000L))
    }
}
