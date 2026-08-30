package com.omnidocs.app.study

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Implements the SuperMemo-2 (SM-2) Spaced Repetition scheduling algorithm.
 *
 * Ratings:
 * 5 - Perfect response, no hesitation
 * 4 - Correct response after a hesitation
 * 3 - Correct response recalled with serious difficulty
 * 2 - Incorrect response; where the correct one seemed easy to recall
 * 1 - Incorrect response; the correct one remembered
 * 0 - Complete blackout
 */
@Singleton
class SpacedRepetitionScheduler @Inject constructor() {

    companion object {
        const val MIN_EASINESS_FACTOR = 1.3f
        const val DEFAULT_EASINESS_FACTOR = 2.5f
        const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L
    }

    /**
     * Calculates the updated CardReviewState given the user's recall quality (0 to 5).
     */
    fun scheduleNextReview(
        currentState: CardReviewState,
        qualityScore: Int,
        nowMs: Long = System.currentTimeMillis()
    ): CardReviewState {
        val q = qualityScore.coerceIn(0, 5)

        // 1. Calculate new Easiness Factor (EF)
        // EF' = EF + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02))
        val qDiff = 5 - q
        val efDelta = 0.1f - qDiff * (0.08f + qDiff * 0.02f)
        val newEf = max(MIN_EASINESS_FACTOR, currentState.easinessFactor + efDelta)

        // 2. Determine repetition count and next interval
        val (newRepetition, newIntervalDays) = if (q < 3) {
            // Failed recall: reset repetitions, review next day (interval = 1)
            Pair(0, 1)
        } else {
            // Successful recall
            val rep = currentState.repetitionCount + 1
            val interval = when (currentState.repetitionCount) {
                0 -> 1
                1 -> 6
                else -> max(1, (currentState.intervalDays * newEf).roundToInt())
            }
            Pair(rep, interval)
        }

        val nextReviewMs = nowMs + (newIntervalDays * MILLIS_PER_DAY)

        return currentState.copy(
            repetitionCount = newRepetition,
            intervalDays = newIntervalDays,
            easinessFactor = newEf,
            nextReviewDateMs = nextReviewMs,
            lastReviewedAtMs = nowMs
        )
    }

    /**
     * Checks if a card is due for review.
     */
    fun isDue(cardState: CardReviewState, nowMs: Long = System.currentTimeMillis()): Boolean {
        return cardState.nextReviewDateMs <= nowMs
    }
}
