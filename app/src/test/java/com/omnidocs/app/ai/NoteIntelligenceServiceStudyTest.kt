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
        assertEquals("Database Security Architecture", definitionCard.sourceTitle)
        assertFalse(definitionCard.sourceSnippet.orEmpty().contains("[Truncated"))
    }

    @Test
    fun testGenerateStudyDeck_largeContentDoesNotLeakTruncationArtifact() = runBlocking {
        `when`(modelDownloadManager.getDownloadedModels()).thenReturn(emptyList())

        val longContent = buildString {
            appendLine("Core Concept: The foundation of modern computing architecture.")
            for (i in 1..200) {
                appendLine("Section $i: Detailed technical notes explaining architecture principles in deep depth.")
            }
        }
        assertTrue(longContent.length > 4000)

        val deck = service.generateStudyDeck(
            noteId = "note_long_1",
            noteTitle = "Long Architecture Document",
            noteContent = longContent,
            language = "en"
        )

        assertNotNull(deck)
        assertTrue(deck.cards.isNotEmpty())
        for (card in deck.cards) {
            assertEquals("Long Architecture Document", card.sourceTitle)
            assertFalse("Card snippet must not contain truncation warning", card.sourceSnippet.orEmpty().contains("[Truncated"))
        }
    }
}
