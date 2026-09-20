package com.omnidocs.app.di

import android.content.Context
import com.omnidocs.app.data.local.*
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
        return NotesDatabase.getDatabase(context)
    }

    @Provides @Singleton fun provideNoteDao(db: NotesDatabase): NoteDao = db.noteDao()
    @Provides @Singleton fun provideRecordingDao(db: NotesDatabase): RecordingDao = db.recordingDao()
    @Provides @Singleton fun provideTranscriptSegmentDao(db: NotesDatabase): TranscriptSegmentDao = db.transcriptSegmentDao()
    @Provides @Singleton fun provideSpeakerDao(db: NotesDatabase): SpeakerDao = db.speakerDao()
    @Provides @Singleton fun provideClaimDao(db: NotesDatabase): ClaimDao = db.claimDao()
    @Provides @Singleton fun provideEvidenceLinkDao(db: NotesDatabase): EvidenceLinkDao = db.evidenceLinkDao()
    @Provides @Singleton fun provideActionItemDao(db: NotesDatabase): ActionItemDao = db.actionItemDao()
    @Provides @Singleton fun provideEntityDao(db: NotesDatabase): EntityDao = db.entityDao()
    @Provides @Singleton fun provideEntityMentionDao(db: NotesDatabase): EntityMentionDao = db.entityMentionDao()
    @Provides @Singleton fun provideNoteLinkDao(db: NotesDatabase): NoteLinkDao = db.noteLinkDao()
    @Provides @Singleton fun provideEmbeddingDao(db: NotesDatabase): EmbeddingDao = db.embeddingDao()
    @Provides @Singleton fun provideAiRunDao(db: NotesDatabase): AiRunDao = db.aiRunDao()
    @Provides @Singleton fun provideNoteVersionDao(db: NotesDatabase): NoteVersionDao = db.noteVersionDao()
    @Provides @Singleton fun provideAuditEventDao(db: NotesDatabase): AuditEventDao = db.auditEventDao()
    @Provides @Singleton fun provideSavedSearchDao(db: NotesDatabase): SavedSearchDao = db.savedSearchDao()
    @Provides @Singleton fun provideAgentJobDao(db: NotesDatabase): AgentJobDao = db.agentJobDao()
    @Provides @Singleton fun provideAgentEventDao(db: NotesDatabase): AgentEventDao = db.agentEventDao()
    @Provides @Singleton fun provideSourceDocumentDao(db: NotesDatabase): SourceDocumentDao = db.sourceDocumentDao()
    @Provides @Singleton fun provideContentBlockDao(db: NotesDatabase): ContentBlockDao = db.contentBlockDao()
    @Provides @Singleton fun provideAiArtifactDao(db: NotesDatabase): AiArtifactDao = db.aiArtifactDao()
    @Provides @Singleton fun provideFlashcardDao(db: NotesDatabase): FlashcardDao = db.flashcardDao()
}

