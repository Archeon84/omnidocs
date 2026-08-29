package com.omnidocs.app.agent

import com.omnidocs.app.ai.AutoTagger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class PipelineAgentsTest {

    @Test
    fun `normalization agent segments paragraphs with exact offsets and detects english`() = runTest {
        val agent = NormalizationAgent()
        val text = """# Introduction
This is the first paragraph.

This is the second paragraph with more details."""

        val input = AgentInput(
            type = "NORMALIZATION_INPUT",
            payload = mapOf("plainText" to text, "htmlContent" to "<p>$text</p>")
        )
        val context = AgentContext(jobId = "test_job")

        val result = agent.execute(input, context)
        assertTrue(result is AgentResult.Success)

        val payload = (result as AgentResult.Success).payload
        assertEquals("en", payload["language"])
        val blockCount = payload["blockCount"] as Int
        assertTrue(blockCount >= 2)
    }

    @Test
    fun `normalization agent detects malay language indicators`() = runTest {
        val agent = NormalizationAgent()
        val malayText = """Dokumen ini adalah untuk kegunaan rasmi dan tidak boleh disebarkan tanpa kebenaran.
Sila pastikan maklumat ini disimpan dengan selamat."""

        val input = AgentInput(
            type = "NORMALIZATION_INPUT",
            payload = mapOf("plainText" to malayText, "htmlContent" to "<p>$malayText</p>")
        )
        val context = AgentContext(jobId = "test_malay")

        val result = agent.execute(input, context)
        assertTrue(result is AgentResult.Success)

        val payload = (result as AgentResult.Success).payload
        assertEquals("ms", payload["language"])
    }

    @Test
    fun `organization agent derives title and extracts todo action items`() = runTest {
        val autoTagger = mock(AutoTagger::class.java)
        val agent = OrganizationAgent(autoTagger)
        val text = """# Project Roadmap 2026
Here is our plan for Q3.

TODO: Finalize database migration
Action Item: Review security posture before deploy
[ ] Prepare meeting slides"""

        val input = AgentInput(
            type = "ORGANIZATION_INPUT",
            payload = mapOf("title" to "", "normalizedText" to text)
        )
        val context = AgentContext(jobId = "test_org")

        val result = agent.execute(input, context)
        assertTrue(result is AgentResult.Success)

        val payload = (result as AgentResult.Success).payload
        val title = payload["suggestedTitle"] as String
        assertEquals("Project Roadmap 2026", title)

        val taskCount = payload["taskCount"] as Int
        assertTrue(taskCount >= 3)
    }
}
