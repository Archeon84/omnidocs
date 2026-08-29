package com.omnidocs.app.ai

/**
 * Prompt format abstraction for different model families.
 * Qwen3 uses ChatML; Llama 3.2 uses its own header-based format.
 */
enum class PromptFormat {
    CHATML,
    LLAMA3
}

object PromptBuilder {

    fun buildPrompt(format: PromptFormat, systemPrompt: String, userPrompt: String): String {
        return when (format) {
            PromptFormat.CHATML -> buildChatML(systemPrompt, userPrompt)
            PromptFormat.LLAMA3 -> buildLlama3(systemPrompt, userPrompt)
        }
    }

    private fun buildChatML(systemPrompt: String, userPrompt: String): String {
        val imS = "<|im_start|>"
        val imE = "<|im_end|>"
        return imS + "system" + NL + systemPrompt + imE + NL +
               imS + "user" + NL + userPrompt + imE + NL +
               imS + "assistant" + NL
    }

    private fun buildLlama3(systemPrompt: String, userPrompt: String): String {
        return "<|start_header_id|>system<|end_header_id|>" + NL2 +
               systemPrompt + "<|eot_id|>" +
               "<|start_header_id|>user<|end_header_id|>" + NL2 +
               userPrompt + "<|eot_id|>" +
               "<|start_header_id|>assistant<|end_header_id|>" + NL2
    }

    private val NL = "\n"
    private val NL2 = "\n\n"
}
