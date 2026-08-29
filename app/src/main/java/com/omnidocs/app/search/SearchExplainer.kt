package com.omnidocs.app.search

import com.omnidocs.app.data.local.entity.NoteEntity

/**
 * Generates human-readable explanations for why a search result matched.
 */
object SearchExplainer {

    data class MatchExplanation(
        val noteId: String,
        val reasons: List<String>
    )

    /**
     * Explain why a note matched a search query.
     */
    fun explain(query: String, note: NoteEntity): MatchExplanation {
        val reasons = mutableListOf<String>()
        val queryLower = query.lowercase()
        val words = queryLower.split(Regex("\\s+")).filter { it.length > 2 }

        // Check title match
        if (note.title.lowercase().contains(queryLower)) {
            reasons.add("Matched exact phrase in title: \"$query\"")
        }

        // Check word-level matches in content
        for (word in words) {
            if (note.plainText.lowercase().contains(word)) {
                reasons.add("Contains word: \"$word\"")
            }
        }

        // Check if it's a recent note
        val daysSinceUpdate = (System.currentTimeMillis() - note.updatedAt) / (1000 * 60 * 60 * 24)
        if (daysSinceUpdate < 7) {
            reasons.add("Recent note (${daysSinceUpdate.toInt()} days ago)")
        }

        // Check if it has tags
        if (note.tags != "[]" && note.tags.isNotBlank()) {
            reasons.add("Has tags")
        }

        // Check if it's pinned
        if (note.isPinned) {
            reasons.add("Pinned note")
        }

        // Check if it has a recording
        if (note.content.contains("<audio")) {
            reasons.add("Contains recording")
        }

        // Check if it has tasks
        if (note.content.contains("checkbox") || note.content.contains("input type=\"checkbox\"")) {
            reasons.add("Contains action items")
        }

        // Fallback if no specific reasons found
        if (reasons.isEmpty()) {
            reasons.add("Related by content similarity")
        }

        return MatchExplanation(note.id, reasons)
    }
}
