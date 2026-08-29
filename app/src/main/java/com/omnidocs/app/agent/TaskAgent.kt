package com.omnidocs.app.agent

import com.omnidocs.app.ai.LlamaCppService
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.ai.resolveActiveModel
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class ExtractedTask(
    val title: String,
    val description: String = "",
    val owner: String? = null,
    val dueAt: Long? = null,
    val priority: String = "MEDIUM"
)

/**
 * Agent responsible for detecting, parsing, and proposing action items,
 * assignees, and target due dates from meeting transcripts and note contents.
 */
@Singleton
class TaskAgent @Inject constructor(
    private val llamaCppService: LlamaCppService,
    private val modelPreferences: ModelPreferences,
    private val modelDownloadManager: ModelDownloadManager
) : Agent {

    override val id: String = "agent_task"

    override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Task extraction cancelled by user")
        }

        val text = input.payload["text"] ?: ""
        if (text.isBlank()) {
            return AgentResult.Success(
                payload = mapOf("tasksJson" to "[]", "taskCount" to 0)
            )
        }

        val tasks = extractTasks(text)
        val jsonArray = JSONArray()
        for (task in tasks) {
            val obj = JSONObject()
            obj.put("title", task.title)
            obj.put("description", task.description)
            obj.put("owner", task.owner ?: "")
            obj.put("dueAt", task.dueAt ?: -1L)
            obj.put("priority", task.priority)
            jsonArray.put(obj)
        }

        return AgentResult.Success(
            payload = mapOf(
                "tasksJson" to jsonArray.toString(),
                "taskCount" to tasks.size
            )
        )
    }

    suspend fun extractTasks(text: String): List<ExtractedTask> {
        if (text.length in 50..4000) {
            val model = resolveActiveModel(modelPreferences, modelDownloadManager)
            if (model != null) {
                try {
                    val prompt = """Analyze the following text and extract actionable tasks.
Return ONLY a valid JSON array of objects with keys: "title", "owner", "priority" (LOW, MEDIUM, HIGH).
Do not include commentary or markdown formatting.

Text:
${text.take(1500)}

JSON:"""
                    val raw = llamaCppService.generate(prompt, maxTokens = 300)
                    if (raw != null) {
                        val parsed = parseJsonTasks(raw)
                        if (parsed.isNotEmpty()) return parsed
                    }
                } catch (e: Exception) {
                    // Fall back to pattern matching
                }
            }
        }
        return heuristicExtractTasks(text)
    }

    private fun parseJsonTasks(raw: String): List<ExtractedTask> {
        return try {
            val clean = raw.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val array = JSONArray(clean)
            val list = mutableListOf<ExtractedTask>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val title = obj.optString("title", "").trim()
                if (title.isNotBlank()) {
                    list.add(
                        ExtractedTask(
                            title = title,
                            owner = obj.optString("owner").takeIf { it.isNotBlank() },
                            priority = obj.optString("priority", "MEDIUM")
                        )
                    )
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun heuristicExtractTasks(text: String): List<ExtractedTask> {
        val patterns = listOf(
            Regex("(?i)\\b(todo|action item|task|must|perlu|sila|need to):?\\s*(.+)"),
            Regex("(?i)^\\[ \\]\\s*(.+)")
        )
        val list = mutableListOf<ExtractedTask>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            for (p in patterns) {
                val match = p.find(trimmed)
                if (match != null) {
                    val title = match.groupValues.last().trim()
                    if (title.length in 3..120) {
                        list.add(ExtractedTask(title = title))
                    }
                }
            }
        }
        return list.distinctBy { it.title }.take(10)
    }
}
