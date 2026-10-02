package com.omnidocs.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 17 -> 18: Add chat_sessions and chat_messages tables to migrate
 * offline local chat storage into SQLCipher-encrypted Room database.
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_sessions` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `title` TEXT NOT NULL,
                `personaId` TEXT NOT NULL,
                `systemPrompt` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `modelId` TEXT NOT NULL,
                `temperature` REAL NOT NULL,
                `topP` REAL NOT NULL,
                `maxTokens` INTEGER NOT NULL,
                `messageCount` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_sessions_updatedAt` ON `chat_sessions` (`updatedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_sessions_personaId` ON `chat_sessions` (`personaId`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_messages` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `sessionId` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `durationMs` INTEGER NOT NULL,
                `tokenCount` INTEGER NOT NULL,
                `tokPerSec` REAL NOT NULL,
                `modelName` TEXT NOT NULL,
                `isError` INTEGER NOT NULL,
                FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_sessionId` ON `chat_messages` (`sessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_timestamp` ON `chat_messages` (`timestamp`)")
    }
}
