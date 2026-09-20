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
    fun testBuildPrompt_thinkingModel_prefillsEmptyThinkBlock() {
        val thinking = ModelInfo(
            id = "qwen3_8b", name = "Qwen3", description = "", size = "",
            downloadUrl = "", fileName = "", isThinkingModel = true
        )
        val prompt = PromptBuilder.buildPrompt(PromptFormat.CHATML, "System.", "Hello.", thinking)
        // The empty think block prefill deterministically disables reasoning
        // — the model physically cannot open a think span.
        assertTrue("thinking model must prefill empty think block", prompt.contains("<think></think>"))
        // Still well-formed ChatML.
        assertTrue(prompt.contains("<|im_start|>system\nSystem.<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>assistant\n"))
    }

    @Test
    fun testBuildPrompt_nonThinkingModel_noPrefill() {
        val plain = ModelInfo(
            id = "qwen2.5_1.5b", name = "Qwen2.5", description = "", size = "",
            downloadUrl = "", fileName = ""
        )
        val prompt = PromptBuilder.buildPrompt(PromptFormat.CHATML, "System.", "Hello.", plain)
        assertFalse(prompt.contains("<think></think>"))
        // Plain model prompt ends directly at assistant open.
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun testBuildChatML_forceNoThink_false_noBlock() {
        val prompt = PromptBuilder.buildChatML("System.", "Hello.", forceNoThink = false)
        assertFalse(prompt.contains("<think></think>"))
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
    fun testAiOutputProcessor_cutsSimulatedQuestionTurn() {
        val raw = "The RAG system retrieves notes [Source 1].\n\nQ: What is chunking?\n\nA: Splitting text."
        val processed = AiOutputProcessor.process(raw)

        assertEquals("The RAG system retrieves notes [Source 1].", processed)
    }

    @Test
    fun testAiOutputProcessor_keepsBareQLineWithoutQuestion() {
        // A "Q:" line that is NOT a new question (no "?") is content, not a
        // simulated turn: cutting here amputated legitimate answers.
        val raw = "Grading rubric [Source 2].\n\nQ: score below fifty percent\n\nThe note continues here."
        val processed = AiOutputProcessor.process(raw)

        assertTrue(processed.contains("The note continues here."))
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
            "Corrected text:\nThis is the corrected text." to "This is the corrected text.",
            "Answer:\nThe meeting is scheduled for 3 PM." to "The meeting is scheduled for 3 PM.",
            "**Answer:**\nThe budget is $50,000." to "The budget is $50,000.",
            "Response:\nProject alpha was completed in Q2." to "Project alpha was completed in Q2."
        )

        for ((input, expected) in testCases) {
            val processed = AiOutputProcessor.process(input)
            assertEquals("Failed to strip preamble from: $input", expected, processed)
        }
    }

    @Test
    fun testAiOutputProcessor_cutsOffSimulatedSubsequentTurns() {
        val rawWithSimulatedTurns = """
            The project launch date is September 15th according to [Source 1].

            User: When is the launch?
            Assistant: The launch is on September 15th.
        """.trimIndent()

        val processed = AiOutputProcessor.process(rawWithSimulatedTurns)
        assertEquals("The project launch date is September 15th according to [Source 1].", processed)
        assertFalse(processed.contains("User:"))
        assertFalse(processed.contains("Assistant:"))
    }

    @Test
    fun testAiOutputProcessor_deduplicatesRepeatedParagraphs() {
        val rawDoubleAnswer = """
            The quarterly revenue reached $1.2M, representing a 15% increase year-over-year.

            The quarterly revenue reached $1.2M, representing a 15% increase year-over-year.
        """.trimIndent()

        val processed = AiOutputProcessor.process(rawDoubleAnswer)
        assertEquals("The quarterly revenue reached $1.2M, representing a 15% increase year-over-year.", processed)
    }

    @Test
    fun testAiOutputProcessor_stripsAllThinkingVariations() {
        val cases = listOf(
            "<think>\nLet's analyze the query step by step.\n1. Look at note.\n2. Form answer.\n</think>\nThe note covers budget allocations." to "The note covers budget allocations.",
            "<thought>I should respond in English.</thought>Project deadline is next Friday." to "Project deadline is next Friday.",
            "<reasoning>Extracting action items from transcript.</reasoning>• Buy supplies\n• Email client" to "• Buy supplies\n• Email client",
            "<THINK>Internal chain of thought</THINK>Task completed." to "Task completed.",
            "<think/>No thinking needed. Direct answer." to "No thinking needed. Direct answer."
        )

        for ((input, expected) in cases) {
            val processed = AiOutputProcessor.process(input)
            assertEquals("Failed for input: $input", expected, processed)
        }
    }

    @Test
    fun testAiOutputProcessor_trimDanglingIncompleteText() {
        // 1. Trims dangling unclosed citation like "[Source 1" or "[Sources"
        val cutCitation = "The deployment succeeded and tables were migrated [Source 1"
        assertEquals(
            "The deployment succeeded and tables were migrated",
            AiOutputProcessor.trimDanglingIncompleteText(cutCitation)
        )

        val cutSources = "Results are verified [Sources"
        assertEquals(
            "Results are verified",
            AiOutputProcessor.trimDanglingIncompleteText(cutSources)
        )

        // 2. Trims trailing empty list markers
        val trailingNumberedList = "Key items:\n1. First point\n2. "
        assertEquals(
            "Key items:\n1. First point",
            AiOutputProcessor.trimDanglingIncompleteText(trailingNumberedList)
        )

        val trailingBulletList = "Key items:\n- Point A\n- "
        assertEquals(
            "Key items:\n- Point A",
            AiOutputProcessor.trimDanglingIncompleteText(trailingBulletList)
        )

        // 3. Closes unclosed markdown code fence
        val unclosedCode = "Here is the snippet:\n```kotlin\nval x = 1"
        val balanced = AiOutputProcessor.trimDanglingIncompleteText(unclosedCode)
        assertTrue("Must close unbalanced code block", balanced.endsWith("\n```"))

        // 4. Strips dangling cut-off punctuation and unclosed opening delimiters
        val cutComma = "The architecture uses MVVM, Room, and,"
        assertEquals("The architecture uses MVVM, Room, and", AiOutputProcessor.trimDanglingIncompleteText(cutComma))

        val cutColon = "Configuration options:"
        assertEquals("Configuration options", AiOutputProcessor.trimDanglingIncompleteText(cutColon))

        val cutDash = "The primary components are - "
        assertEquals("The primary components are", AiOutputProcessor.trimDanglingIncompleteText(cutDash))

        val cutParen = "The application database is encrypted (SQLCipher ("
        assertEquals("The application database is encrypted (SQLCipher", AiOutputProcessor.trimDanglingIncompleteText(cutParen))
    }

    @Test
    fun testPipelinedThinkingAndStopStreamFilters_sequentialFifoOrdering() {
        val thinkFilter = ThinkingStreamFilter()
        val stopFilter = StopStringFilter()
        val collected = StringBuilder()
        val streamedTokens = mutableListOf<String>()

        val text = "Based on the notes, no specific missing information was identified."
        // Feed in various chunk sizes to stress test boundary holdbacks
        val chunks = listOf("Based on the ", "notes, no specific ", "missing information ", "was identified.")

        for (token in chunks) {
            val visible = thinkFilter.feed(token)
            if (visible.isNotEmpty()) {
                val releasable = stopFilter.feed(visible)
                if (releasable.isNotEmpty()) {
                    collected.append(releasable)
                    streamedTokens.add(releasable)
                }
            }
        }

        // Pipelined flush (as implemented in AnswerAgent.kt):
        val thinkTail = thinkFilter.flush()
        if (thinkTail.isNotEmpty() && !stopFilter.stopped) {
            val releasable = stopFilter.feed(thinkTail)
            if (releasable.isNotEmpty()) {
                collected.append(releasable)
                streamedTokens.add(releasable)
            }
        }
        if (!stopFilter.stopped) {
            val stopTail = stopFilter.flush()
            if (stopTail.isNotEmpty()) {
                collected.append(stopTail)
                streamedTokens.add(stopTail)
            }
        }

        // Must match exactly, with NO scrambling of "missing information" or "was identified"
        assertEquals(text, collected.toString())
        assertEquals(text, streamedTokens.joinToString(""))
        assertFalse("Must never scramble word fragments", collected.contains("m was indentified.issing"))
    }
}

