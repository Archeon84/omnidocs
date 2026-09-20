package com.omnidocs.app.ui.screens.study

import androidx.lifecycle.SavedStateHandle
import com.omnidocs.app.ai.NoteIntelligenceService
import com.omnidocs.app.data.local.entity.FlashcardEntity
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
    fun setUp() = runTest {
        Dispatchers.setMain(testDispatcher)
        intelligenceService = mock(NoteIntelligenceService::class.java)
        scheduler = SpacedRepetitionScheduler()
        exportService = mock(StudyExportService::class.java)
        repository = mock(NotesRepository::class.java)
        savedStateHandle = SavedStateHandle(mapOf("noteId" to "note_123"))

        `when`(repository.getFlashcardsForNote(anyString())).thenReturn(emptyList())
        `when`(repository.getDueFlashcards(anyLong(), anyInt())).thenReturn(emptyList())
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

    @Test
    fun testStudySessionFlow_intraSessionRecycling_onAgainRating() = runTest {
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

        // Rate card 1 as "Again" (1) -> should be re-enqueued at the end
        viewModel.rateCard(1)
        assertEquals(3, viewModel.activeQueue.value.size) // Card 1 re-added to end
        assertEquals("card_1", viewModel.activeQueue.value.last().id)

        // Rate card 2 as "Good" (4)
        viewModel.rateCard(4)
        assertEquals(2, viewModel.currentCardIndex.value)
        assertNull(viewModel.reviewSummary.value) // Session still active because card 1 is pending

        // Rate recycled card 1 as "Good" (4) -> session concludes
        viewModel.rateCard(4)
        val summary = viewModel.reviewSummary.value
        assertNotNull(summary)
        assertEquals(2, summary!!.totalCards) // 2 unique cards
        assertEquals(1, summary.againCount)
        assertEquals(2, summary.goodCount)
    }

    @Test
    fun testStudySessionFlow_cacheFirstLoading_usesRoomWithoutGenerating() = runTest {
        val cachedEntities = listOf(
            FlashcardEntity(
                id = "cached_1",
                noteId = "note_123",
                type = "QA",
                prompt = "Cached Prompt?",
                answer = "Cached Answer.",
                optionsJson = "[]"
            )
        )

        `when`(repository.getFlashcardsForNote("note_123")).thenReturn(cachedEntities)
        val dummyNote = Note(
            id = "note_123",
            title = "Cached Note",
            content = "Content",
            plainText = "Content",
            isPinned = false,
            language = "en",
            createdAt = 1000L,
            updatedAt = 2000L,
            imageUrl = null
        )
        `when`(repository.getNoteById("note_123")).thenReturn(dummyNote)

        viewModel = StudyViewModel(
            intelligenceService = intelligenceService,
            scheduler = scheduler,
            exportService = exportService,
            repository = repository,
            savedStateHandle = savedStateHandle
        )

        testDispatcher.scheduler.advanceUntilIdle()

        // Verify deck loaded from cache without calling intelligenceService
        verify(intelligenceService, never()).generateStudyDeck(anyString(), anyString(), anyString(), anyString())
        assertEquals(1, viewModel.deck.value!!.cards.size)
        assertEquals("Cached Prompt?", viewModel.deck.value!!.cards.first().prompt)
    }

    @Test
    fun testStudySessionFlow_projectedIntervals() = runTest {
        val dummyNote = Note(
            id = "note_123",
            title = "Kotlin Coroutines",
            content = "Content",
            plainText = "Content",
            isPinned = false,
            language = "en",
            createdAt = 1000L,
            updatedAt = 2000L,
            imageUrl = null
        )

        `when`(repository.getNoteById("note_123")).thenReturn(dummyNote)
        `when`(intelligenceService.generateStudyDeck(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(dummyDeck)

        viewModel = StudyViewModel(
            intelligenceService = intelligenceService,
            scheduler = scheduler,
            exportService = exportService,
            repository = repository,
            savedStateHandle = savedStateHandle
        )

        testDispatcher.scheduler.advanceUntilIdle()

        val againInterval = viewModel.getProjectedInterval(1)
        val goodInterval = viewModel.getProjectedInterval(4)
        val easyInterval = viewModel.getProjectedInterval(5)

        assertEquals("<10m", againInterval)
        assertEquals("1d", goodInterval)
        assertEquals("1d", easyInterval)
    }
}
