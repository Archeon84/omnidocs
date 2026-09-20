package com.omnidocs.app.ai

object Qwen3PromptBuilder {

    /**
     * @param format chat template of the model that will execute the prompt.
     * Sending ChatML to a Phi-4/Llama model (or vice versa) degrades output.
     */
    fun buildTranslatePrompt(
        text: String,
        targetLang: String,
        format: PromptFormat = PromptFormat.CHATML
    ): String {
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
        return PromptBuilder.buildPrompt(format, systemPrompt, userPrompt)
    }
}
