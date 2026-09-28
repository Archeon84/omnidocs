package com.omnidocs.app.ui.screens.ask

import com.omnidocs.app.agent.Citation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskNotesViewModelTest {

    @Test
    fun `deriveEffectiveSearchQuery preserves standalone question without history`() {
        val q = "How does OCR work?"
        val effective = AskNotesViewModel.deriveEffectiveSearchQuery(q, lastMessage = null)
        assertEquals(q, effective)
    }

    @Test
    fun `deriveEffectiveSearchQuery preserves standalone long question with no pronouns`() {
        val lastMsg = GroundedAskMessage(
            question = "Tell me about offline speech recognition.",
            answer = "The app uses Sherpa-ONNX for local transcription.",
            citations = listOf(Citation(noteId = "1", noteTitle = "Audio Engine", quoteSnippet = "...", quoteHash = "h1")),
            confidence = "HIGH",
            isVerified = true,
            insufficientEvidence = false,
            durationMs = 1200L
        )
        val standalone = "What are the export formats supported for notes?"
        val effective = AskNotesViewModel.deriveEffectiveSearchQuery(standalone, lastMsg)
        assertEquals(standalone, effective)
    }

    @Test
    fun `deriveEffectiveSearchQuery resolves pronoun follow-up using previous question and answer`() {
        val lastMsg = GroundedAskMessage(
            question = "What database does the app use?",
            answer = "The application implements SQLCipher with encrypted Room persistence.",
            citations = listOf(Citation(noteId = "1", noteTitle = "Database Storage Engine", quoteSnippet = "...", quoteHash = "h1")),
            confidence = "HIGH",
            isVerified = true,
            insufficientEvidence = false,
            durationMs = 1500L
        )

        val followup = "Where is its key stored?"
        val effective = AskNotesViewModel.deriveEffectiveSearchQuery(followup, lastMsg)

        assertTrue("Must contain original follow-up query", effective.contains("Where is its key stored?"))
        assertTrue("Must extract database or sqlcipher context", effective.contains("database") || effective.contains("sqlcipher") || effective.contains("storage"))
    }

    @Test
    fun `deriveEffectiveSearchQuery resolves short follow-up question`() {
        val lastMsg = GroundedAskMessage(
            question = "Does the app support markdown export?",
            answer = "Yes, MarkdownExportService packages notes into clean markdown.",
            citations = emptyList(),
            confidence = "HIGH",
            isVerified = true,
            insufficientEvidence = false,
            durationMs = 800L
        )

        val followup = "Why?"
        val effective = AskNotesViewModel.deriveEffectiveSearchQuery(followup, lastMsg)

        assertTrue("Must expand short follow-up", effective.contains("markdown"))
        assertTrue("Must contain original 'Why?'", effective.contains("Why?"))
    }

    @Test
    fun `deriveEffectiveSearchQuery backward compatible overload works with string previous question`() {
        val followup = "How does it work?"
        val effective = AskNotesViewModel.deriveEffectiveSearchQuery(followup, previousQuestion = "Vector search indexing")
        assertTrue("Must prepend vector search keywords", effective.contains("vector"))
        assertTrue("Must contain original query", effective.contains("How does it work?"))
    }

    @Test
    fun `buildAnnotatedAnswer creates clickable annotations for in-text citations`() {
        val citations = listOf(
            Citation(
                noteId = "note_sec",
                noteTitle = "Security Whitepaper",
                quoteSnippet = "All offline notes are encrypted with AES-256-GCM.",
                quoteHash = "h1",
                sourceIndex = 1
            ),
            Citation(
                noteId = "note_db",
                noteTitle = "Database Architecture",
                quoteSnippet = "SQLCipher provides full database encryption.",
                quoteHash = "h2",
                sourceIndex = 2
            )
        )

        val answer = "The database uses SQLCipher [Source 2], and offline notes use AES-256 [Source 1]."
        val annotated = buildAnnotatedAnswer(
            answer = answer,
            citations = citations,
            primaryColor = androidx.compose.ui.graphics.Color.Blue,
            containerColor = androidx.compose.ui.graphics.Color.LightGray
        )

        val annotations = annotated.getStringAnnotations(tag = "CITATION", start = 0, end = annotated.length)
        assertEquals(2, annotations.size)

        // First citation in text is [Source 2]
        val firstAnno = annotations[0]
        assertEquals("note_db|||SQLCipher provides full database encryption.", firstAnno.item)

        // Second citation in text is [Source 1]
        val secondAnno = annotations[1]
        assertEquals("note_sec|||All offline notes are encrypted with AES-256-GCM.", secondAnno.item)
    }

    @Test
    fun `buildAnnotatedAnswer handles numeric and ranged citations`() {
        val citations = listOf(
            Citation(
                noteId = "note_1",
                noteTitle = "Doc 1",
                quoteSnippet = "Snippet 1",
                quoteHash = "h1",
                sourceIndex = 1
            ),
            Citation(
                noteId = "note_2",
                noteTitle = "Doc 2",
                quoteSnippet = "Snippet 2",
                quoteHash = "h2",
                sourceIndex = 2
            )
        )

        // Test bare numeric [1] and range [Sources 1-2]
        val answer = "See [1] for basics and [Sources 1-2] for details."
        val annotated = buildAnnotatedAnswer(
            answer = answer,
            citations = citations,
            primaryColor = androidx.compose.ui.graphics.Color.Blue,
            containerColor = androidx.compose.ui.graphics.Color.LightGray
        )

        val annotations = annotated.getStringAnnotations(tag = "CITATION", start = 0, end = annotated.length)
        assertEquals(2, annotations.size)
        assertEquals("note_1|||Snippet 1", annotations[0].item)
        assertEquals("note_1|||Snippet 1", annotations[1].item)
    }

    @Test
    fun `buildAnnotatedAnswer leaves non-citation brackets intact without annotations`() {
        val citations = listOf(
            Citation(noteId = "note_1", noteTitle = "Doc 1", quoteSnippet = "Snippet 1", quoteHash = "h1", sourceIndex = 1)
        )
        val answer = "This is a regular list item [TODO] with [random bracket] content."
        val annotated = buildAnnotatedAnswer(
            answer = answer,
            citations = citations,
            primaryColor = androidx.compose.ui.graphics.Color.Blue,
            containerColor = androidx.compose.ui.graphics.Color.LightGray
        )

        val annotations = annotated.getStringAnnotations(tag = "CITATION", start = 0, end = annotated.length)
        assertTrue(annotations.isEmpty())
        assertTrue(annotated.text.contains("[TODO]"))
        assertTrue(annotated.text.contains("[random bracket]"))
    }

    @Test
    fun `buildAnnotatedAnswer formats markdown headings and bullets with citations`() {
        val citations = listOf(
            Citation(
                noteId = "note_arch",
                noteTitle = "Architecture",
                quoteSnippet = "HNSW index provides sub-millisecond search.",
                quoteHash = "h1",
                sourceIndex = 1
            )
        )
        val answer = """
            ### Architecture Overview [Source 1]
            - **Vector Index**: Uses USearch HNSW [Source 1]
            - **Storage**: Persisted to disk with `.usearch` files
        """.trimIndent()

        val annotated = buildAnnotatedAnswer(
            answer = answer,
            citations = citations,
            primaryColor = androidx.compose.ui.graphics.Color.Blue,
            containerColor = androidx.compose.ui.graphics.Color.LightGray,
            headingColor = androidx.compose.ui.graphics.Color.Red,
            bulletColor = androidx.compose.ui.graphics.Color.Green
        )

        // Verifies headings stripped the ### prefix
        assertTrue(annotated.text.contains("Architecture Overview"))
        assertFalse(annotated.text.contains("###"))

        // Verifies bullet list replaced - with bullet glyph
        assertTrue(annotated.text.contains("• Vector Index"))
        assertTrue(annotated.text.contains("• Storage"))

        // Verifies bold content stripped asterisks and preserved text
        assertTrue(annotated.text.contains("Vector Index"))
        assertFalse(annotated.text.contains("**Vector Index**"))

        // Verifies inline code preserved text without backticks
        assertTrue(annotated.text.contains(".usearch"))
        assertFalse(annotated.text.contains("`.usearch`"))

        // Verifies both citations were tagged
        val annotations = annotated.getStringAnnotations(tag = "CITATION", start = 0, end = annotated.length)
        assertEquals(2, annotations.size)
        assertEquals("note_arch|||HNSW index provides sub-millisecond search.", annotations[0].item)
        assertEquals("note_arch|||HNSW index provides sub-millisecond search.", annotations[1].item)
    }
}

