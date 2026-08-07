package com.omnidocs.app.domain.model

data class Note(
    val id: String = "",
    val title: String = "",
    val content: String = "", // HTML content
    val plainText: String = "", // For search
    val isPinned: Boolean = false,
    val language: String = "en", // en or ms
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val imageUrl: String? = null,
    val attachments: String = "[]", // JSON array of attachment paths
    val isDeleted: Boolean = false,
    val tags: String = "[]",
    val relatedNotes: String = "[]"
)
