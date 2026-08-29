package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcript_segments",
    indices = [
        Index(value = ["recordingId"]),
        Index(value = ["noteId"]),
        Index(value = ["speakerId"])
    ]
)
data class TranscriptSegmentEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val noteId: String,
    val speakerId: String? = null,
    val startMs: Long,
    val endMs: Long,
    val rawText: String,
    val correctedText: String? = null,
    val language: String,
    val confidence: Float,
    val createdAt: Long,
    val updatedAt: Long
)