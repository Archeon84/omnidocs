package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.omnidocs.app.study.CardReviewState
import com.omnidocs.app.study.Flashcard
import com.omnidocs.app.study.StudyCardType
import org.json.JSONArray

@Entity(
    tableName = "flashcards",
    indices = [
        Index(value = ["noteId"]),
        Index(value = ["nextReviewDateMs"]),
        Index(value = ["noteId", "nextReviewDateMs"])
    ]
)
data class FlashcardEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val type: String, // QA, CLOZE, MULTIPLE_CHOICE, DEFINITION
    val prompt: String,
    val answer: String,
    val optionsJson: String, // JSON array of options
    val correctOptionIndex: Int? = null,
    val explanation: String? = null,
    val sourceSnippet: String? = null,
    val sourceTitle: String? = null,
    val isUncertain: Boolean = false,
    val tagsJson: String = "[]",
    val repetitionCount: Int = 0,
    val intervalDays: Int = 0,
    val easinessFactor: Float = 2.5f,
    val nextReviewDateMs: Long = System.currentTimeMillis(),
    val lastReviewedAtMs: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toFlashcard(): Flashcard {
        val optionsList = mutableListOf<String>()
        try {
            val arr = JSONArray(optionsJson)
            for (i in 0 until arr.length()) {
                optionsList.add(arr.getString(i))
            }
        } catch (_: Exception) {}

        val tagsList = mutableListOf<String>()
        try {
            val arr = JSONArray(tagsJson)
            for (i in 0 until arr.length()) {
                tagsList.add(arr.getString(i))
            }
        } catch (_: Exception) {}

        val cardType = try {
            StudyCardType.valueOf(type.uppercase())
        } catch (_: Exception) {
            StudyCardType.QA
        }

        return Flashcard(
            id = id,
            noteId = noteId,
            type = cardType,
            prompt = prompt,
            answer = answer,
            options = optionsList,
            correctOptionIndex = correctOptionIndex,
            explanation = explanation,
            sourceSnippet = sourceSnippet,
            sourceTitle = sourceTitle,
            isUncertain = isUncertain,
            tags = tagsList
        )
    }

    fun toCardReviewState(): CardReviewState {
        return CardReviewState(
            cardId = id,
            repetitionCount = repetitionCount,
            intervalDays = intervalDays,
            easinessFactor = easinessFactor,
            nextReviewDateMs = nextReviewDateMs,
            lastReviewedAtMs = lastReviewedAtMs
        )
    }

    companion object {
        fun fromFlashcard(
            card: Flashcard,
            reviewState: CardReviewState? = null,
            nowMs: Long = System.currentTimeMillis()
        ): FlashcardEntity {
            return FlashcardEntity(
                id = card.id,
                noteId = card.noteId,
                type = card.type.name,
                prompt = card.prompt,
                answer = card.answer,
                optionsJson = JSONArray(card.options).toString(),
                correctOptionIndex = card.correctOptionIndex,
                explanation = card.explanation,
                sourceSnippet = card.sourceSnippet,
                sourceTitle = card.sourceTitle,
                isUncertain = card.isUncertain,
                tagsJson = JSONArray(card.tags).toString(),
                repetitionCount = reviewState?.repetitionCount ?: 0,
                intervalDays = reviewState?.intervalDays ?: 0,
                easinessFactor = reviewState?.easinessFactor ?: 2.5f,
                nextReviewDateMs = reviewState?.nextReviewDateMs ?: nowMs,
                lastReviewedAtMs = reviewState?.lastReviewedAtMs,
                createdAt = nowMs,
                updatedAt = nowMs
            )
        }
    }
}
