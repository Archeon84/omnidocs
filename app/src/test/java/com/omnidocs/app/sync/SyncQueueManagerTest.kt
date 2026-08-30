package com.omnidocs.app.sync

import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.entity.NoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

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

        `when`(noteDao.getUnsyncedNotes()).thenReturn(localUnsynced)
        `when`(noteDao.getNoteById("note_2")).thenReturn(null) // Brand new remote note

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

        `when`(noteDao.getUnsyncedNotes()).thenReturn(emptyList()) // Nothing to push
        `when`(noteDao.getNoteById("note_shared")).thenReturn(localNote) // Local has unsynced edits!

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
    fun testComputeBackoffDelayMs_exponentialProgression() {
        assertEquals(0L, SyncQueueManager.computeBackoffDelayMs(0))
        assertEquals(1000L, SyncQueueManager.computeBackoffDelayMs(1))
        assertEquals(2000L, SyncQueueManager.computeBackoffDelayMs(2))
        assertEquals(4000L, SyncQueueManager.computeBackoffDelayMs(3))
        assertEquals(8000L, SyncQueueManager.computeBackoffDelayMs(4))
        assertEquals(60000L, SyncQueueManager.computeBackoffDelayMs(10, maxDelayMs = 60000L))
    }
}
