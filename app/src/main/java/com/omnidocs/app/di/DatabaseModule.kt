package com.omnidocs.app.di

import android.content.Context
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.NotesDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NotesDatabase {
        // Use NotesDatabase.getDatabase() which applies SQLCipher encryption
        // and proper migration handling — Room.databaseBuilder() bypasses both.
        return NotesDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideNoteDao(database: NotesDatabase): NoteDao {
        return database.noteDao()
    }
}

