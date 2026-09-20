package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["isPinned", "updatedAt"]),
        Index(value = ["isSynced"]),
        Index(value = ["isDeleted", "updatedAt"]),
        Index(value = ["isDeleted", "isPinned", "updatedAt"])
    ]
)
data class NoteEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val content: String, // HTML content
    val plainText: String, // For search
    val isPinned: Boolean,
    val language: String,
    val createdAt: Long,
    val updatedAt: Long,
    val imageUrl: String?,
    val attachments: String = "[]", // JSON array of attachment paths
    val isSynced: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val tags: String = "[]",
    val relatedNotes: String = "[]"
)
