package com.omnidocs.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val MIGRATION_19_20_ADD_ATTACHMENT_NAME_SQL =
    "ALTER TABLE `chat_messages` ADD COLUMN `attachmentName` TEXT DEFAULT NULL"
internal const val MIGRATION_19_20_ADD_ATTACHMENT_TYPE_SQL =
    "ALTER TABLE `chat_messages` ADD COLUMN `attachmentType` TEXT DEFAULT NULL"
internal const val MIGRATION_19_20_ADD_ATTACHMENT_URI_SQL =
    "ALTER TABLE `chat_messages` ADD COLUMN `attachmentUri` TEXT DEFAULT NULL"
internal const val MIGRATION_19_20_ADD_ATTACHMENT_EXTRACTED_TEXT_SQL =
    "ALTER TABLE `chat_messages` ADD COLUMN `attachmentExtractedText` TEXT DEFAULT NULL"

/**
 * Migration 19 -> 20: Add attachment metadata and extracted text columns to `chat_messages` table
 * to support uploading documents, images, and sound/audio files in offline local LLM chat.
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(MIGRATION_19_20_ADD_ATTACHMENT_NAME_SQL)
        db.execSQL(MIGRATION_19_20_ADD_ATTACHMENT_TYPE_SQL)
        db.execSQL(MIGRATION_19_20_ADD_ATTACHMENT_URI_SQL)
        db.execSQL(MIGRATION_19_20_ADD_ATTACHMENT_EXTRACTED_TEXT_SQL)
    }
}
