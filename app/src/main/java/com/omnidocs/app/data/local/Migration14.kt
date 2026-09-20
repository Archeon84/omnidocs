package com.omnidocs.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 14 -> 15: Add flashcards table for persistent SM-2 spaced repetition study.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `flashcards` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `noteId` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `prompt` TEXT NOT NULL,
                `answer` TEXT NOT NULL,
                `optionsJson` TEXT NOT NULL,
                `correctOptionIndex` INTEGER,
                `explanation` TEXT,
                `sourceSnippet` TEXT,
                `sourceTitle` TEXT,
                `isUncertain` INTEGER NOT NULL DEFAULT 0,
                `tagsJson` TEXT NOT NULL DEFAULT '[]',
                `repetitionCount` INTEGER NOT NULL DEFAULT 0,
                `intervalDays` INTEGER NOT NULL DEFAULT 0,
                `easinessFactor` REAL NOT NULL DEFAULT 2.5,
                `nextReviewDateMs` INTEGER NOT NULL,
                `lastReviewedAtMs` INTEGER,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_flashcards_noteId` ON `flashcards` (`noteId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_flashcards_nextReviewDateMs` ON `flashcards` (`nextReviewDateMs`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_flashcards_noteId_nextReviewDateMs` ON `flashcards` (`noteId`, `nextReviewDateMs`)")
    }
}
