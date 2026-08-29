package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "claims",
    indices = [
        Index(value = ["noteId"]),
        Index(value = ["claimType"]),
        Index(value = ["status"])
    ]
)
data class ClaimEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val text: String,
    val claimType: String, // fact, decision, interpretation, question, task, deadline
    val confidence: Float,
    val status: String, // ai_suggested, user_approved, user_rejected, resolved, uncertain
    val createdAt: Long,
    val updatedAt: Long
)