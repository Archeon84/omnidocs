package com.omnidocs.app.data.remote

import android.content.Context
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.entity.NoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class DriveServiceMergeTest {

    private lateinit var noteDao: NoteDao
    private lateinit var service: DriveService

    private fun entity(
        id: String = "n1",
        updatedAt: Long = 1000L,
        isSynced: Boolean = false,
        isDeleted: Boolean = false,
        deletedAt: Long? = null,
        content: String = "body"
    ) = NoteEntity(
        id = id,
        title = "T",
        content = content,
        plainText = content,
        isPinned = false,
        language = "en",
        createdAt = 500L,
        updatedAt = updatedAt,
        imageUrl = null,
        isSynced = isSynced,
        isDeleted = isDeleted,
        deletedAt = deletedAt
    )

    @Before
    fun setUp() {
        noteDao = mock(NoteDao::class.java)
        service = DriveService(mock(Context::class.java), noteDao)
    }

    @Test
    fun applyRemoteNote_insertsWhenNoLocal() = runBlocking {
        `when`(noteDao.getNoteByIdIncludeDeleted("n1")).thenReturn(null)
        val remote = entity(updatedAt = 2000L)

        service.applyRemoteNote(remote)

        verify(noteDao).insertNote(remote.copy(isSynced = true))
    }

    @Test
    fun applyRemoteNote_keepsNewerLocalUnsyncedEdit() = runBlocking {
        `when`(noteDao.getNoteByIdIncludeDeleted("n1"))
            .thenReturn(entity(updatedAt = 3000L, isSynced = false, content = "local edit"))
        val remote = entity(updatedAt = 2000L, content = "cloud older")

        service.applyRemoteNote(remote)

        verify(noteDao, never()).insertNote(remote.copy(isSynced = true))
    }

    @Test
    fun applyRemoteNote_acceptsNewerRemoteOverUnsyncedLocal() = runBlocking {
        `when`(noteDao.getNoteByIdIncludeDeleted("n1"))
            .thenReturn(entity(updatedAt = 1000L, isSynced = false, content = "local older"))
        val remote = entity(updatedAt = 2000L, content = "cloud newer")

        service.applyRemoteNote(remote)

        verify(noteDao).insertNote(remote.copy(isSynced = true))
    }

    @Test
    fun applyRemoteNote_acceptsRemoteWhenLocalSynced() = runBlocking {
        `when`(noteDao.getNoteByIdIncludeDeleted("n1"))
            .thenReturn(entity(updatedAt = 1000L, isSynced = true))
        val remote = entity(updatedAt = 1000L)

        service.applyRemoteNote(remote)

        verify(noteDao, times(1)).insertNote(remote.copy(isSynced = true))
    }

    @Test
    fun applyRemoteNote_remoteDeleteWinsWhenNewer() = runBlocking {
        `when`(noteDao.getNoteByIdIncludeDeleted("n1"))
            .thenReturn(entity(updatedAt = 1000L, isDeleted = false))
        val remote = entity(updatedAt = 1000L, isDeleted = true, deletedAt = 2000L)

        service.applyRemoteNote(remote)

        verify(noteDao, times(1)).insertNote(remote.copy(isSynced = true))
    }

    @Test
    fun applyRemoteNote_localDeleteWinsWhenNewer() = runBlocking {
        `when`(noteDao.getNoteByIdIncludeDeleted("n1"))
            .thenReturn(entity(updatedAt = 1000L, isDeleted = true, deletedAt = 3000L))
        val remote = entity(updatedAt = 500L, isDeleted = true, deletedAt = 600L)

        service.applyRemoteNote(remote)

        verify(noteDao, never()).insertNote(remote.copy(isSynced = true))
    }

    @Test
    fun applyRemoteNote_localUnsyncedEditWinsTimestampTie() = runBlocking {
        `when`(noteDao.getNoteByIdIncludeDeleted("n1"))
            .thenReturn(entity(updatedAt = 2000L, isSynced = false, content = "local edit"))
        val remote = entity(updatedAt = 2000L, content = "cloud copy")

        service.applyRemoteNote(remote)

        verify(noteDao, never()).insertNote(remote.copy(isSynced = true))
    }

    @Test
    fun applyRemoteNote_localDeleteWinsTimestampTie() = runBlocking {
        `when`(noteDao.getNoteByIdIncludeDeleted("n1"))
            .thenReturn(entity(updatedAt = 1000L, isDeleted = true, deletedAt = 2000L))
        val remote = entity(updatedAt = 500L, isDeleted = true, deletedAt = 2000L)

        service.applyRemoteNote(remote)

        verify(noteDao, never()).insertNote(remote.copy(isSynced = true))
    }
}
