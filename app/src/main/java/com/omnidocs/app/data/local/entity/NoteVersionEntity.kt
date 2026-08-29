package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "note_versions",
    indices = [
        Index(value = ["noteId", "version"])
    ]
)
data class NoteVersionEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val version: Int,
    val content: String,
    val plainText: String,
    val createdAt: Long
)