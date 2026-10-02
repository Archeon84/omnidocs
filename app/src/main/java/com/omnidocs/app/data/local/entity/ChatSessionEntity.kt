package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_sessions",
    indices = [
        Index(value = ["updatedAt"]),
        Index(value = ["personaId"])
    ]
)
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val personaId: String,
    val systemPrompt: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelId: String,
    val temperature: Float,
    val topP: Float,
    val maxTokens: Int,
    val messageCount: Int
)
