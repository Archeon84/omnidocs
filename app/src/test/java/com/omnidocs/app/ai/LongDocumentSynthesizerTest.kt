package com.omnidocs.app.ai

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class LongDocumentSynthesizerTest {

    private lateinit var liteRtLmService: LiteRtLmService
    private lateinit var noteBlockAdapter: NoteBlockAdapter
    private lateinit var modelPreferences: ModelPreferences
    private lateinit var modelDownloadManager: ModelDownloadManager
    private lateinit var synthesizer: LongDocumentSynthesizer

    @Before
    fun setUp() {
        liteRtLmService = mock(LiteRtLmService::class.java)
        noteBlockAdapter = NoteBlockAdapter()
        modelPreferences = mock(ModelPreferences::class.java)
        modelDownloadManager = mock(ModelDownloadManager::class.java)

        `when`(modelPreferences.selectedModelId).thenReturn(flowOf("gemma_4_e2b"))
        `when`(modelDownloadManager.getDownloadedModels()).thenReturn(
            listOf(
                ModelInfo(
                    id = "gemma_4_e2b",
                    name = "Gemma 4 E2B",
                    description = "Default",
                    size = "1.1 GB",
                    downloadUrl = "https://example.com/gemma",
                    fileName = "gemma-4-E2B-it.litertlm",
                    promptFormat = PromptFormat.GEMMA,
                    isDownloaded = true
                )
            )
        )

        synthesizer = LongDocumentSynthesizer(
            liteRtLmService = liteRtLmService,
            noteBlockAdapter = noteBlockAdapter,
            modelPreferences = modelPreferences,
            modelDownloadManager = modelDownloadManager
        )
    }

    @Test
    fun testSummarizeDocument_shortDocument_runsDirectSinglePass() = runTest {
        val shortDoc = "This is a brief project outline about on-device AI integration."
        `when`(liteRtLmService.generate(anyString(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.nullable(String::class.java)))
            .thenReturn("Direct summary of project outline.")

        val summary = synthesizer.summarizeDocument("Short Note", shortDoc)
        assertNotNull(summary)
        assertTrue(summary!!.contains("Direct summary"))
    }

    @Test
    fun testSummarizeDocument_fiveThousandWords_runsHierarchicalMapReduce() = runTest {
        // Construct a document with >5,000 words
        val builder = StringBuilder()
        for (chapter in 1..10) {
            builder.append("# Chapter $chapter: Architecture Component\n\n")
            repeat(10) { section ->
                builder.append("## Section $chapter.$section: Implementation Details\n")
                builder.append("Detailed analysis of component $chapter section $section with benchmarks. ".repeat(6))
                builder.append("\n\n")
            }
        }
        val longDoc = builder.toString()
        val wordCount = longDoc.split(Regex("\\s+")).count { it.isNotBlank() }
        assertTrue("Fixture must exceed 5,000 words (actual: $wordCount)", wordCount >= 5000)

        // Mock map-phase and reduce-phase generations
        `when`(liteRtLmService.generate(anyString(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.nullable(String::class.java)))
            .thenReturn("Extracted key discussion and metrics for section.")

        var progressCalls = 0
        var maxProgress = 0f
        val summary = synthesizer.summarizeDocument("Master Plan", longDoc) { progress ->
            progressCalls++
            if (progress > maxProgress) maxProgress = progress
        }

        assertNotNull("Map-Reduce must produce non-null summary for 5,000+ words", summary)
        assertTrue("Progress callbacks must fire during map and reduce stages", progressCalls > 2)
        assertEquals("Final progress callback must reach 100%", 1.0f, maxProgress, 0.01f)
    }
}
