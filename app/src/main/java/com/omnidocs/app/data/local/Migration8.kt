package com.omnidocs.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE `saved_searches` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `query` TEXT NOT NULL,
                `filtersJson` TEXT NOT NULL DEFAULT '{}',
                `runOnOpen` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL,
                `lastUsedAt` INTEGER,
                PRIMARY KEY(`id`)
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_searches_lastUsedAt` ON `saved_searches` (`lastUsedAt`)")
    }
}
