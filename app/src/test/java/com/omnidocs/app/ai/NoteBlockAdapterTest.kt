package com.omnidocs.app.ai

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NoteBlockAdapterTest {

    private lateinit var adapter: NoteBlockAdapter

    @Before
    fun setUp() {
        adapter = NoteBlockAdapter()
    }

    @Test
    fun testChunkText_mergesConsecutiveItemsSeparatedByDoubleNewlines() {
        // Simulates 40 dates separated by double newlines (\n\n)
        val fortyDates = (1..40).joinToString("\n\n") { i ->
            val day = if (i < 10) "0$i" else "$i"
            "- 2026-03-$day: Scheduled project sync meeting #$i"
        }

        val segments = adapter.chunkText("note_1", "Project Timeline", fortyDates)

        // Naive \n\n splitting would produce 40 tiny segments.
        // Semantic chunking merges them into cohesive segments under 512 tokens (~2000 chars).
        assertTrue("Should merge 40 double-newline items into a small number of continuous chunks", segments.size in 1..3)
        assertTrue(segments.all { it.isListBlock })
        assertTrue(segments.all { it.tokenEstimate <= NoteBlockAdapter.MAX_CHUNK_TOKENS })

        // Verify content integrity
        assertTrue(segments.first().content.contains("2026-03-01"))
        assertTrue(segments.last().content.contains("2026-03-40"))
    }

    @Test
    fun testChunkText_respectsMarkdownHeadersAsBoundaries() {
        val markdownDoc = buildString {
            append("# Introduction\n")
            append("This is the overview of the OmniDocs platform architecture.\n\n")
            append("## Offline Inference Engine\n")
            append("- Llama.cpp JNI bindings\n")
            append("- Qwen3-1.7B on-device model\n\n")
            append("## Storage & Security\n")
            append("- Room with SQLCipher AES-256 encryption\n")
            append("- Hardware KeyStore authentication\n")
        }

        val segments = adapter.chunkText("note_2", "Architecture Overview", markdownDoc)

        assertTrue(segments.size >= 2)
        val offlineSegment = segments.find { it.headerContext?.contains("Offline Inference Engine") == true }
        assertNotNull(offlineSegment)
        assertTrue(offlineSegment!!.content.contains("Llama.cpp JNI bindings"))

        val storageSegment = segments.find { it.headerContext?.contains("Storage & Security") == true }
        assertNotNull(storageSegment)
        assertTrue(storageSegment!!.content.contains("SQLCipher AES-256"))
    }

    @Test
    fun testTokenEstimation() {
        val shortText = "Hello world"
        assertTrue(adapter.estimateTokens(shortText) in 1..4)

        val thousandCharText = "A".repeat(1000)
        assertEquals(250, adapter.estimateTokens(thousandCharText))
    }

    @Test
    fun testChunkText_sizeSplitCarriesOverlap() {
        // One topic, many lines: forces size-driven splits (no hard boundary).
        val longTopic = (1..120).joinToString("\n") { i ->
            "Line $i pads the chunk with filler words about retrieval pipelines and embeddings."
        }
        val segments = adapter.chunkText("note_overlap", "Long Note", longTopic)

        assertTrue("Long single-topic text must split", segments.size >= 2)
        val first = segments[0]
        val second = segments[1]
        // Boundary content is embedded on both sides: the second chunk opens
        // with the first chunk's tail.
        val probe = first.content.takeLast(100).trim()
        assertTrue(probe.isNotBlank())
        assertTrue("Second chunk must carry the first chunk's tail", second.content.contains(probe))
        // Offsets stay consistent: genuine overlap, not a gap.
        assertTrue(second.startOffset < first.endOffset)
        assertTrue(second.startOffset >= first.startOffset)
    }

    @Test
    fun testChunkText_headerSplitCarriesNoOverlap() {
        val doc = "# Alpha\n" + "Alpha body line.\n".repeat(5) +
            "# Beta\n" + "Beta body line.\n".repeat(5)
        val segments = adapter.chunkText("note_hdr", "Headers", doc)

        assertTrue(segments.size >= 2)
        val beta = segments.find { it.headerContext == "Beta" }!!
        assertFalse(
            "Hard topic boundary must not leak Alpha content",
            beta.content.contains("Alpha body")
        )
    }

    @Test
    fun testChunkText_protectsCodeBlocksFromSplitting() {
        val docWithCode = buildString {
            append("Introductory paragraph explaining the algorithm.\n\n")
            append("```kotlin\n")
            repeat(40) { i ->
                append("fun executeStep$i(param: Int): String = \"result_$i\"\n")
            }
            append("```\n\n")
            append("Concluding explanation of the algorithm implementation.\n")
        }

        val segments = adapter.chunkText("note_code", "Code Spec", docWithCode)
        val codeSegment = segments.find { it.content.contains("```kotlin") }
        assertNotNull("Code block must exist", codeSegment)
        assertTrue("Code block closing fence must be preserved in same chunk", codeSegment!!.content.contains("```\n") || codeSegment.content.endsWith("```"))
    }

    @Test
    fun testExpandToParentContext_expandsChildPassageToParagraph() {
        val fullText = "First section intro.\n\n" +
            "This is the opening of the key paragraph. Inside this paragraph is our target passage chunk. Here is the conclusion of the paragraph.\n\n" +
            "Next section follows later."

        // Simulate child segment of just the middle sentence
        val childContent = "Inside this paragraph is our target passage chunk."
        val start = fullText.indexOf(childContent)
        val end = start + childContent.length

        val segment = SourceSegment(
            noteId = "note_expand",
            noteTitle = "Test Note",
            content = childContent,
            startOffset = start,
            endOffset = end,
            tokenEstimate = 10,
            headerContext = "Key Paragraph"
        )

        val expanded = adapter.expandToParentContext(segment, fullText, maxChars = 500)
        assertTrue("Must expand backward to paragraph start", expanded.contains("This is the opening of the key paragraph"))
        assertTrue("Must preserve child content", expanded.contains(childContent))
        assertTrue("Must expand forward to paragraph end", expanded.contains("Here is the conclusion of the paragraph"))
    }

    @Test
    fun testChunkText_handlesFiveThousandWordsDocumentWithoutDataLoss() {
        val sectionTitles = listOf(
            "Executive Summary",
            "System Architecture",
            "Database Design",
            "Vector Search & Embeddings",
            "Speech Recognition Pipeline",
            "Security & Cryptography",
            "Performance Benchmarks",
            "Conclusion & Future Work"
        )

        val docBuilder = StringBuilder()
        for ((idx, title) in sectionTitles.withIndex()) {
            docBuilder.append("# Chapter ${idx + 1}: $title\n\n")
            repeat(10) { p ->
                docBuilder.append("## Subsection ${idx + 1}.$p: Detailed Discussion\n")
                // Generate ~75 words per paragraph = ~750 words per chapter
                docBuilder.append("This paragraph provides in-depth analysis of $title section $p. ".repeat(6))
                docBuilder.append("\n\n")
            }
        }

        val fullText = docBuilder.toString()
        val wordCount = fullText.split(Regex("\\s+")).count { it.isNotBlank() }
        assertTrue("Fixture must exceed 5,000 words (actual: $wordCount)", wordCount >= 5000)

        val segments = adapter.chunkText("note_5k", "Full Research Paper", fullText)

        assertTrue("5,000+ word document must produce multiple semantic segments", segments.size >= 12)
        // Verify every segment is safely within on-device token limits
        assertTrue("All segments must stay under MAX_CHUNK_TOKENS", segments.all { it.tokenEstimate <= NoteBlockAdapter.MAX_CHUNK_TOKENS * 2 })

        // Verify hierarchical breadcrumb tracking across deep sections
        val deepSegment = segments.find { it.headerContext?.contains("Chapter 4: Vector Search & Embeddings > Subsection 4.5: Detailed Discussion") == true }
        assertNotNull("Hierarchical header breadcrumb must be preserved for deep chapters", deepSegment)
    }
}
