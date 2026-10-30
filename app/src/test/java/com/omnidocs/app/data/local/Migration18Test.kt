package com.omnidocs.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test for Migration 18 -> 19 verifying version bounds and foreign key SQL contract.
 */
class Migration18Test {

    @Test
    fun migration18_19_spansVersions18To19() {
        assertEquals(18, MIGRATION_18_19.startVersion)
        assertEquals(19, MIGRATION_18_19.endVersion)
    }

    @Test
    fun migration18_19_definesForeignKeyCascades() {
        assertTrue(MIGRATION_18_19_CREATE_NEW_TABLE_SQL.contains("FOREIGN KEY(`sourceNoteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE"))
        assertTrue(MIGRATION_18_19_CREATE_NEW_TABLE_SQL.contains("FOREIGN KEY(`targetNoteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE"))
    }

    @Test
    fun migration18_19_cleansUpOrphanedLinksBeforeMigration() {
        assertTrue(MIGRATION_18_19_CLEANUP_ORPHANS_SQL.contains("DELETE FROM `note_links`"))
        assertTrue(MIGRATION_18_19_CLEANUP_ORPHANS_SQL.contains("`sourceNoteId` NOT IN (SELECT `id` FROM `notes`)"))
        assertTrue(MIGRATION_18_19_CLEANUP_ORPHANS_SQL.contains("`targetNoteId` NOT IN (SELECT `id` FROM `notes`)"))
    }
}
