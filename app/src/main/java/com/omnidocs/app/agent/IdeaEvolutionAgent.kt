package com.omnidocs.app.agent

import com.omnidocs.app.knowledge.IdeaEvolution
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent responsible for tracing how concepts, topics, and projects
 * evolve across notes over time, extracting decision chains and supporting evidence.
 */
@Singleton
class IdeaEvolutionAgent @Inject constructor(
    private val ideaEvolution: IdeaEvolution
) : Agent {

    override val id: String = "agent_idea_evolution"

    override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Idea evolution tracking cancelled by user")
        }

        val concept = input.payload["concept"] ?: ""
        if (concept.isBlank()) {
            return AgentResult.PermanentFailure("Missing 'concept' parameter for idea evolution")
        }

        val timeline = ideaEvolution.traceEvolution(concept)

        val relatedNotesArray = JSONArray()
        for (note in timeline.relatedNotes) {
            val obj = JSONObject()
            obj.put("id", note.id)
            obj.put("title", note.title)
            obj.put("createdAt", note.createdAt)
            relatedNotesArray.put(obj)
        }

        val decisionsArray = JSONArray()
        for (decision in timeline.decisions) {
            val obj = JSONObject()
            obj.put("id", decision.id)
            obj.put("text", decision.text)
            obj.put("noteId", decision.noteId)
            decisionsArray.put(obj)
        }

        return AgentResult.Success(
            payload = mapOf(
                "concept" to timeline.concept,
                "firstMentionNoteId" to (timeline.firstMention?.id ?: ""),
                "firstMentionTitle" to (timeline.firstMention?.title ?: ""),
                "relatedNotesCount" to timeline.relatedNotes.size,
                "relatedNotesJson" to relatedNotesArray.toString(),
                "decisionsCount" to timeline.decisions.size,
                "decisionsJson" to decisionsArray.toString(),
                "status" to timeline.currentStatus
            )
        )
    }
}
