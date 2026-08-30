package com.omnidocs.app.study

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StudyEngineTest {

    private lateinit var scheduler: SpacedRepetitionScheduler
    private lateinit var exportService: StudyExportService

    @Before
    fun setUp() {
        scheduler = SpacedRepetitionScheduler()
        exportService = StudyExportService()
    }

    @Test
    fun testSm2Scheduler_initialReviewSequence() {
        val initial = CardReviewState(
            cardId = "card_1",
            repetitionCount = 0,
            intervalDays = 0,
            easinessFactor = 2.5f
        )

        val baseTime = 1756540800000L

        // Review 1: Perfect recall (q = 5)
        val r1 = scheduler.scheduleNextReview(initial, qualityScore = 5, nowMs = baseTime)
        assertEquals(1, r1.repetitionCount)
        assertEquals(1, r1.intervalDays)
        assertTrue(r1.easinessFactor >= 2.5f)
        assertEquals(baseTime + (1 * 24 * 60 * 60 * 1000L), r1.nextReviewDateMs)

        // Review 2: Good recall (q = 4)
        val r2 = scheduler.scheduleNextReview(r1, qualityScore = 4, nowMs = r1.nextReviewDateMs)
        assertEquals(2, r2.repetitionCount)
        assertEquals(6, r2.intervalDays) // SM-2 rep 1 -> 6 days
        assertEquals(r1.nextReviewDateMs + (6 * 24 * 60 * 60 * 1000L), r2.nextReviewDateMs)

        // Review 3: Perfect recall (q = 5)
        val r3 = scheduler.scheduleNextReview(r2, qualityScore = 5, nowMs = r2.nextReviewDateMs)
        assertEquals(3, r3.repetitionCount)
        assertTrue(r3.intervalDays >= 15) // ~ 6 * 2.6 = 15.6 -> 16 days
    }

    @Test
    fun testSm2Scheduler_resetOnFailedRecall() {
        val highRep = CardReviewState(
            cardId = "card_2",
            repetitionCount = 5,
            intervalDays = 45,
            easinessFactor = 2.4f
        )

        val now = 1756540800000L
        // Failed recall (q = 1)
        val failed = scheduler.scheduleNextReview(highRep, qualityScore = 1, nowMs = now)

        assertEquals(0, failed.repetitionCount) // Resets to 0
        assertEquals(1, failed.intervalDays) // Interval resets to 1
        assertTrue(failed.easinessFactor < 2.4f) // Easiness decreases
        assertEquals(now + (1 * 24 * 60 * 60 * 1000L), failed.nextReviewDateMs)
    }

    @Test
    fun testStudyExportService_markdownExport() {
        val deck = StudyDeck(
            title = "Android Architecture & Security",
            noteId = "note_arch",
            cards = listOf(
                Flashcard(
                    id = "c1",
                    noteId = "note_arch",
                    type = StudyCardType.QA,
                    prompt = "How does OmniDocs encrypt SQLite at rest?",
                    answer = "Using SQLCipher 4.5.4 with AES-256 and an Android KeyStore master key.",
                    sourceSnippet = "Database encrypted via SQLCipher with KeyStore master key",
                    tags = listOf("security", "storage")
                ),
                Flashcard(
                    id = "c2",
                    noteId = "note_arch",
                    type = StudyCardType.MULTIPLE_CHOICE,
                    prompt = "Which SIMD instruction set accelerates on-device GGUF inference on ARM64?",
                    answer = "ARM NEON",
                    options = listOf("SSE4.2", "AVX-512", "ARM NEON", "AltiVec"),
                    correctOptionIndex = 2,
                    explanation = "ARM NEON provides 128-bit vector registers for quantized matrix operations."
                )
            )
        )

        val md = exportService.exportToMarkdown(deck)

        assertNotNull(md)
        assertTrue(md.contains("# Flashcards: Android Architecture & Security"))
        assertTrue(md.contains("## Card 1"))
        assertTrue(md.contains("**Question:** How does OmniDocs encrypt SQLite at rest?"))
        assertTrue(md.contains("**Answer:** Using SQLCipher 4.5.4"))
        assertTrue(md.contains("Tags: #security #storage"))

        assertTrue(md.contains("## Card 2"))
        assertTrue(md.contains("- (✓) ARM NEON"))
        assertTrue(md.contains("- ( ) AVX-512"))
        assertTrue(md.contains("*Explanation:* ARM NEON provides 128-bit vector registers"))
    }

    @Test
    fun testStudyExportService_ankiTsvExport() {
        val deck = StudyDeck(
            title = "Malaysian History",
            noteId = "note_history",
            cards = listOf(
                Flashcard(
                    id = "c3",
                    noteId = "note_history",
                    type = StudyCardType.DEFINITION,
                    prompt = "Merdeka",
                    answer = "Independence Day of Malaysia celebrated on August 31st.",
                    tags = listOf("history", "malaysia")
                )
            )
        )

        val tsv = exportService.exportToAnkiTsv(deck)

        assertNotNull(tsv)
        assertTrue(tsv.contains("#separator:tab"))
        assertTrue(tsv.contains("Merdeka\tIndependence Day of Malaysia celebrated on August 31st.\thistory malaysia"))
    }
}
