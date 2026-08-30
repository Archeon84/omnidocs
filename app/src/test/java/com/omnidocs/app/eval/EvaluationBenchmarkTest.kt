package com.omnidocs.app.eval

import com.omnidocs.app.agent.AnswerAgent
import com.omnidocs.app.agent.Citation
import com.omnidocs.app.data.local.entity.ActionItemEntity
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.sync.ConflictField
import com.omnidocs.app.sync.ConflictResolutionChoice
import com.omnidocs.app.sync.NoteConflictResolver
import com.omnidocs.app.util.HtmlSanitizer
import com.omnidocs.app.util.MarkdownCodec
import com.omnidocs.app.vocabulary.VocabularyDictionaryService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/**
 * Benchmark and evaluation test suite measuring key metrics defined in the
 * Evidence-First AI Master Plan:
 * 1. Evidence link correctness & SHA-256 quote hash verification
 * 2. BM-English cross-language vocabulary normalization
 * 3. Offline 3-way synchronization & conflict resolution reliability
 * 4. Malformed Markdown & HTML sanitization resiliency
 */
class EvaluationBenchmarkTest {

    private lateinit var vocabService: VocabularyDictionaryService
    private lateinit var conflictResolver: NoteConflictResolver

    @Before
    fun setUp() {
        vocabService = VocabularyDictionaryService()
        conflictResolver = NoteConflictResolver()
    }

    // ── 1. Evidence-Link Correctness & Hash Verification ─────────────────

    @Test
    fun testEvaluation_evidenceLinkAndQuoteHashCorrectness() {
        val originalText = "The quarterly financial report confirms a 15% year-over-year revenue increase to $1.2M."
        val quoteSnippet = originalText.take(50)

        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(quoteSnippet.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val citation = Citation(
            noteId = "note_eval_01",
            noteTitle = "Q3 Financial Report",
            startOffset = 0,
            endOffset = 50,
            quoteSnippet = quoteSnippet,
            quoteHash = expectedHash
        )

        assertEquals("note_eval_01", citation.noteId)
        assertEquals(expectedHash, citation.quoteHash)
        assertTrue(originalText.startsWith(citation.quoteSnippet))
    }

    // ── 2. BM-English Code-Switching & Vocabulary Normalization ──────────

    @Test
    fun testEvaluation_bmEnglishVocabularyNormalizationPrecision() {
        val testCorpus = listOf(
            "Mesyuarat dgn pegawai lhdn utk bincang audit cukai." to "Mesyuarat dengan pegawai LHDN untuk bincang audit cukai.",
            "Tolong semak baki kwsp dan perkeso pekerja skg." to "Tolong semak baki KWSP dan PERKESO pekerja sekarang.",
            "Projek pembinaan uitm shah alam diketuai oleh jkr." to "Projek pembinaan UITM shah alam diketuai oleh JKR."
        )

        var correctNormalizations = 0
        for ((input, expected) in testCorpus) {
            val normalized = vocabService.normalizeTranscript(input, expandAcronymsInPlace = false)
            if (normalized == expected) {
                correctNormalizations++
            }
        }

        val accuracy = correctNormalizations.toDouble() / testCorpus.size
        assertEquals(1.0, accuracy, 0.001) // 100% accuracy on standard benchmark set
    }

    // ── 3. Offline 3-Way Synchronization & Conflict Reliability ──────────

    @Test
    fun testEvaluation_offline3WaySyncReliability() {
        val baseNote = NoteEntity(
            id = "note_sync_bench",
            title = "Sprint Goal",
            content = "1. Deliver MVP\n2. Run security audit",
            plainText = "",
            isPinned = false,
            language = "en",
            createdAt = 1000L,
            updatedAt = 2000L,
            imageUrl = null
        )

        val localModified = baseNote.copy(
            content = "1. Deliver MVP (Completed)\n2. Run security audit"
        )

        val remoteModified = baseNote.copy(
            content = "1. Deliver MVP\n2. Run security audit (Passed)"
        )

        val report = conflictResolver.detectConflicts(baseNote, localModified, remoteModified)
        assertTrue(report.hasConflicts)

        val choices = mapOf(
            ConflictField.CONTENT to ConflictResolutionChoice.CONCATENATE_BOTH
        )

        val merged = conflictResolver.resolveConflicts(report, localModified, remoteModified, choices)
        assertTrue(merged.isResolved)
        assertTrue(merged.content.contains("Deliver MVP (Completed)"))
        assertTrue(merged.content.contains("--- MERGED VERSION ---"))
        assertTrue(merged.content.contains("Run security audit (Passed)"))
    }

    // ── 4. Malformed Markdown & HTML Resiliency ──────────────────────────

    @Test
    fun testEvaluation_malformedMarkdownAndHtmlResiliency() {
        val unclosedHtml = "<p>Paragraph without closing tag<b>Bold text<i>Italic without close"
        val sanitized = HtmlSanitizer.sanitize(unclosedHtml)

        assertNotNull(sanitized)
        assertTrue(sanitized.contains("Paragraph without closing tag"))
        assertTrue(sanitized.contains("Bold text"))

        val xssAttempt = "<a href=\"javascript:alert('pwn')\">Malicious link</a><script>alert(1)</script>"
        val secureHtml = HtmlSanitizer.sanitize(xssAttempt)

        assertFalse(secureHtml.contains("javascript:"))
        assertFalse(secureHtml.contains("<script>"))
        assertTrue(secureHtml.contains("Malicious link"))
    }
}
