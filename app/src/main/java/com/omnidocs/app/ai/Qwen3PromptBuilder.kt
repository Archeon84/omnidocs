package com.omnidocs.app.ai

object Qwen3PromptBuilder {

    // Max characters for the input text. ~2000 chars ≈ 500 tokens which is the
    // sweet spot for a 1.7B model on phone CPU — long enough to be useful, short
    // enough to decode in a reasonable time (each chunk of 256 tokens decodes in
    // a few seconds on a mid-range phone).
    private const val MAX_INPUT_CHARS = 1000

    private fun truncateText(text: String): String {
        if (text.length <= MAX_INPUT_CHARS) return text
        return text.take(MAX_INPUT_CHARS) + "\n\n[Text truncated — showing first ${MAX_INPUT_CHARS} characters]"
    }

    fun buildSummarizePrompt(text: String, language: String = "en"): String {
        val langInstruction = if (language != "en") "\nRespond in $language language." else ""
        val systemPrompt = "You are a helpful assistant that summarizes text concisely. Keep the main points, key information, and important details. Be clear and accurate. Do not add information not present in the original text. Output only the summary, nothing else.$langInstruction"
        val userPrompt = "Summarize the following text:\n\n${truncateText(text)}"
        return formatChat(systemPrompt, userPrompt)
    }

    fun buildProofreadPrompt(text: String, language: String = "en"): String {
        val langInstruction = if (language != "en") "\nRespond in $language language." else ""
        val systemPrompt = "You are a professional proofreader and editor. Correct grammar, spelling, punctuation, and improve clarity. Keep the original meaning intact. Output only the corrected text, nothing else. Do not add explanations.$langInstruction"
        val userPrompt = "Proofread and correct the following text:\n\n${truncateText(text)}"
        return formatChat(systemPrompt, userPrompt)
    }

    fun buildRewritePrompt(text: String, language: String = "en"): String {
        val langInstruction = if (language != "en") "\nRespond in $language language." else ""
        val systemPrompt = "You are a professional writer. Rewrite the text to be clearer, more eloquent, and better structured while preserving the original meaning. Output only the rewritten text, nothing else.$langInstruction"
        val userPrompt = "Rewrite the following text in a better way:\n\n${truncateText(text)}"
        return formatChat(systemPrompt, userPrompt)
    }

    fun buildTranslatePrompt(text: String, targetLang: String): String {
        val langName = when (targetLang) {
            "en" -> "English"
            "ms" -> "Bahasa Melayu"
            "zh" -> "Chinese (Simplified)"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "ar" -> "Arabic"
            "hi" -> "Hindi"
            "bn" -> "Bengali"
            "ta" -> "Tamil"
            "te" -> "Telugu"
            "ur" -> "Urdu"
            "mr" -> "Marathi"
            "gu" -> "Gujarati"
            "kn" -> "Kannada"
            "ml" -> "Malayalam"
            "si" -> "Sinhala"
            "ne" -> "Nepali"
            "my" -> "Burmese"
            "km" -> "Khmer"
            "lo" -> "Lao"
            "th" -> "Thai"
            "vi" -> "Vietnamese"
            "id" -> "Indonesian"
            "tl" -> "Filipino"
            "fa" -> "Persian"
            "tr" -> "Turkish"
            "he" -> "Hebrew"
            "fr" -> "French"
            "de" -> "German"
            "es" -> "Spanish"
            "pt" -> "Portuguese"
            "it" -> "Italian"
            "ru" -> "Russian"
            "pl" -> "Polish"
            "nl" -> "Dutch"
            "sv" -> "Swedish"
            "da" -> "Danish"
            "no" -> "Norwegian"
            "fi" -> "Finnish"
            "cs" -> "Czech"
            "ro" -> "Romanian"
            "uk" -> "Ukrainian"
            "el" -> "Greek"
            "hu" -> "Hungarian"
            "sr" -> "Serbian"
            "hr" -> "Croatian"
            "sk" -> "Slovak"
            "sl" -> "Slovenian"
            "et" -> "Estonian"
            "lv" -> "Latvian"
            "lt" -> "Lithuanian"
            "ca" -> "Catalan"
            "gl" -> "Galician"
            "eu" -> "Basque"
            "af" -> "Afrikaans"
            else -> targetLang
        }
        val systemPrompt = "You are a professional translator. Translate the text accurately to $langName. Preserve the original meaning and tone. Output only the translation, nothing else."
        val userPrompt = "Translate the following text to $langName:\n\n$text"
        return formatChat(systemPrompt, userPrompt)
    }

    private fun formatChat(systemPrompt: String, userPrompt: String): String {
        // Qwen3 ChatML format: requires <|im_start|>/<|im_end|> tokens to delimit turns
        return "<|im_start|>system\n$systemPrompt<|im_end|>\n<|im_start|>user\n$userPrompt<|im_end|>\n<|im_start|>assistant\n"
    }
}
