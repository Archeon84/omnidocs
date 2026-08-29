package com.omnidocs.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 10 -> 11: Add Ingestion and Provenance data model tables.
 *
 * Creates source_documents and content_blocks tables to track original files,
 * checksums, chunk offsets, pages, timestamps, and OCR/transcript provenance.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Source Documents
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `source_documents` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `uri` TEXT NOT NULL,
                `mimeType` TEXT NOT NULL,
                `fileName` TEXT NOT NULL,
                `checksum` TEXT NOT NULL,
                `fileSizeBytes` INTEGER NOT NULL DEFAULT 0,
                `pageCount` INTEGER,
                `language` TEXT,
                `isEncrypted` INTEGER NOT NULL DEFAULT 0,
                `importedAt` INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_source_documents_checksum` ON `source_documents` (`checksum`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_source_documents_mimeType` ON `source_documents` (`mimeType`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_source_documents_importedAt` ON `source_documents` (`importedAt`)")

        // Content Blocks
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `content_blocks` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `sourceDocumentId` TEXT,
                `noteId` TEXT,
                `blockIndex` INTEGER NOT NULL DEFAULT 0,
                `blockType` TEXT NOT NULL DEFAULT 'paragraph',
                `content` TEXT NOT NULL,
                `pageNumber` INTEGER,
                `startOffset` INTEGER,
                `endOffset` INTEGER,
                `startMs` INTEGER,
                `endMs` INTEGER,
                `boundingBoxJson` TEXT,
                `confidence` REAL,
                `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`sourceDocumentId`) REFERENCES `source_documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_content_blocks_sourceDocumentId` ON `content_blocks` (`sourceDocumentId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_content_blocks_noteId` ON `content_blocks` (`noteId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_content_blocks_createdAt` ON `content_blocks` (`createdAt`)")
    }
}
