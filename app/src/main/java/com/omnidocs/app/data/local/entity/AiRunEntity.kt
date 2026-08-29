package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_runs",
    indices = [
        Index(value = ["operation"]),
        Index(value = ["status"]),
        Index(value = ["createdAt"])
    ]
)
data class AiRunEntity(
    @PrimaryKey val id: String,
    val operation: String, // summarize, extract_claims, extract_tasks, etc.
    val provider: String, // llama_cpp, gemini, etc.
    val model: String,
    val promptVersion: String,
    val inputHash: String,
    val outputHash: String,
    val status: String, // running, completed, failed
    val costEstimate: Double,
    val createdAt: Long,
    val completedAt: Long? = null
)