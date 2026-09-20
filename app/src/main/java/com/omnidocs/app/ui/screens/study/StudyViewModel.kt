package com.omnidocs.app.ui.screens.study

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.ai.NoteIntelligenceService
import com.omnidocs.app.data.local.entity.FlashcardEntity
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.study.CardReviewState
import com.omnidocs.app.study.Flashcard
import com.omnidocs.app.study.SpacedRepetitionScheduler
import com.omnidocs.app.study.StudyDeck
import com.omnidocs.app.study.StudyExportService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StudyReviewSummary(
    val totalCards: Int,
    val easyCount: Int,
    val goodCount: Int,
    val hardCount: Int,
    val againCount: Int
)

@HiltViewModel
class StudyViewModel @Inject constructor(
    private val intelligenceService: NoteIntelligenceService,
    private val scheduler: SpacedRepetitionScheduler,
    private val exportService: StudyExportService,
    private val repository: NotesRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val noteId: String? = savedStateHandle["noteId"]

    private val _deck = MutableStateFlow<StudyDeck?>(null)
    val deck: StateFlow<StudyDeck?> = _deck.asStateFlow()

    // The active session queue (starts as deck.cards, can have recycled failed cards appended)
    private val _activeQueue = MutableStateFlow<List<Flashcard>>(emptyList())
    val activeQueue: StateFlow<List<Flashcard>> = _activeQueue.asStateFlow()

    private val _currentCardIndex = MutableStateFlow(0)
    val currentCardIndex: StateFlow<Int> = _currentCardIndex.asStateFlow()

    private val _isFlipped = MutableStateFlow(false)
    val isFlipped: StateFlow<Boolean> = _isFlipped.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _reviewSummary = MutableStateFlow<StudyReviewSummary?>(null)
    val reviewSummary: StateFlow<StudyReviewSummary?> = _reviewSummary.asStateFlow()

    private val reviewStateMap = mutableMapOf<String, CardReviewState>()
    private val ratingsList = mutableListOf<Int>()

    init {
        if (!noteId.isNullOrBlank()) {
            loadDeckForNote(noteId, forceRegenerate = false)
        } else {
            loadWorkspaceDueDeck()
        }
    }

    fun loadDeckForNote(id: String, forceRegenerate: Boolean = false) {
        _isLoading.value = true
        _reviewSummary.value = null
        _currentCardIndex.value = 0
        _isFlipped.value = false
        ratingsList.clear()

        viewModelScope.launch {
            try {
                // 1. Cache-first check: if not forcing regenerate, load from Room DB
                if (!forceRegenerate) {
                    val cachedEntities = repository.getFlashcardsForNote(id)
                    if (!cachedEntities.isNullOrEmpty()) {
                        val cards = cachedEntities.map { it.toFlashcard() }
                        for (entity in cachedEntities) {
                            reviewStateMap[entity.id] = entity.toCardReviewState()
                        }
                        val noteTitle = repository.getNoteById(id)?.title ?: "Study Deck"
                        val loadedDeck = StudyDeck(title = noteTitle, noteId = id, cards = cards)
                        _deck.value = loadedDeck
                        _activeQueue.value = cards
                        _isLoading.value = false
                        return@launch
                    }
                }

                // 2. Generate via LLM / rule-based fallback if cache is empty or regenerate requested
                val note = repository.getNoteById(id)
                if (note != null) {
                    val generatedDeck = intelligenceService.generateStudyDeck(
                        noteId = note.id,
                        noteTitle = note.title,
                        noteContent = if (note.plainText.isNotBlank()) note.plainText else note.content,
                        language = note.language
                    )

                    // Persist newly generated cards to Room DB
                    if (forceRegenerate) {
                        repository.deleteFlashcardsForNote(id)
                    }
                    val now = System.currentTimeMillis()
                    val entities = generatedDeck.cards.map { card ->
                        FlashcardEntity.fromFlashcard(card, nowMs = now)
                    }
                    repository.saveFlashcards(entities)

                    for (entity in entities) {
                        reviewStateMap[entity.id] = entity.toCardReviewState()
                    }

                    _deck.value = generatedDeck
                    _activeQueue.value = generatedDeck.cards
                }
            } catch (e: Exception) {
                android.util.Log.e("StudyViewModel", "Failed to load study deck", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadWorkspaceDueDeck() {
        _isLoading.value = true
        _reviewSummary.value = null
        _currentCardIndex.value = 0
        _isFlipped.value = false
        ratingsList.clear()

        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val dueEntities = repository.getDueFlashcards(now, limit = 50)
                if (!dueEntities.isNullOrEmpty()) {
                    val cards = dueEntities.map { it.toFlashcard() }
                    for (entity in dueEntities) {
                        reviewStateMap[entity.id] = entity.toCardReviewState()
                    }
                    val deck = StudyDeck(
                        title = "Daily Practice (${cards.size} due)",
                        noteId = "",
                        cards = cards
                    )
                    _deck.value = deck
                    _activeQueue.value = cards
                } else {
                    _deck.value = StudyDeck(
                        title = "Daily Practice",
                        noteId = "",
                        cards = emptyList()
                    )
                    _activeQueue.value = emptyList()
                }
            } catch (e: Exception) {
                android.util.Log.e("StudyViewModel", "Failed to load workspace due deck", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun regenerateDeck() {
        noteId?.let { loadDeckForNote(it, forceRegenerate = true) }
    }

    fun flipCard() {
        _isFlipped.value = !_isFlipped.value
    }

    /**
     * Returns the projected review interval string for the current card for a given quality score.
     */
    fun getProjectedInterval(qualityScore: Int): String {
        val queue = _activeQueue.value
        val idx = _currentCardIndex.value
        if (idx !in queue.indices) return ""

        val card = queue[idx]
        val currentState = reviewStateMap[card.id] ?: CardReviewState(cardId = card.id)
        val projectedState = scheduler.scheduleNextReview(currentState, qualityScore)

        return if (qualityScore < 3) {
            "<10m"
        } else {
            scheduler.formatIntervalDays(projectedState.intervalDays)
        }
    }

    fun rateCard(qualityScore: Int) {
        val queue = _activeQueue.value
        val idx = _currentCardIndex.value
        if (idx !in queue.indices) return

        val currentCard = queue[idx]
        val currentState = reviewStateMap[currentCard.id] ?: CardReviewState(cardId = currentCard.id)
        val updatedState = scheduler.scheduleNextReview(currentState, qualityScore)
        reviewStateMap[currentCard.id] = updatedState
        ratingsList.add(qualityScore)

        // Persist SM-2 update to Room DB asynchronously
        viewModelScope.launch {
            try {
                repository.updateFlashcardReview(
                    id = currentCard.id,
                    repetitionCount = updatedState.repetitionCount,
                    intervalDays = updatedState.intervalDays,
                    easinessFactor = updatedState.easinessFactor,
                    nextReviewDateMs = updatedState.nextReviewDateMs,
                    lastReviewedAtMs = updatedState.lastReviewedAtMs ?: System.currentTimeMillis()
                )
            } catch (e: Exception) {
                android.util.Log.e("StudyViewModel", "Failed to update review state in DB", e)
            }
        }

        // Intra-session failed card recycling:
        // If score < 3 ("Again"), re-enqueue the card at the end of the active queue
        if (qualityScore < 3) {
            _activeQueue.value = _activeQueue.value + currentCard
        }

        _isFlipped.value = false

        if (idx + 1 < _activeQueue.value.size) {
            _currentCardIndex.value = idx + 1
        } else {
            // Review session completed
            val totalUnique = _deck.value?.cards?.size ?: queue.size
            _reviewSummary.value = StudyReviewSummary(
                totalCards = totalUnique,
                easyCount = ratingsList.count { it == 5 },
                goodCount = ratingsList.count { it == 4 },
                hardCount = ratingsList.count { it == 3 },
                againCount = ratingsList.count { it < 3 }
            )
        }
    }

    fun restartDeck() {
        val originalCards = _deck.value?.cards ?: emptyList()
        _activeQueue.value = originalCards
        _currentCardIndex.value = 0
        _isFlipped.value = false
        _reviewSummary.value = null
        ratingsList.clear()
    }

    fun exportDeckMarkdown(context: Context): Boolean {
        val currentDeck = _deck.value ?: return false
        val md = exportService.exportToMarkdown(currentDeck)
        return exportService.shareStudyDeck(context, md, isMarkdown = true, baseFilename = "study_${currentDeck.title}")
    }

    fun exportDeckAnki(context: Context): Boolean {
        val currentDeck = _deck.value ?: return false
        val tsv = exportService.exportToAnkiTsv(currentDeck)
        return exportService.shareStudyDeck(context, tsv, isMarkdown = false, baseFilename = "anki_${currentDeck.title}")
    }
}
