package com.omnidocs.app.agent

import com.omnidocs.app.ai.AutoTagger
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent responsible for organizing extracted and normalized documents.
 * Proposes title refinements, contextual tags, and detects potential action items.
 */
@Singleton
class OrganizationAgent @Inject constructor(
    private val autoTagger: AutoTagger
) : Agent {

    override val id: String = "agent_organization"

    override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Organization cancelled by user")
        }

        val rawTitle = input.payload["title"] ?: ""
        val normalizedText = input.payload["normalizedText"] ?: ""

        // 1. Refine Title
        val suggestedTitle = when {
            rawTitle.isNotBlank() && !rawTitle.startsWith("document", ignoreCase = true) -> rawTitle
            else -> deriveTitleFromText(normalizedText, rawTitle)
        }

        // 2. Propose Tags
        val tagsJson = try {
            if (normalizedText.length >= 50) {
                autoTagger.generateTags(suggestedTitle, normalizedText)
            } else {
                extractHeuristicTags(normalizedText)
            }
        } catch (e: Exception) {
            extractHeuristicTags(normalizedText)
        }

        // 3. Detect Action Items / Tasks
        val tasks = detectActionItems(normalizedText)
        val tasksArray = JSONArray()
        for (task in tasks) {
            val obj = JSONObject()
            obj.put("title", task)
            tasksArray.put(obj)
        }

        return AgentResult.Success(
            payload = mapOf(
                "suggestedTitle" to suggestedTitle,
                "suggestedTags" to tagsJson,
                "suggestedTasks" to tasksArray.toString(),
                "taskCount" to tasks.size
            )
        )
    }

    private fun deriveTitleFromText(text: String, fallback: String): String {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return fallback.ifBlank { "Untitled Note" }
        val clean = firstLine.removePrefix("#").trim()
        return if (clean.length > 60) clean.take(57) + "..." else clean
    }

    private fun extractHeuristicTags(text: String): String {
        val words = text.lowercase().split(Regex("[^a-zA-Z0-9_]")).filter { it.length > 4 }
        val freq = mutableMapOf<String, Int>()
        for (w in words) {
            freq[w] = (freq[w] ?: 0) + 1
        }
        val top = freq.entries.sortedByDescending { it.value }.take(3).map { it.key }
        return JSONArray(top).toString()
    }

    private fun detectActionItems(text: String): List<String> {
        val taskPatterns = listOf(
            Regex("(?i)\\b(todo|action item|task|due by|need to|must|perlu|sila):?\\s*(.+)"),
            Regex("(?i)^\\[ \\]\\s*(.+)")
        )
        val result = mutableListOf<String>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            for (pattern in taskPatterns) {
                val match = pattern.find(trimmed)
                if (match != null) {
                    val taskText = match.groupValues.last().trim()
                    if (taskText.isNotBlank() && taskText.length < 150) {
                        result.add(taskText)
                    }
                }
            }
        }
        return result.distinct().take(10)
    }
}
