package com.omnidocs.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omnidocs.app.data.local.entity.ChatMessageEntity
import com.omnidocs.app.data.local.entity.ChatSessionEntity
import com.omnidocs.app.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Instrumented test verifying the Room database configuration, schema v18,
 * foreign key cascade relationships, and DAO transactions on device ART runtime.
 */
@RunWith(AndroidJUnit4::class)
class NotesDatabaseInstrumentationTest {

    private lateinit var db: NotesDatabase
    private lateinit var noteDao: NoteDao
    private lateinit var chatDao: ChatDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        noteDao = db.noteDao()
        chatDao = db.chatDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndReadNote() = runBlocking {
        val note = NoteEntity(
            id = "test-note-1",
            title = "Instrumented Test Note",
            content = "# Hello\nThis is an on-device test.",
            plainText = "Hello This is an on-device test.",
            isPinned = false,
            language = "en",
            imageUrl = null,
            tags = "[\"test\", \"android\"]",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        noteDao.insertNote(note)

        val retrieved = noteDao.getNoteById("test-note-1")
        assertNotNull(retrieved)
        assertEquals("Instrumented Test Note", retrieved?.title)
        assertEquals(false, retrieved?.isDeleted)
    }

    @Test
    fun insertChatSessionAndMessagesWithCascadeDelete() = runBlocking {
        val sessionId = "session-art-1"
        val session = ChatSessionEntity(
            id = sessionId,
            title = "Offline AI Conversation",
            personaId = "engineer",
            systemPrompt = "Code clearly",
            createdAt = 1000L,
            updatedAt = 1000L,
            modelId = "gemma",
            temperature = 0.7f,
            topP = 0.9f,
            maxTokens = 2048,
            messageCount = 2
        )
        chatDao.insertSession(session)

        val msg1 = ChatMessageEntity(
            id = "msg-1",
            sessionId = sessionId,
            role = "user",
            content = "Write a binary search in Kotlin",
            timestamp = 1001L,
            durationMs = 0L,
            tokenCount = 7,
            tokPerSec = 0.0,
            modelName = "gemma",
            isError = false
        )
        val msg2 = ChatMessageEntity(
            id = "msg-2",
            sessionId = sessionId,
            role = "assistant",
            content = "fun binarySearch(...) = ...",
            timestamp = 1002L,
            durationMs = 250L,
            tokenCount = 25,
            tokPerSec = 100.0,
            modelName = "gemma",
            isError = false
        )
        chatDao.insertMessages(listOf(msg1, msg2))

        val messages = chatDao.getMessages(sessionId)
        assertEquals(2, messages.size)
        assertEquals("msg-1", messages[0].id)
        assertEquals("msg-2", messages[1].id)

        // Verify foreign key CASCADE: deleting the session removes its messages
        chatDao.deleteSession(sessionId)
        val remainingSessions = chatDao.getSessions()
        assertTrue(remainingSessions.none { it.id == sessionId })

        val remainingMessages = chatDao.getMessages(sessionId)
        assertTrue(remainingMessages.isEmpty())
    }

    @Test
    fun updateAndRenameChatSession() = runBlocking {
        val sessionId = "session-rename-1"
        val session = ChatSessionEntity(
            id = sessionId,
            title = "Original Title",
            personaId = "general",
            systemPrompt = "Default",
            createdAt = 5000L,
            updatedAt = 5000L,
            modelId = "",
            temperature = 0.7f,
            topP = 0.9f,
            maxTokens = 2048,
            messageCount = 0
        )
        chatDao.insertSession(session)

        chatDao.renameSession(sessionId, "Renamed Title", 6000L)
        val updated = chatDao.getSession(sessionId)
        assertNotNull(updated)
        assertEquals("Renamed Title", updated?.title)
        assertEquals(6000L, updated?.updatedAt)
    }
}
