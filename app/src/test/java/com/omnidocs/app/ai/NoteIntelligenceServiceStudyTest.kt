package com.omnidocs.app.ai

import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.study.StudyCardType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class NoteIntelligenceServiceStudyTest {

    private lateinit var llamaCppService: LlamaCppService
    private lateinit var modelDownloadManager: ModelDownloadManager
    private lateinit var modelPreferences: ModelPreferences
    private lateinit var repository: NotesRepository
    private lateinit var service: NoteIntelligenceService

    @Before
    fun setUp() {
        llamaCppService = mock(LlamaCppService::class.java)
        modelDownloadManager = mock(ModelDownloadManager::class.java)
        modelPreferences = mock(ModelPreferences::class.java)
        repository = mock(NotesRepository::class.java)

        service = NoteIntelligenceService(
            llamaCppService = llamaCppService,
            modelDownloadManager = modelDownloadManager,
            modelPreferences = modelPreferences,
            repository = repository
        )
    }

    @Test
    fun testGenerateStudyDeck_fallbackWhenNoModelDownloaded() = runBlocking {
        `when`(modelDownloadManager.getDownloadedModels()).thenReturn(emptyList())

        val noteContent = """
            Android KeyStore: Hardware-backed secure key storage system.
            SQLCipher: Transparent 256-bit AES encryption for SQLite database files.

            OmniDocs uses SQLCipher and Android KeyStore to ensure zero plaintext data leaks.
        """.trimIndent()

        val deck = service.generateStudyDeck(
            noteId = "note_sec_101",
            noteTitle = "Database Security Architecture",
            noteContent = noteContent,
            language = "en"
        )

        assertNotNull(deck)
        assertEquals("Database Security Architecture", deck.title)
        assertEquals("note_sec_101", deck.noteId)
        assertTrue(deck.cards.isNotEmpty())

        val definitionCard = deck.cards.find { it.type == StudyCardType.DEFINITION }
        assertNotNull(definitionCard)
        assertTrue(definitionCard!!.prompt.contains("Android KeyStore") || definitionCard.prompt.contains("SQLCipher"))
    }
}
