package com.omnidocs.app.ai

/**
 * Prompt format abstraction for different model families.
 * Qwen3/3.5 uses ChatML; Llama 3.2 uses header-based format; Phi-4 uses tag-based format.
 */
enum class PromptFormat {
    CHATML,
    LLAMA3,
    PHI4,
    GEMMA
}

object PromptBuilder {

    /**
     * Build the full chat prompt for [format]. When [model] is a thinking
     * model (Qwen3-family), the CHATML assistant turn is prefixed with an
     * EMPTY think block: the model cannot open a fresh one, so it continues
     * directly with the answer. This disables reasoning deterministically -
     * the previous /no_think suffix was only a soft hint that small models
     * ignored on device (200s of hidden "Thinking Process:" output).
     */
    fun buildPrompt(
        format: PromptFormat,
        systemPrompt: String,
        userPrompt: String,
        model: ModelInfo? = null
    ): String {
        val forceNoThink = model?.isThinkingModel == true
        return when (format) {
            PromptFormat.CHATML -> buildChatML(systemPrompt, userPrompt, forceNoThink)
            PromptFormat.LLAMA3 -> buildLlama3(systemPrompt, userPrompt)
            PromptFormat.PHI4 -> buildPhi4(systemPrompt, userPrompt)
            PromptFormat.GEMMA -> buildGemma(systemPrompt, userPrompt)
        }
    }

    /**
     * Build a multi-turn chat prompt for conversational history.
     */
    fun buildMultiTurnPrompt(
        format: PromptFormat,
        systemPrompt: String,
        messages: List<Pair<String, String>>,
        model: ModelInfo? = null
    ): String {
        if (messages.isEmpty()) {
            return buildPrompt(format, systemPrompt, "", model)
        }
        return when (format) {
            PromptFormat.CHATML -> {
                val imS = "<|im_start|>"
                val imE = "<|im_end|>"
                val sb = StringBuilder()
                if (systemPrompt.isNotBlank()) {
                    sb.append(imS).append("system").append(NL).append(systemPrompt).append(imE).append(NL)
                }
                for ((role, text) in messages) {
                    val r = if (role.equals("user", ignoreCase = true)) "user" else "assistant"
                    sb.append(imS).append(r).append(NL).append(text).append(imE).append(NL)
                }
                val forceNoThink = model?.isThinkingModel == true
                val thinkBlock = if (forceNoThink) "<think></think>\n\n" else ""
                sb.append(imS).append("assistant").append(NL).append(thinkBlock)
                sb.toString()
            }
            PromptFormat.LLAMA3 -> {
                val sb = StringBuilder()
                if (systemPrompt.isNotBlank()) {
                    sb.append("<|start_header_id|>system<|end_header_id|>").append(NL2)
                        .append(systemPrompt).append("<|eot_id|>")
                }
                for ((role, text) in messages) {
                    val r = if (role.equals("user", ignoreCase = true)) "user" else "assistant"
                    sb.append("<|start_header_id|>").append(r).append("<|end_header_id|>").append(NL2)
                        .append(text).append("<|eot_id|>")
                }
                sb.append("<|start_header_id|>assistant<|end_header_id|>").append(NL2)
                sb.toString()
            }
            PromptFormat.PHI4 -> {
                val sb = StringBuilder()
                if (systemPrompt.isNotBlank()) {
                    sb.append("<|system|>").append(NL).append(systemPrompt).append("<|end|>").append(NL)
                }
                for ((role, text) in messages) {
                    val r = if (role.equals("user", ignoreCase = true)) "user" else "assistant"
                    sb.append("<|").append(r).append("|>").append(NL).append(text).append("<|end|>").append(NL)
                }
                sb.append("<|assistant|>").append(NL)
                sb.toString()
            }
            PromptFormat.GEMMA -> {
                val sb = StringBuilder()
                var firstUser = true
                for ((role, text) in messages) {
                    if (role.equals("user", ignoreCase = true)) {
                        val content = if (firstUser && systemPrompt.isNotBlank()) {
                            firstUser = false
                            systemPrompt + NL2 + text
                        } else {
                            text
                        }
                        sb.append("<start_of_turn>user").append(NL).append(content).append("<end_of_turn>").append(NL)
                    } else {
                        sb.append("<start_of_turn>model").append(NL).append(text).append("<end_of_turn>").append(NL)
                    }
                }
                sb.append("<start_of_turn>model").append(NL)
                sb.toString()
            }
        }
    }

    internal fun buildChatML(systemPrompt: String, userPrompt: String, forceNoThink: Boolean = false): String {
        val imS = "<|im_start|>"
        val imE = "<|im_end|>"
        // Prefilled empty think block: the model physically cannot open a
        // think span, so generation starts at the answer itself.
        val thinkBlock = if (forceNoThink) "<think></think>\n\n" else ""
        return imS + "system" + NL + systemPrompt + imE + NL +
               imS + "user" + NL + userPrompt + imE + NL +
               imS + "assistant" + NL + thinkBlock
    }

    private fun buildLlama3(systemPrompt: String, userPrompt: String): String {
        return "<|start_header_id|>system<|end_header_id|>" + NL2 +
               systemPrompt + "<|eot_id|>" +
               "<|start_header_id|>user<|end_header_id|>" + NL2 +
               userPrompt + "<|eot_id|>" +
               "<|start_header_id|>assistant<|end_header_id|>" + NL2
    }

    private fun buildPhi4(systemPrompt: String, userPrompt: String): String {
        return "<|system|>" + NL +
               systemPrompt + "<|end|>" + NL +
               "<|user|>" + NL +
               userPrompt + "<|end|>" + NL +
               "<|assistant|>" + NL
    }

    private fun buildGemma(systemPrompt: String, userPrompt: String): String {
        val combinedUser = if (systemPrompt.isNotBlank()) {
            systemPrompt + NL2 + userPrompt
        } else {
            userPrompt
        }
        return "<start_of_turn>user" + NL +
               combinedUser + "<end_of_turn>" + NL +
               "<start_of_turn>model" + NL
    }

    private val NL = "\n"
    private val NL2 = "\n\n"
}
