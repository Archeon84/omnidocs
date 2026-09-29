package com.omnidocs.app.ui.screens.localchat

import androidx.compose.ui.graphics.Color
import com.omnidocs.app.ai.ModelInfo
import com.omnidocs.app.ai.PromptBuilder
import com.omnidocs.app.ai.PromptFormat
import org.junit.Assert.*
import org.junit.Test

class LocalChatTest {

    @Test
    fun `ChatMarkdownParser parses plain text without code blocks`() {
        val input = "Hello, this is a plain message from local AI."
        val segments = ChatMarkdownParser.parse(input)

        assertEquals(1, segments.size)
        assertTrue(segments[0] is ChatContentSegment.Text)
        assertEquals(input, (segments[0] as ChatContentSegment.Text).content)
    }

    @Test
    fun `ChatMarkdownParser parses code blocks with language tags`() {
        val input = """
            Here is a Kotlin example:
            ```kotlin
            val message = "Offline"
            println(message)
            ```
            And here is a bash command:
            ```bash
            ls -la
            ```
            That's all!
        """.trimIndent()

        val segments = ChatMarkdownParser.parse(input)
        assertEquals(5, segments.size)

        assertTrue(segments[0] is ChatContentSegment.Text)
        assertTrue((segments[0] as ChatContentSegment.Text).content.contains("Here is a Kotlin example:"))

        assertTrue(segments[1] is ChatContentSegment.CodeBlock)
        val code1 = segments[1] as ChatContentSegment.CodeBlock
        assertEquals("kotlin", code1.language)
        assertTrue(code1.code.contains("val message = \"Offline\""))

        assertTrue(segments[2] is ChatContentSegment.Text)

        assertTrue(segments[3] is ChatContentSegment.CodeBlock)
        val code2 = segments[3] as ChatContentSegment.CodeBlock
        assertEquals("bash", code2.language)
        assertEquals("ls -la", code2.code)

        assertTrue(segments[4] is ChatContentSegment.Text)
        assertEquals("That's all!", (segments[4] as ChatContentSegment.Text).content.trim())
    }

    @Test
    fun `ChatMarkdownParser handles active streaming unclosed code block`() {
        val streamingInput = """
            Thinking finished. Code:
            ```python
            def compute():
                return 42
        """.trimIndent()

        val segments = ChatMarkdownParser.parse(streamingInput)
        assertEquals(2, segments.size)

        assertTrue(segments[0] is ChatContentSegment.Text)
        assertTrue(segments[1] is ChatContentSegment.CodeBlock)
        val block = segments[1] as ChatContentSegment.CodeBlock
        assertEquals("python", block.language)
        assertTrue(block.code.contains("def compute():"))
    }

    @Test
    fun `buildAnnotatedMarkdown parses headings and bullet items`() {
        val md = """
            # Heading 1
            ## Heading 2
            ### Heading 3
            - Item 1
            - Item 2
            > Quoted text
        """.trimIndent()

        val annotated = ChatMarkdownParser.buildAnnotatedMarkdown(
            text = md,
            primaryColor = Color.Blue,
            headingColor = Color.DarkGray,
            codeBgColor = Color.LightGray,
            codeTextColor = Color.Black
        )

        assertTrue(annotated.text.contains("Heading 1"))
        assertTrue(annotated.text.contains("Heading 2"))
        assertTrue(annotated.text.contains("Heading 3"))
        assertTrue(annotated.text.contains("• Item 1"))
        assertTrue(annotated.text.contains("▎ Quoted text"))
    }

    @Test
    fun `buildMultiTurnPrompt ChatML formats multi-turn dialogue`() {
        val messages = listOf(
            "user" to "Hello, who are you?",
            "assistant" to "I am your on-device local AI.",
            "user" to "What is 2 + 2?"
        )

        val prompt = PromptBuilder.buildMultiTurnPrompt(
            format = PromptFormat.CHATML,
            systemPrompt = "You are an offline assistant.",
            messages = messages
        )

        assertTrue(prompt.contains("<|im_start|>system\nYou are an offline assistant.<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>user\nHello, who are you?<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>assistant\nI am your on-device local AI.<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>user\nWhat is 2 + 2?<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `buildMultiTurnPrompt Gemma formats multi-turn dialogue with system prompt on first user turn`() {
        val messages = listOf(
            "user" to "Write a haiku",
            "assistant" to "Code runs on the chip,\nNo internet needed here,\nQuietly private.",
            "user" to "Explain it"
        )

        val prompt = PromptBuilder.buildMultiTurnPrompt(
            format = PromptFormat.GEMMA,
            systemPrompt = "You are a poet.",
            messages = messages
        )

        assertTrue("First user turn must contain system prompt", prompt.contains("<start_of_turn>user\nYou are a poet.\n\nWrite a haiku<end_of_turn>"))
        assertTrue(prompt.contains("<start_of_turn>model\nCode runs on the chip"))
        assertTrue("Second user turn should only contain prompt", prompt.contains("<start_of_turn>user\nExplain it<end_of_turn>"))
        assertTrue(prompt.endsWith("<start_of_turn>model\n"))
    }

    @Test
    fun `buildMultiTurnPrompt Llama3 formats headers properly`() {
        val messages = listOf(
            "user" to "Hi",
            "assistant" to "Hello!",
            "user" to "How are you?"
        )

        val prompt = PromptBuilder.buildMultiTurnPrompt(
            format = PromptFormat.LLAMA3,
            systemPrompt = "You are a helpful bot.",
            messages = messages
        )

        assertTrue(prompt.contains("<|start_header_id|>system<|end_header_id|>\n\nYou are a helpful bot.<|eot_id|>"))
        assertTrue(prompt.contains("<|start_header_id|>user<|end_header_id|>\n\nHi<|eot_id|>"))
        assertTrue(prompt.contains("<|start_header_id|>assistant<|end_header_id|>\n\nHello!<|eot_id|>"))
        assertTrue(prompt.contains("<|start_header_id|>user<|end_header_id|>\n\nHow are you?<|eot_id|>"))
        assertTrue(prompt.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
    }
}
