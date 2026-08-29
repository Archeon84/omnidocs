package com.omnidocs.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 6 -> 7: Add evidence-first knowledge workspace tables.
 *
 * Creates 13 new tables for recordings, transcript segments, speakers,
 * claims, evidence links, action items, entities, entity mentions,
 * note links, embeddings, AI runs, note versions, and audit events.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Recordings
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `recordings` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `noteId` TEXT NOT NULL,
                `filename` TEXT NOT NULL,
                `storageKey` TEXT NOT NULL,
                `durationMs` INTEGER NOT NULL,
                `language` TEXT NOT NULL,
                `processingMode` TEXT NOT NULL,
                `processingStatus` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `deletedAt` INTEGER
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recordings_noteId` ON `recordings` (`noteId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recordings_createdAt` ON `recordings` (`createdAt`)")

        // Transcript segments
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `transcript_segments` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `recordingId` TEXT NOT NULL,
                `noteId` TEXT NOT NULL,
                `speakerId` TEXT,
                `startMs` INTEGER NOT NULL,
                `endMs` INTEGER NOT NULL,
                `rawText` TEXT NOT NULL,
                `correctedText` TEXT,
                `language` TEXT NOT NULL,
                `confidence` REAL NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transcript_segments_recordingId` ON `transcript_segments` (`recordingId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transcript_segments_noteId` ON `transcript_segments` (`noteId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transcript_segments_speakerId` ON `transcript_segments` (`speakerId`)")

        // Speakers
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `speakers` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `displayName` TEXT NOT NULL,
                `profileReference` TEXT,
                `createdAt` INTEGER NOT NULL
            )"""
        )

        // Claims
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `claims` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `noteId` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                `claimType` TEXT NOT NULL,
                `confidence` REAL NOT NULL,
                `status` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_claims_noteId` ON `claims` (`noteId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_claims_claimType` ON `claims` (`claimType`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_claims_status` ON `claims` (`status`)")

        // Evidence links
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `evidence_links` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `claimId` TEXT NOT NULL,
                `sourceType` TEXT NOT NULL,
                `sourceId` TEXT NOT NULL,
                `startOffset` INTEGER,
                `endOffset` INTEGER,
                `startMs` INTEGER,
                `endMs` INTEGER,
                `quoteHash` TEXT,
                `relevanceScore` REAL NOT NULL,
                `createdAt` INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_evidence_links_claimId` ON `evidence_links` (`claimId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_evidence_links_sourceType_sourceId` ON `evidence_links` (`sourceType`, `sourceId`)")

        // Action items
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `action_items` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `noteId` TEXT NOT NULL,
                `claimId` TEXT,
                `title` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `owner` TEXT,
                `dueAt` INTEGER,
                `status` TEXT NOT NULL,
                `priority` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `completedAt` INTEGER
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_action_items_noteId` ON `action_items` (`noteId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_action_items_status` ON `action_items` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_action_items_dueAt` ON `action_items` (`dueAt`)")

        // Entities
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `entities` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `entityType` TEXT NOT NULL,
                `canonicalName` TEXT NOT NULL,
                `aliasesJson` TEXT NOT NULL DEFAULT '[]',
                `metadataJson` TEXT NOT NULL DEFAULT '{}',
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_entities_entityType` ON `entities` (`entityType`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_entities_canonicalName` ON `entities` (`canonicalName`)")

        // Entity mentions
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `entity_mentions` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `entityId` TEXT NOT NULL,
                `noteId` TEXT,
                `transcriptSegmentId` TEXT,
                `startOffset` INTEGER NOT NULL,
                `endOffset` INTEGER NOT NULL,
                `confidence` REAL NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_entity_mentions_entityId` ON `entity_mentions` (`entityId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_entity_mentions_noteId` ON `entity_mentions` (`noteId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_entity_mentions_transcriptSegmentId` ON `entity_mentions` (`transcriptSegmentId`)")

        // Note links
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `note_links` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `sourceNoteId` TEXT NOT NULL,
                `targetNoteId` TEXT NOT NULL,
                `linkType` TEXT NOT NULL,
                `confidence` REAL NOT NULL,
                `createdBy` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_links_sourceNoteId` ON `note_links` (`sourceNoteId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_links_targetNoteId` ON `note_links` (`targetNoteId`)")

        // Embeddings
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `embeddings` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `sourceType` TEXT NOT NULL,
                `sourceId` TEXT NOT NULL,
                `chunkHash` TEXT NOT NULL,
                `modelName` TEXT NOT NULL,
                `embeddingVector` BLOB NOT NULL,
                `createdAt` INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_embeddings_sourceType_sourceId` ON `embeddings` (`sourceType`, `sourceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_embeddings_chunkHash` ON `embeddings` (`chunkHash`)")

        // AI runs
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `ai_runs` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `operation` TEXT NOT NULL,
                `provider` TEXT NOT NULL,
                `model` TEXT NOT NULL,
                `promptVersion` TEXT NOT NULL,
                `inputHash` TEXT NOT NULL,
                `outputHash` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `costEstimate` REAL NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `completedAt` INTEGER
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_runs_operation` ON `ai_runs` (`operation`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_runs_status` ON `ai_runs` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_runs_createdAt` ON `ai_runs` (`createdAt`)")

        // Note versions
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `note_versions` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `noteId` TEXT NOT NULL,
                `version` INTEGER NOT NULL,
                `content` TEXT NOT NULL,
                `plainText` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_versions_noteId_version` ON `note_versions` (`noteId`, `version`)")

        // Audit events
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `audit_events` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `entityType` TEXT NOT NULL,
                `entityId` TEXT NOT NULL,
                `action` TEXT NOT NULL,
                `userId` TEXT,
                `details` TEXT NOT NULL DEFAULT '{}',
                `createdAt` INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_events_entityType_entityId` ON `audit_events` (`entityType`, `entityId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_events_action` ON `audit_events` (`action`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_events_createdAt` ON `audit_events` (`createdAt`)")
    }
}
