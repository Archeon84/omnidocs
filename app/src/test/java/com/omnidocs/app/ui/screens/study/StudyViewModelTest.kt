package com.omnidocs.app.ui.screens.study

import androidx.lifecycle.SavedStateHandle
import com.omnidocs.app.ai.NoteIntelligenceService
import com.omnidocs.app.domain.model.Note
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.study.CardReviewState
import com.omnidocs.app.study.Flashcard
import com.omnidocs.app.study.SpacedRepetitionScheduler
import com.omnidocs.app.study.StudyCardType
import com.omnidocs.app.study.StudyDeck
import com.omnidocs.app.study.StudyExportService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class StudyViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var intelligenceService: NoteIntelligenceService
    private lateinit var scheduler: SpacedRepetitionScheduler
    private lateinit var exportService: StudyExportService
    private lateinit var repository: NotesRepository
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var viewModel: StudyViewModel

    private val dummyDeck = StudyDeck(
        title = "Kotlin Coroutines",
        noteId = "note_123",
        cards = listOf(
            Flashcard(
                id = "card_1",
                noteId = "note_123",
                type = StudyCardType.QA,
                prompt = "What is a CoroutineScope?",
                answer = "A scope that defines the lifecycle of coroutines."
            ),
            Flashcard(
                id = "card_2",
                noteId = "note_123",
                type = StudyCardType.QA,
                prompt = "What is Dispatchers.IO used for?",
                answer = "Offloading blocking IO operations like disk and network."
            )
        )
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        intelligenceService = mock(NoteIntelligenceService::class.java)
        scheduler = SpacedRepetitionScheduler()
        exportService = mock(StudyExportService::class.java)
        repository = mock(NotesRepository::class.java)
        savedStateHandle = SavedStateHandle(mapOf("noteId" to "note_123"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testStudySessionFlow_flipsCardsAndCompletesReviewSummary() = runTest {
        val dummyNote = Note(
            id = "note_123",
            title = "Kotlin Coroutines",
            content = "Coroutines content",
            plainText = "Coroutines plain text",
            isPinned = false,
            language = "en",
            createdAt = 1000L,
            updatedAt = 2000L,
            imageUrl = null
        )

        `when`(repository.getNoteById("note_123")).thenReturn(dummyNote)
        `when`(intelligenceService.generateStudyDeck("note_123", "Kotlin Coroutines", "Coroutines plain text", "en"))
            .thenReturn(dummyDeck)

        viewModel = StudyViewModel(
            intelligenceService = intelligenceService,
            scheduler = scheduler,
            exportService = exportService,
            repository = repository,
            savedStateHandle = savedStateHandle
        )

        testDispatcher.scheduler.advanceUntilIdle()

        // Verify deck is loaded
        assertNotNull(viewModel.deck.value)
        assertEquals(2, viewModel.deck.value!!.cards.size)
        assertEquals(0, viewModel.currentCardIndex.value)
        assertFalse(viewModel.isFlipped.value)

        // Flip card 1
        viewModel.flipCard()
        assertTrue(viewModel.isFlipped.value)

        // Rate card 1 as Easy (5)
        viewModel.rateCard(5)
        assertFalse(viewModel.isFlipped.value)
        assertEquals(1, viewModel.currentCardIndex.value)
        assertNull(viewModel.reviewSummary.value) // Not completed yet

        // Flip and rate card 2 as Good (4)
        viewModel.flipCard()
        assertTrue(viewModel.isFlipped.value)
        viewModel.rateCard(4)

        // Review completed
        val summary = viewModel.reviewSummary.value
        assertNotNull(summary)
        assertEquals(2, summary!!.totalCards)
        assertEquals(1, summary.easyCount)
        assertEquals(1, summary.goodCount)
        assertEquals(0, summary.againCount)
    }
}
