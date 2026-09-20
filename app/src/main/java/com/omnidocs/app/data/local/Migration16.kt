package com.omnidocs.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 16 -> 17: Add chunkText, sectionHeader, startOffset, endOffset, and chunkIndex
 * to embeddings table to persist chunk metadata and avoid re-chunking on retrieval.
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val existingColumns = mutableSetOf<String>()
        val cursor = db.query("PRAGMA table_info(embeddings)")
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (nameIndex != -1) {
                existingColumns.add(cursor.getString(nameIndex))
            }
        }
        cursor.close()

        if ("chunkText" !in existingColumns) {
            db.execSQL("ALTER TABLE embeddings ADD COLUMN chunkText TEXT NOT NULL DEFAULT ''")
        }
        if ("sectionHeader" !in existingColumns) {
            db.execSQL("ALTER TABLE embeddings ADD COLUMN sectionHeader TEXT DEFAULT NULL")
        }
        if ("startOffset" !in existingColumns) {
            db.execSQL("ALTER TABLE embeddings ADD COLUMN startOffset INTEGER NOT NULL DEFAULT 0")
        }
        if ("endOffset" !in existingColumns) {
            db.execSQL("ALTER TABLE embeddings ADD COLUMN endOffset INTEGER NOT NULL DEFAULT 0")
        }
        if ("chunkIndex" !in existingColumns) {
            db.execSQL("ALTER TABLE embeddings ADD COLUMN chunkIndex INTEGER NOT NULL DEFAULT 0")
        }
    }
}
