package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "action_items",
    indices = [
        Index(value = ["noteId"]),
        Index(value = ["status"]),
        Index(value = ["dueAt"])
    ]
)
data class ActionItemEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val claimId: String? = null,
    val title: String,
    val description: String,
    val owner: String? = null,
    val dueAt: Long? = null,
    val status: String, // pending, in_progress, completed, cancelled
    val priority: String, // low, medium, high, urgent
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null
)