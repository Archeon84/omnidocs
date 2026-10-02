package com.omnidocs.app.data.local

import android.content.Context
import com.omnidocs.app.data.local.entity.ChatMessageEntity
import com.omnidocs.app.data.local.entity.ChatSessionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persona presets for offline local LLM chat.
 */
data class ChatPersona(
    val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val systemPrompt: String
)

val BUILTIN_PERSONAS = listOf(
    ChatPersona(
        id = "general",
        name = "General Assistant",
        icon = "🤖",
        description = "Helpful, thoughtful, and structured on-device assistant",
        systemPrompt = "You are a helpful, knowledgeable, and thoughtful AI assistant running entirely offline on the user's device. Answer clearly, accurately, and politely."
    ),
    ChatPersona(
        id = "engineer",
        name = "Software Engineer",
        icon = "💻",
        description = "Clean code, debugging, architecture, and best practices",
        systemPrompt = "You are an expert senior software engineer. Provide clean, idiomatic, well-structured code with concise explanations. Format code blocks with appropriate language tags."
    ),
    ChatPersona(
        id = "analyst",
        name = "Deep Analyst",
        icon = "🧠",
        description = "Step-by-step reasoning, critical analysis, and balanced breakdown",
        systemPrompt = "You are a rigorous analytical thinker. Break down problems step-by-step, evaluate trade-offs objectively, and present structured takeaways."
    ),
    ChatPersona(
        id = "writer",
        name = "Creative Writer",
        icon = "✍️",
        description = "Engaging storytelling, expressive prose, and copy polish",
        systemPrompt = "You are a versatile creative writer and editor. Craft expressive, polished, and compelling text with varied sentence structures and vivid style."
    ),
    ChatPersona(
        id = "concise",
        name = "Concise & Direct",
        icon = "⚡",
        description = "Fast, straight-to-the-point answers with zero filler",
        systemPrompt = "You are an efficient, concise assistant. Answer directly and precisely without preamble, conversational fluff, or repetition."
    ),
    ChatPersona(
        id = "custom",
        name = "Custom Persona",
        icon = "🛠️",
        description = "User-defined customized instructions",
        systemPrompt = ""
    )
)

data class LocalChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val personaId: String = "general",
    val systemPrompt: String = BUILTIN_PERSONAS.first().systemPrompt,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val modelId: String = "",
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val maxTokens: Int = 2048,
    val messageCount: Int = 0
)

data class LocalChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val tokenCount: Int = 0,
    val tokPerSec: Double = 0.0,
    val modelName: String = "",
    val isError: Boolean = false
)

fun ChatSessionEntity.toModel(): LocalChatSession = LocalChatSession(
    id = id,
    title = title,
    personaId = personaId,
    systemPrompt = systemPrompt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    modelId = modelId,
    temperature = temperature,
    topP = topP,
    maxTokens = maxTokens,
    messageCount = messageCount
)

fun LocalChatSession.toEntity(): ChatSessionEntity = ChatSessionEntity(
    id = id,
    title = title,
    personaId = personaId,
    systemPrompt = systemPrompt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    modelId = modelId,
    temperature = temperature,
    topP = topP,
    maxTokens = maxTokens,
    messageCount = messageCount
)

fun ChatMessageEntity.toModel(): LocalChatMessage = LocalChatMessage(
    id = id,
    sessionId = sessionId,
    role = role,
    content = content,
    timestamp = timestamp,
    durationMs = durationMs,
    tokenCount = tokenCount,
    tokPerSec = tokPerSec,
    modelName = modelName,
    isError = isError
)

fun LocalChatMessage.toEntity(): ChatMessageEntity = ChatMessageEntity(
    id = id,
    sessionId = sessionId,
    role = role,
    content = content,
    timestamp = timestamp,
    durationMs = durationMs,
    tokenCount = tokenCount,
    tokPerSec = tokPerSec,
    modelName = modelName,
    isError = isError
)

/**
 * Thread-safe on-device storage for local offline chat sessions and message threads.
 * Backed by SQLCipher-encrypted Room database (`chat_sessions` and `chat_messages` tables),
 * providing AES-256 encryption at rest and automatic inclusion in LocalBackupService.
 */
@Singleton
class LocalChatStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatDao: ChatDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val baseDir = File(context.filesDir, "local_chats")
    private val indexFile = File(baseDir, "sessions_index.json")

    val sessions: StateFlow<List<LocalChatSession>> = chatDao.getSessionsFlow()
        .map { entities -> entities.map { it.toModel() } }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    init {
        scope.launch {
            migrateLegacyJsonFilesIfNeeded()
        }
    }

    private suspend fun migrateLegacyJsonFilesIfNeeded() = withContext(Dispatchers.IO) {
        try {
            if (indexFile.exists()) {
                val jsonStr = indexFile.readText()
                val jsonArr = JSONArray(jsonStr)
                val sessionsToInsert = mutableListOf<ChatSessionEntity>()
                val messagesToInsert = mutableListOf<ChatMessageEntity>()

                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.getJSONObject(i)
                    val sessionId = obj.getString("id")
                    sessionsToInsert.add(
                        ChatSessionEntity(
                            id = sessionId,
                            title = obj.getString("title"),
                            personaId = obj.optString("personaId", "general"),
                            systemPrompt = obj.optString("systemPrompt", BUILTIN_PERSONAS.first().systemPrompt),
                            createdAt = obj.getLong("createdAt"),
                            updatedAt = obj.getLong("updatedAt"),
                            modelId = obj.optString("modelId", ""),
                            temperature = obj.optDouble("temperature", 0.7).toFloat(),
                            topP = obj.optDouble("topP", 0.9).toFloat(),
                            maxTokens = obj.optInt("maxTokens", 2048),
                            messageCount = obj.optInt("messageCount", 0)
                        )
                    )

                    val msgFile = File(baseDir, "messages_${sessionId}.json")
                    if (msgFile.exists()) {
                        try {
                            val msgArr = JSONArray(msgFile.readText())
                            for (j in 0 until msgArr.length()) {
                                val mObj = msgArr.getJSONObject(j)
                                messagesToInsert.add(
                                    ChatMessageEntity(
                                        id = mObj.getString("id"),
                                        sessionId = mObj.getString("sessionId"),
                                        role = mObj.getString("role"),
                                        content = mObj.getString("content"),
                                        timestamp = mObj.getLong("timestamp"),
                                        durationMs = mObj.optLong("durationMs", 0L),
                                        tokenCount = mObj.optInt("tokenCount", 0),
                                        tokPerSec = mObj.optDouble("tokPerSec", 0.0),
                                        modelName = mObj.optString("modelName", ""),
                                        isError = mObj.optBoolean("isError", false)
                                    )
                                )
                            }
                        } catch (_: Exception) {}
                    }
                }

                if (sessionsToInsert.isNotEmpty()) {
                    chatDao.insertSessions(sessionsToInsert)
                }
                if (messagesToInsert.isNotEmpty()) {
                    chatDao.insertMessages(messagesToInsert)
                }

                baseDir.deleteRecursively()
            }
        } catch (_: Exception) {}
    }

    suspend fun createSession(
        title: String = "New Chat",
        persona: ChatPersona = BUILTIN_PERSONAS.first(),
        modelId: String = "",
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        maxTokens: Int = 2048
    ): LocalChatSession = withContext(Dispatchers.IO) {
        val newSession = LocalChatSession(
            id = UUID.randomUUID().toString(),
            title = title,
            personaId = persona.id,
            systemPrompt = persona.systemPrompt,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            modelId = modelId,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            messageCount = 0
        )
        chatDao.insertSession(newSession.toEntity())
        newSession
    }

    suspend fun updateSession(session: LocalChatSession) = withContext(Dispatchers.IO) {
        chatDao.updateSession(session.toEntity())
    }

    suspend fun renameSession(sessionId: String, newTitle: String) = withContext(Dispatchers.IO) {
        chatDao.renameSession(sessionId, newTitle, System.currentTimeMillis())
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        chatDao.deleteSession(sessionId)
    }

    suspend fun clearAllSessions() = withContext(Dispatchers.IO) {
        chatDao.clearAllSessions()
    }

    suspend fun loadMessages(sessionId: String): List<LocalChatMessage> = withContext(Dispatchers.IO) {
        chatDao.getMessages(sessionId).map { it.toModel() }
    }

    suspend fun saveMessages(sessionId: String, messages: List<LocalChatMessage>) = withContext(Dispatchers.IO) {
        chatDao.deleteMessagesForSession(sessionId)
        chatDao.insertMessages(messages.map { it.toEntity() })

        val session = chatDao.getSession(sessionId)
        if (session != null) {
            val derivedTitle = if (session.title == "New Chat" && messages.isNotEmpty()) {
                val firstUser = messages.firstOrNull { it.role == "user" }?.content?.trim()
                if (!firstUser.isNullOrBlank()) {
                    if (firstUser.length > 36) firstUser.take(34) + "…" else firstUser
                } else session.title
            } else session.title

            chatDao.updateSession(
                session.copy(
                    title = derivedTitle,
                    messageCount = messages.size,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }
}
