package com.omnidocs.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 13 -> 14: retrieval hot-path indices + FTS soft-delete hygiene.
 *
 * - `index_embeddings_sourceId` / `index_embeddings_modelName`: the
 *   single-column delete/lookup predicates (`deleteEmbeddingsBySourceIds`,
 *   `deleteEmbeddingsByModel`) previously matched no index prefix.
 * - `index_notes_isDeleted_isPinned_updatedAt`: covers the exact
 *   `WHERE isDeleted = 0 ORDER BY isPinned DESC, updatedAt DESC` list query.
 * - `notes_fts_au` no longer re-inserts soft-deleted rows (dead index bloat
 *   previously accumulated behind the `isDeleted` filter); pre-existing dead
 *   rows are purged. Same guard added to `notes_fts_ai`.
 *
 * Index names must match Room's expected `index_<table>_<cols>` convention
 * exactly, or Room will reject the schema at open time.
 */
internal const val MIGRATION_13_14_EMBEDDINGS_SOURCEID_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS `index_embeddings_sourceId` " +
        "ON `embeddings` (`sourceId`)"

internal const val MIGRATION_13_14_EMBEDDINGS_MODELNAME_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS `index_embeddings_modelName` " +
        "ON `embeddings` (`modelName`)"

internal const val MIGRATION_13_14_NOTES_LIST_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS `index_notes_isDeleted_isPinned_updatedAt` " +
        "ON `notes` (`isDeleted`, `isPinned`, `updatedAt`)"

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(MIGRATION_13_14_EMBEDDINGS_SOURCEID_INDEX_SQL)
        db.execSQL(MIGRATION_13_14_EMBEDDINGS_MODELNAME_INDEX_SQL)
        db.execSQL(MIGRATION_13_14_NOTES_LIST_INDEX_SQL)

        // Only databases that migrated through v3 -> v4 carry the manual
        // notes_fts_* triggers (fresh installs use Room-generated content-sync
        // triggers instead). Replacing triggers blindly would double-fire
        // sync and duplicate FTS rows, so gate on sqlite_master.
        val cursor = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name = 'notes_fts_au'"
        )
        val hasManualTriggers = cursor.count > 0
        cursor.close()
        if (hasManualTriggers) {
            db.execSQL("DROP TRIGGER IF EXISTS `notes_fts_au`")
            db.execSQL(
                """CREATE TRIGGER `notes_fts_au` AFTER UPDATE ON `notes` BEGIN
                    DELETE FROM `notes_fts` WHERE `docid` = old.`rowid`;
                    INSERT INTO `notes_fts`(`docid`, `title`, `plainText`)
                    SELECT new.`rowid`, new.`title`, new.`plainText` WHERE new.`isDeleted` = 0;
                END"""
            )
            db.execSQL("DROP TRIGGER IF EXISTS `notes_fts_ai`")
            db.execSQL(
                """CREATE TRIGGER `notes_fts_ai` AFTER INSERT ON `notes`
                    WHEN (new.`isDeleted` = 0) BEGIN
                    INSERT INTO `notes_fts`(`docid`, `title`, `plainText`)
                    VALUES (new.`rowid`, new.`title`, new.`plainText`);
                END"""
            )
        }
        // Purge dead FTS rows accumulated by unconditional sync (safe on all
        // installs; sync re-adds a row only if the note is updated again).
        db.execSQL(
            "DELETE FROM `notes_fts` WHERE `docid` IN " +
                "(SELECT `rowid` FROM `notes` WHERE `isDeleted` = 1)"
        )
        // Drop dead per-block vectors: nothing reads sourceType='content_block'
        // (retrieval cites ContentBlockEntity rows lexically), and the writer
        // no longer produces them.
        db.execSQL("DELETE FROM `embeddings` WHERE `sourceType` = 'content_block'")
    }
}
