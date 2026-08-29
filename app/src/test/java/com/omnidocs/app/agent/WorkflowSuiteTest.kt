package com.omnidocs.app.agent

import com.omnidocs.app.ai.LlamaCppService
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelPreferences
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class WorkflowSuiteTest {

    private lateinit var llamaCppService: LlamaCppService
    private lateinit var modelPreferences: ModelPreferences
    private lateinit var modelDownloadManager: ModelDownloadManager

    @Before
    fun setup() {
        llamaCppService = mock(LlamaCppService::class.java)
        modelPreferences = mock(ModelPreferences::class.java)
        modelDownloadManager = mock(ModelDownloadManager::class.java)

        `when`(modelPreferences.selectedModelId).thenReturn(flowOf("none"))
        `when`(modelDownloadManager.getDownloadedModels()).thenReturn(emptyList())
    }

    @Test
    fun `task agent extracts actionable items from structured text`() = runTest {
        val agent = TaskAgent(llamaCppService, modelPreferences, modelDownloadManager)
        val text = """Team sync notes:
TODO: Update Room migration to version 11
Action item: Verify arm64-v8a build on Xiaomi device
[ ] Polish Settings UI for agent audit trail"""

        val tasks = agent.extractTasks(text)
        assertTrue(tasks.size >= 3)
        assertTrue(tasks.any { it.title.contains("Update Room migration") })
    }

    @Test
    fun `transcript normalizer cleans stutter duplicates and formats timing`() = runTest {
        val agent = TranscriptNormalizerAgent()
        val input = AgentInput(
            type = "NORMALIZE_TRANSCRIPT",
            payload = mapOf("rawTranscript" to "We need to to verify verify the the offline model.")
        )
        val result = agent.execute(input, AgentContext(jobId = "test_trans"))
        assertTrue(result is AgentResult.Success)

        val payload = (result as AgentResult.Success).payload
        val normalized = payload["fullNormalizedText"] as String
        assertFalse(normalized.contains("to to"))
        assertFalse(normalized.contains("verify verify"))
    }

    @Test
    fun `summary agent generates structured extractive fallback summary`() = runTest {
        val agent = SummaryAgent(llamaCppService, modelPreferences, modelDownloadManager)
        val content = """OmniDocs provides private offline-first document indexing.
Users can import documents in 8 formats including PDF and Markdown.
Voice recordings are transcribed using offline Sherpa-ONNX Whisper models.
All data is stored in SQLCipher encrypted Room databases."""

        val summary = agent.generateSummary("OmniDocs Overview", content)
        assertTrue(summary.contains("Summary"))
        assertTrue(summary.contains("OmniDocs provides private offline-first"))
    }
}
