package com.omnidocs.app.data.local

import org.junit.Assert.*
import org.junit.Test

/**
 * Guards the Migration 12 -> 13 hot-path indices.
 *
 * Room's MigrationTestHelper is instrumented-only, so this JVM test verifies
 * the migration contract instead: version bounds plus index SQL that matches
 * Room's expected `index_<table>_<cols>` schema names exactly (a mismatch
 * would make Room reject the database at open time).
 */
class Migration12Test {

    @Test
    fun migration12_13_spansVersions12To13() {
        assertEquals(12, MIGRATION_12_13.startVersion)
        assertEquals(13, MIGRATION_12_13.endVersion)
    }

    @Test
    fun migration12_13_createsNotesDeletedIndex() {
        assertTrue(MIGRATION_12_13_NOTES_INDEX_SQL.contains("index_notes_isDeleted_updatedAt"))
        assertTrue(MIGRATION_12_13_NOTES_INDEX_SQL.contains("`notes`"))
        assertTrue(MIGRATION_12_13_NOTES_INDEX_SQL.contains("`isDeleted`"))
        assertTrue(MIGRATION_12_13_NOTES_INDEX_SQL.contains("`updatedAt`"))
    }

    @Test
    fun migration12_13_createsEmbeddingsModelIndex() {
        assertTrue(MIGRATION_12_13_EMBEDDINGS_INDEX_SQL.contains("index_embeddings_sourceType_modelName"))
        assertTrue(MIGRATION_12_13_EMBEDDINGS_INDEX_SQL.contains("`embeddings`"))
        assertTrue(MIGRATION_12_13_EMBEDDINGS_INDEX_SQL.contains("`sourceType`"))
        assertTrue(MIGRATION_12_13_EMBEDDINGS_INDEX_SQL.contains("`modelName`"))
    }
}
