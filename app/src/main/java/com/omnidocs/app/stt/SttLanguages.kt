package com.omnidocs.app.stt

/**
 * Speech languages shared by Settings ("Speech Language") and the voice
 * capture overlay chips. Single source of truth so the two UIs can never
 * drift: codes are what `ModelPreferences.sttLanguage` persists and what the
 * Sherpa/system engines accept ("" = auto-detect).
 */
val STT_LANGUAGES = listOf(
    "" to "Auto",
    "en" to "EN",
    "ms" to "BM",
    "zh" to "中文",
    "ja" to "日本語",
    "ko" to "한국어",
    "es" to "ES",
    "ar" to "AR",
    "vi" to "VI",
    "uk" to "UK"
)

/** Full code → display-name pairs for the Settings language dialog. */
val STT_LANGUAGES_WITH_NAMES = listOf(
    "" to "Auto-detect",
    "en" to "English",
    "ms" to "Malay (Bahasa Melayu)",
    "zh" to "Chinese (Mandarin)",
    "ja" to "Japanese",
    "ko" to "Korean",
    "es" to "Spanish",
    "ar" to "Arabic",
    "vi" to "Vietnamese",
    "uk" to "Ukrainian"
)

/** Display name for a language code, e.g. in Settings rows. */
fun sttLanguageDisplayName(code: String): String = when (code) {
    "" -> "Auto-detect"
    "en" -> "English"
    "ms" -> "Malay (Bahasa Melayu)"
    "zh" -> "Chinese (Mandarin)"
    "ja" -> "Japanese"
    "ko" -> "Korean"
    "es" -> "Spanish"
    "ar" -> "Arabic"
    "vi" -> "Vietnamese"
    "uk" -> "Ukrainian"
    else -> code
}
