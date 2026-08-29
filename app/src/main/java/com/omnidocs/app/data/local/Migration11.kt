package com.omnidocs.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 11 -> 12: Add AI Artifacts and Approval System table.
 *
 * Creates ai_artifacts table to track generated summaries, proposed titles,
 * tags, claims, and action items with verification and human approval states.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `ai_artifacts` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `noteId` TEXT NOT NULL,
                `artifactType` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `sourceRevision` INTEGER NOT NULL DEFAULT 1,
                `promptVersion` TEXT NOT NULL DEFAULT '1.0',
                `modelId` TEXT NOT NULL DEFAULT 'on_device_llm',
                `verificationStatus` TEXT NOT NULL DEFAULT 'UNVERIFIED',
                `approvalState` TEXT NOT NULL DEFAULT 'PENDING',
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_artifacts_noteId` ON `ai_artifacts` (`noteId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_artifacts_artifactType` ON `ai_artifacts` (`artifactType`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_artifacts_approvalState` ON `ai_artifacts` (`approvalState`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_artifacts_createdAt` ON `ai_artifacts` (`createdAt`)")
    }
}
