package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4

/**
 * FTS4 virtual table for full-text search on notes.
 * Room auto-generates the CREATE VIRTUAL TABLE statement and keeps
 * the index in sync with [NoteEntity] via contentEntity linkage.
 * The manual sync triggers in MIGRATION_3_4 are still needed for
 * existing databases migrating from v3 → v4.
 */
@Fts4(contentEntity = NoteEntity::class)
@Entity(tableName = "notes_fts")
data class NoteFtsEntity(
    val title: String,
    val plainText: String
)
