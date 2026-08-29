package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "entity_mentions",
    indices = [
        Index(value = ["entityId"]),
        Index(value = ["noteId"]),
        Index(value = ["transcriptSegmentId"])
    ]
)
data class EntityMentionEntity(
    @PrimaryKey val id: String,
    val entityId: String,
    val noteId: String? = null,
    val transcriptSegmentId: String? = null,
    val startOffset: Int,
    val endOffset: Int,
    val confidence: Float
)