package com.omnidocs.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test for Migration 19 -> 20 verifying version bounds and attachment column SQL contracts.
 */
class Migration20Test {

    @Test
    fun migration19_20_spansVersions19To20() {
        assertEquals(19, MIGRATION_19_20.startVersion)
        assertEquals(20, MIGRATION_19_20.endVersion)
    }

    @Test
    fun migration19_20_definesAttachmentColumns() {
        assertTrue(MIGRATION_19_20_ADD_ATTACHMENT_NAME_SQL.contains("`chat_messages`"))
        assertTrue(MIGRATION_19_20_ADD_ATTACHMENT_NAME_SQL.contains("`attachmentName`"))

        assertTrue(MIGRATION_19_20_ADD_ATTACHMENT_TYPE_SQL.contains("`chat_messages`"))
        assertTrue(MIGRATION_19_20_ADD_ATTACHMENT_TYPE_SQL.contains("`attachmentType`"))

        assertTrue(MIGRATION_19_20_ADD_ATTACHMENT_URI_SQL.contains("`chat_messages`"))
        assertTrue(MIGRATION_19_20_ADD_ATTACHMENT_URI_SQL.contains("`attachmentUri`"))

        assertTrue(MIGRATION_19_20_ADD_ATTACHMENT_EXTRACTED_TEXT_SQL.contains("`chat_messages`"))
        assertTrue(MIGRATION_19_20_ADD_ATTACHMENT_EXTRACTED_TEXT_SQL.contains("`attachmentExtractedText`"))
    }
}
