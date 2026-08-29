package com.omnidocs.app.templates

/**
 * Defines a note template with pre-filled structure and extraction hints.
 */
interface NoteTemplate {
    val id: String
    val name: String
    val description: String
    val icon: String // emoji or icon name

    /** Pre-filled HTML content for the note */
    val htmlContent: String

    /** Fields that the template expects to be filled in */
    val expectedFields: List<String>

    /** What the AI should extract from notes using this template */
    val extractionTypes: List<String>

    /** Suggested prompts for the AI */
    val suggestedPrompts: List<String>
}
