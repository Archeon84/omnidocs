package com.omnidocs.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 18 -> 19: Enforce SQLite Foreign Key cascading (`onDelete = CASCADE`)
 * on `note_links` table for referential integrity.
 *
 * When a note is deleted or purged from the `notes` table, all associated graph
 * edges in `note_links` (both incoming and outgoing) are automatically dropped by SQLite.
 */
internal const val MIGRATION_18_19_CLEANUP_ORPHANS_SQL =
    "DELETE FROM `note_links` WHERE `sourceNoteId` NOT IN (SELECT `id` FROM `notes`) OR `targetNoteId` NOT IN (SELECT `id` FROM `notes`)"

internal const val MIGRATION_18_19_CREATE_NEW_TABLE_SQL =
    """CREATE TABLE IF NOT EXISTS `note_links_new` (
        `id` TEXT NOT NULL PRIMARY KEY,
        `sourceNoteId` TEXT NOT NULL,
        `targetNoteId` TEXT NOT NULL,
        `linkType` TEXT NOT NULL,
        `confidence` REAL NOT NULL,
        `createdBy` TEXT NOT NULL,
        `createdAt` INTEGER NOT NULL,
        FOREIGN KEY(`sourceNoteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
        FOREIGN KEY(`targetNoteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
    )"""

val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(MIGRATION_18_19_CLEANUP_ORPHANS_SQL)
        db.execSQL(MIGRATION_18_19_CREATE_NEW_TABLE_SQL)
        db.execSQL(
            """
            INSERT INTO `note_links_new` (`id`, `sourceNoteId`, `targetNoteId`, `linkType`, `confidence`, `createdBy`, `createdAt`)
            SELECT `id`, `sourceNoteId`, `targetNoteId`, `linkType`, `confidence`, `createdBy`, `createdAt`
            FROM `note_links`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `note_links`")
        db.execSQL("ALTER TABLE `note_links_new` RENAME TO `note_links`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_links_sourceNoteId` ON `note_links` (`sourceNoteId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_links_targetNoteId` ON `note_links` (`targetNoteId`)")
    }
}
