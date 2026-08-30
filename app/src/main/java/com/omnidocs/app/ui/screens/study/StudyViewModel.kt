package com.omnidocs.app.ui.screens.study

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.ai.NoteIntelligenceService
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

    private val noteId: String? = savedStateHandle["noteId"]

    private val _deck = MutableStateFlow<StudyDeck?>(null)
    val deck: StateFlow<StudyDeck?> = _deck.asStateFlow()

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
            loadDeckForNote(noteId)
        }
    }

    fun loadDeckForNote(id: String) {
        _isLoading.value = true
        _reviewSummary.value = null
        _currentCardIndex.value = 0
        _isFlipped.value = false
        ratingsList.clear()

        viewModelScope.launch {
            try {
                val note = repository.getNoteById(id)
                if (note != null) {
                    val generatedDeck = intelligenceService.generateStudyDeck(
                        noteId = note.id,
                        noteTitle = note.title,
                        noteContent = if (note.plainText.isNotBlank()) note.plainText else note.content,
                        language = note.language
                    )
                    _deck.value = generatedDeck
                }
            } catch (e: Exception) {
                android.util.Log.e("StudyViewModel", "Failed to generate study deck", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun flipCard() {
        _isFlipped.value = !_isFlipped.value
    }

    fun rateCard(qualityScore: Int) {
        val currentDeck = _deck.value ?: return
        val cards = currentDeck.cards
        if (_currentCardIndex.value >= cards.size) return

        val currentCard = cards[_currentCardIndex.value]
        val currentState = reviewStateMap[currentCard.id] ?: CardReviewState(cardId = currentCard.id)
        val updatedState = scheduler.scheduleNextReview(currentState, qualityScore)
        reviewStateMap[currentCard.id] = updatedState
        ratingsList.add(qualityScore)

        _isFlipped.value = false
        if (_currentCardIndex.value + 1 < cards.size) {
            _currentCardIndex.value += 1
        } else {
            // Review session completed
            _reviewSummary.value = StudyReviewSummary(
                totalCards = cards.size,
                easyCount = ratingsList.count { it == 5 },
                goodCount = ratingsList.count { it == 4 },
                hardCount = ratingsList.count { it == 3 },
                againCount = ratingsList.count { it < 3 }
            )
        }
    }

    fun restartDeck() {
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
