package com.omnidocs.app.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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

/**
 * Thread-safe private on-device storage for local offline chat sessions and message threads.
 * Keeps all conversation history strictly local in private encrypted app storage.
 */
@Singleton
class LocalChatStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val mutex = Mutex()
    private val baseDir = File(context.filesDir, "local_chats").apply { mkdirs() }
    private val indexFile = File(baseDir, "sessions_index.json")

    private val _sessions = MutableStateFlow<List<LocalChatSession>>(emptyList())
    val sessions: StateFlow<List<LocalChatSession>> = _sessions.asStateFlow()

    init {
        loadSessionsSync()
    }

    private fun loadSessionsSync() {
        try {
            if (indexFile.exists()) {
                val jsonStr = indexFile.readText()
                val jsonArr = JSONArray(jsonStr)
                val list = mutableListOf<LocalChatSession>()
                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.getJSONObject(i)
                    list.add(
                        LocalChatSession(
                            id = obj.getString("id"),
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
                }
                _sessions.value = list.sortedByDescending { it.updatedAt }
            }
        } catch (e: Exception) {
            _sessions.value = emptyList()
        }
    }

    private suspend fun persistSessionsIndex(list: List<LocalChatSession>) = withContext(Dispatchers.IO) {
        try {
            val jsonArr = JSONArray()
            for (s in list) {
                val obj = JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("personaId", s.personaId)
                    put("systemPrompt", s.systemPrompt)
                    put("createdAt", s.createdAt)
                    put("updatedAt", s.updatedAt)
                    put("modelId", s.modelId)
                    put("temperature", s.temperature.toDouble())
                    put("topP", s.topP.toDouble())
                    put("maxTokens", s.maxTokens)
                    put("messageCount", s.messageCount)
                }
                jsonArr.put(obj)
            }
            val tmp = File(baseDir, "sessions_index.tmp")
            tmp.writeText(jsonArr.toString())
            tmp.renameTo(indexFile)
        } catch (_: Exception) {}
    }

    suspend fun createSession(
        title: String = "New Chat",
        persona: ChatPersona = BUILTIN_PERSONAS.first(),
        modelId: String = "",
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        maxTokens: Int = 2048
    ): LocalChatSession = mutex.withLock {
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
        val updated = listOf(newSession) + _sessions.value.filter { it.id != newSession.id }
        _sessions.value = updated
        persistSessionsIndex(updated)
        newSession
    }

    suspend fun updateSession(session: LocalChatSession) = mutex.withLock {
        val current = _sessions.value.toMutableList()
        val index = current.indexOfFirst { it.id == session.id }
        if (index != -1) {
            current[index] = session
            val sorted = current.sortedByDescending { it.updatedAt }
            _sessions.value = sorted
            persistSessionsIndex(sorted)
        }
    }

    suspend fun renameSession(sessionId: String, newTitle: String) = mutex.withLock {
        val current = _sessions.value.toMutableList()
        val index = current.indexOfFirst { it.id == sessionId }
        if (index != -1) {
            val updated = current[index].copy(title = newTitle, updatedAt = System.currentTimeMillis())
            current[index] = updated
            val sorted = current.sortedByDescending { it.updatedAt }
            _sessions.value = sorted
            persistSessionsIndex(sorted)
        }
    }

    suspend fun deleteSession(sessionId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val msgFile = File(baseDir, "messages_${sessionId}.json")
            if (msgFile.exists()) msgFile.delete()
        }
        val updated = _sessions.value.filter { it.id != sessionId }
        _sessions.value = updated
        persistSessionsIndex(updated)
    }

    suspend fun clearAllSessions() = mutex.withLock {
        withContext(Dispatchers.IO) {
            baseDir.listFiles()?.forEach { it.delete() }
        }
        _sessions.value = emptyList()
    }

    suspend fun loadMessages(sessionId: String): List<LocalChatMessage> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = File(baseDir, "messages_${sessionId}.json")
                if (!file.exists()) return@withLock emptyList()
                val jsonArr = JSONArray(file.readText())
                val list = mutableListOf<LocalChatMessage>()
                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.getJSONObject(i)
                    list.add(
                        LocalChatMessage(
                            id = obj.getString("id"),
                            sessionId = obj.getString("sessionId"),
                            role = obj.getString("role"),
                            content = obj.getString("content"),
                            timestamp = obj.getLong("timestamp"),
                            durationMs = obj.optLong("durationMs", 0L),
                            tokenCount = obj.optInt("tokenCount", 0),
                            tokPerSec = obj.optDouble("tokPerSec", 0.0),
                            modelName = obj.optString("modelName", ""),
                            isError = obj.optBoolean("isError", false)
                        )
                    )
                }
                list.sortedBy { it.timestamp }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    suspend fun saveMessages(sessionId: String, messages: List<LocalChatMessage>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val jsonArr = JSONArray()
                for (m in messages) {
                    val obj = JSONObject().apply {
                        put("id", m.id)
                        put("sessionId", m.sessionId)
                        put("role", m.role)
                        put("content", m.content)
                        put("timestamp", m.timestamp)
                        put("durationMs", m.durationMs)
                        put("tokenCount", m.tokenCount)
                        put("tokPerSec", m.tokPerSec)
                        put("modelName", m.modelName)
                        put("isError", m.isError)
                    }
                    jsonArr.put(obj)
                }
                val file = File(baseDir, "messages_${sessionId}.json")
                val tmp = File(baseDir, "messages_${sessionId}.tmp")
                tmp.writeText(jsonArr.toString())
                tmp.renameTo(file)

                // Update session messageCount and updatedAt
                val current = _sessions.value.toMutableList()
                val sIdx = current.indexOfFirst { it.id == sessionId }
                if (sIdx != -1) {
                    val session = current[sIdx]
                    val derivedTitle = if (session.title == "New Chat" && messages.isNotEmpty()) {
                        val firstUser = messages.firstOrNull { it.role == "user" }?.content?.trim()
                        if (!firstUser.isNullOrBlank()) {
                            if (firstUser.length > 36) firstUser.take(34) + "…" else firstUser
                        } else session.title
                    } else session.title

                    val updated = session.copy(
                        title = derivedTitle,
                        messageCount = messages.size,
                        updatedAt = System.currentTimeMillis()
                    )
                    current[sIdx] = updated
                    val sorted = current.sortedByDescending { it.updatedAt }
                    _sessions.value = sorted
                    persistSessionsIndex(sorted)
                }
            } catch (_: Exception) {}
        }
    }
}
