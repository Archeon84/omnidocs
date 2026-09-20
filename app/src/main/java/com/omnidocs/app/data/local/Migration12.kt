package com.omnidocs.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 12 -> 13: Add hot-path indices.
 *
 * - `index_notes_isDeleted_updatedAt`: every list/search query filters
 *   `WHERE isDeleted = 0 ORDER BY updatedAt DESC`; without it each query
 *   is a full table scan.
 * - `index_embeddings_sourceType_modelName`: the exact predicate of the
 *   per-search embedding lookup (`WHERE sourceType AND modelName`).
 *
 * Index names must match Room's expected `index_<table>_<cols>` convention
 * exactly, or Room will reject the schema at open time.
 */
internal const val MIGRATION_12_13_NOTES_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS `index_notes_isDeleted_updatedAt` " +
        "ON `notes` (`isDeleted`, `updatedAt`)"

internal const val MIGRATION_12_13_EMBEDDINGS_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS `index_embeddings_sourceType_modelName` " +
        "ON `embeddings` (`sourceType`, `modelName`)"

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(MIGRATION_12_13_NOTES_INDEX_SQL)
        db.execSQL(MIGRATION_12_13_EMBEDDINGS_INDEX_SQL)
    }
}
