package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "content_blocks",
    indices = [
        Index(value = ["sourceDocumentId"]),
        Index(value = ["noteId"]),
        Index(value = ["createdAt"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = SourceDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceDocumentId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ContentBlockEntity(
    @PrimaryKey val id: String,
    val sourceDocumentId: String? = null,
    val noteId: String? = null,
    val blockIndex: Int = 0,
    val blockType: String = "paragraph",
    val content: String,
    val pageNumber: Int? = null,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val boundingBoxJson: String? = null,
    val confidence: Float? = null,
    val createdAt: Long
)
