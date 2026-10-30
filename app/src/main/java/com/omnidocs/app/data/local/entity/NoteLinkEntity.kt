package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "note_links",
    indices = [
        Index(value = ["sourceNoteId"]),
        Index(value = ["targetNoteId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceNoteId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["targetNoteId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class NoteLinkEntity(
    @PrimaryKey val id: String,
    val sourceNoteId: String,
    val targetNoteId: String,
    val linkType: String, // related, references, supports, contradicts, builds_on
    val confidence: Float,
    val createdBy: String, // user, rule, ai
    val createdAt: Long
)
