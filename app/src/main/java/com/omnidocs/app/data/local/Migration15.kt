package com.omnidocs.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 15 -> 16: Add sourceTitle column to flashcards table for note citation.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val cursor = db.query("PRAGMA table_info(`flashcards`)")
        var hasSourceTitle = false
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (nameIndex != -1 && cursor.getString(nameIndex) == "sourceTitle") {
                hasSourceTitle = true
                break
            }
        }
        cursor.close()

        if (!hasSourceTitle) {
            db.execSQL("ALTER TABLE `flashcards` ADD COLUMN `sourceTitle` TEXT")
        }
    }
}
