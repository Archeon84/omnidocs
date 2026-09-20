package com.omnidocs.app.study

enum class StudyCardType {
    QA,
    CLOZE,
    MULTIPLE_CHOICE,
    DEFINITION
}

data class Flashcard(
    val id: String,
    val noteId: String,
    val type: StudyCardType = StudyCardType.QA,
    val prompt: String,
    val answer: String,
    val options: List<String> = emptyList(), // For MULTIPLE_CHOICE
    val correctOptionIndex: Int? = null,
    val explanation: String? = null,
    val sourceSnippet: String? = null,
    val sourceTitle: String? = null,
    val isUncertain: Boolean = false,
    val tags: List<String> = emptyList()
)

data class CardReviewState(
    val cardId: String,
    val repetitionCount: Int = 0,
    val intervalDays: Int = 0,
    val easinessFactor: Float = 2.5f,
    val nextReviewDateMs: Long = System.currentTimeMillis(),
    val lastReviewedAtMs: Long? = null
)

data class StudyDeck(
    val title: String,
    val noteId: String,
    val cards: List<Flashcard>
)
