package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recordings",
    indices = [
        Index(value = ["noteId"]),
        Index(value = ["createdAt"])
    ]
)
data class RecordingEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val filename: String,
    val storageKey: String,
    val durationMs: Long,
    val language: String,
    val processingMode: String, // local, self_hosted, third_party_cloud
    val processingStatus: String, // pending, processing, completed, failed
    val createdAt: Long,
    val deletedAt: Long? = null
)