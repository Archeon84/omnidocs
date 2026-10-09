package com.omnidocs.app.data.local

import android.content.Context
import com.omnidocs.app.data.local.entity.ChatMessageEntity
import com.omnidocs.app.data.local.entity.ChatSessionEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LocalChatStorageTest {

    private lateinit var context: Context
    private lateinit var chatDao: ChatDao
    private lateinit var storage: LocalChatStorage
    private lateinit var tempDir: File

    @Before
    fun setup() {
        context = mock(Context::class.java)
        chatDao = mock(ChatDao::class.java)
        tempDir = File(System.getProperty("java.io.tmpdir"), "test_local_chats_${System.currentTimeMillis()}")
        `when`(context.filesDir).thenReturn(tempDir)
        `when`(chatDao.getSessionsFlow()).thenReturn(flowOf(emptyList()))

        storage = LocalChatStorage(context, chatDao)
    }

    private fun <T : Any> captureNonNull(captor: ArgumentCaptor<T>, fallback: T): T {
        captor.capture()
        return fallback
    }

    private fun <T : Any> eqNonNull(value: T): T {
        org.mockito.Mockito.eq(value)
        return value
    }

    @Test
    fun `createSession persists new session via ChatDao`() = runTest {
        val session = storage.createSession(title = "My Test Chat")
        assertEquals("My Test Chat", session.title)
        assertEquals("general", session.personaId)

        val captor = ArgumentCaptor.forClass(ChatSessionEntity::class.java)
        verify(chatDao).insertSession(captureNonNull(captor, session.toEntity()))
        assertEquals(session.id, captor.value.id)
        assertEquals("My Test Chat", captor.value.title)
    }

    @Test
    fun `renameSession calls chatDao renameSession with timestamp`() = runTest {
        storage.renameSession("session-123", "New Name")
        verify(chatDao).renameSession(eqNonNull("session-123"), eqNonNull("New Name"), anyLong())
    }

    @Test
    fun `deleteSession calls chatDao deleteSession`() = runTest {
        storage.deleteSession("session-123")
        verify(chatDao).deleteSession("session-123")
    }

    @Test
    fun `loadMessages maps entities from chatDao to model`() = runTest {
        val entity = ChatMessageEntity(
            id = "msg-1",
            sessionId = "session-1",
            role = "user",
            content = "Hello offline world",
            timestamp = 1000L,
            durationMs = 0L,
            tokenCount = 5,
            tokPerSec = 0.0,
            modelName = "gemma",
            isError = false
        )
        `when`(chatDao.getMessages("session-1")).thenReturn(listOf(entity))

        val messages = storage.loadMessages("session-1")
        assertEquals(1, messages.size)
        assertEquals("msg-1", messages[0].id)
        assertEquals("Hello offline world", messages[0].content)
        assertEquals("user", messages[0].role)
    }

    @Test
    fun `saveMessages replaces messages and updates session messageCount and title`() = runTest {
        val sessionEntity = ChatSessionEntity(
            id = "session-1",
            title = "New Chat",
            personaId = "general",
            systemPrompt = "sys",
            createdAt = 1000L,
            updatedAt = 1000L,
            modelId = "gemma",
            temperature = 0.7f,
            topP = 0.9f,
            maxTokens = 2048,
            messageCount = 0
        )
        `when`(chatDao.getSession("session-1")).thenReturn(sessionEntity)

        val newMessages = listOf(
            LocalChatMessage(
                id = "m1",
                sessionId = "session-1",
                role = "user",
                content = "What is Kotlin coroutines?"
            )
        )

        storage.saveMessages("session-1", newMessages)

        verify(chatDao).deleteMessagesForSession("session-1")
        verify(chatDao).insertMessages(anyList())

        val sessionCaptor = ArgumentCaptor.forClass(ChatSessionEntity::class.java)
        verify(chatDao).updateSession(captureNonNull(sessionCaptor, sessionEntity))
        assertEquals(1, sessionCaptor.value.messageCount)
        assertEquals("What is Kotlin coroutines?", sessionCaptor.value.title)
    }

    @Test
    fun `entity mapping functions roundtrip cleanly`() {
        val session = LocalChatSession(
            id = "s-1",
            title = "Test",
            personaId = "engineer",
            systemPrompt = "prompt",
            createdAt = 1234L,
            updatedAt = 5678L,
            modelId = "gemma",
            temperature = 0.5f,
            topP = 0.8f,
            maxTokens = 1024,
            messageCount = 3
        )
        val entity = session.toEntity()
        val mappedBack = entity.toModel()
        assertEquals(session, mappedBack)

        val message = LocalChatMessage(
            id = "m-1",
            sessionId = "s-1",
            role = "assistant",
            content = "Here is the response",
            timestamp = 9999L,
            durationMs = 120L,
            tokenCount = 42,
            tokPerSec = 15.5,
            modelName = "gemma-4",
            isError = false
        )
        val messageEntity = message.toEntity()
        val messageBack = messageEntity.toModel()
        assertEquals(message, messageBack)
    }
}
