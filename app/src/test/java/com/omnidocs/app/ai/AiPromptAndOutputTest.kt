package com.omnidocs.app.ai

import org.junit.Assert.*
import org.junit.Test

class AiPromptAndOutputTest {

    @Test
    fun testPromptBuilder_ChatML_noNoThinkToken() {
        val prompt = PromptBuilder.buildPrompt(
            format = PromptFormat.CHATML,
            systemPrompt = "You are an expert summarizer.",
            userPrompt = "Summarize this note."
        )

        assertTrue(prompt.contains("<|im_start|>system\nYou are an expert summarizer.<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>user\nSummarize this note.<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
        assertFalse("Prompt should not contain unofficial /no_think token", prompt.contains("/no_think"))
    }

    @Test
    fun testPromptBuilder_Llama3_noDuplicateBos() {
        val prompt = PromptBuilder.buildPrompt(
            format = PromptFormat.LLAMA3,
            systemPrompt = "You are an expert editor.",
            userPrompt = "Proofread this text."
        )

        assertTrue(prompt.contains("<|start_header_id|>system<|end_header_id|>\n\nYou are an expert editor.<|eot_id|>"))
        assertTrue(prompt.contains("<|start_header_id|>user<|end_header_id|>\n\nProofread this text.<|eot_id|>"))
        assertTrue(prompt.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
        assertFalse("Prompt should not have hardcoded <|begin_of_text|> since tokenizer adds it", prompt.startsWith("<|begin_of_text|>"))
    }

    @Test
    fun testAiOutputProcessor_stripsCompleteThinkingTags() {
        val raw = "<think>Analyzing key points and structure...</think>• Point 1\n• Point 2\n\n**Key Takeaway**: Final decision made."
        val processed = AiOutputProcessor.process(raw)

        assertFalse(processed.contains("<think>"))
        assertFalse(processed.contains("Analyzing key points"))
        assertTrue(processed.contains("• Point 1"))
        assertTrue(processed.contains("**Key Takeaway**: Final decision made."))
    }

    @Test
    fun testAiOutputProcessor_handlesUnclosedThinkingTagsGracefully() {
        // Model was cut off before closing </think>
        val raw = "<think>Here is my thought process and the final summary: The meeting approved the project budget of $50k."
        val processed = AiOutputProcessor.process(raw)

        assertFalse(processed.startsWith("<think>"))
        assertTrue(processed.contains("The meeting approved the project budget of $50k."))
        assertNotEquals("Should not wipe to empty string on unclosed think tag", "", processed)
    }

    @Test
    fun testAiOutputProcessor_stripsMarkdownCodeWrappers() {
        val raw = "```markdown\n• **Summary Item 1**\n• **Summary Item 2**\n```"
        val processed = AiOutputProcessor.process(raw)

        assertFalse(processed.startsWith("```"))
        assertFalse(processed.endsWith("```"))
        assertTrue(processed.contains("• **Summary Item 1**"))
    }

    @Test
    fun testAiOutputProcessor_stripsConversationalPreambles() {
        val testCases = listOf(
            "Here is the proofread version:\n\nThis is the corrected text." to "This is the corrected text.",
            "Sure! Here is the corrected text:\nThis is the corrected text." to "This is the corrected text.",
            "Certainly, here is the rewritten text:\n\nThis is the rewritten text." to "This is the rewritten text.",
            "Here's the proofread text:\nThis is the corrected text." to "This is the corrected text.",
            "Proofread version:\n\nThis is the corrected text." to "This is the corrected text.",
            "Corrected text:\nThis is the corrected text." to "This is the corrected text."
        )

        for ((input, expected) in testCases) {
            val processed = AiOutputProcessor.process(input)
            assertEquals("Failed to strip preamble from: $input", expected, processed)
        }
    }
}

