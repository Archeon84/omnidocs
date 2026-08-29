package com.omnidocs.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 9 -> 10: Add Agent Runtime Foundation tables.
 *
 * Creates agent_jobs and agent_events tables for durable job state
 * and append-only audit event logging.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `agent_jobs` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `jobType` TEXT NOT NULL,
                `inputRefsJson` TEXT NOT NULL DEFAULT '{}',
                `status` TEXT NOT NULL,
                `progress` INTEGER NOT NULL DEFAULT 0,
                `isLocalExecution` INTEGER NOT NULL DEFAULT 1,
                `modelId` TEXT,
                `createdAt` INTEGER NOT NULL,
                `startedAt` INTEGER,
                `completedAt` INTEGER,
                `retryCount` INTEGER NOT NULL DEFAULT 0,
                `errorCode` TEXT,
                `errorMessage` TEXT
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_jobs_status` ON `agent_jobs` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_jobs_jobType` ON `agent_jobs` (`jobType`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_jobs_createdAt` ON `agent_jobs` (`createdAt`)")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `agent_events` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `jobId` TEXT NOT NULL,
                `agentId` TEXT NOT NULL,
                `eventType` TEXT NOT NULL,
                `safeMetadata` TEXT NOT NULL DEFAULT '{}',
                `durationMs` INTEGER,
                `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`jobId`) REFERENCES `agent_jobs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_events_jobId` ON `agent_events` (`jobId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_events_createdAt` ON `agent_events` (`createdAt`)")
    }
}
