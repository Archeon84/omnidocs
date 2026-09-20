package com.omnidocs.app.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.modelDataStore: DataStore<Preferences> by preferencesDataStore(name = "model_settings")

/**
 * Persists the user's selected AI model across app restarts.
 */
@Singleton
class ModelPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val selectedModelKey = stringPreferencesKey("selected_model_id")

    /** The currently selected model ID. Defaults to qwen3_1.7b. */
    val selectedModelId: Flow<String> = context.modelDataStore.data.map { prefs ->
        prefs[selectedModelKey] ?: DEFAULT_MODEL_ID
    }

    suspend fun setSelectedModelId(id: String) {
        context.modelDataStore.edit { prefs ->
            prefs[selectedModelKey] = id
        }
    }

    // ---- Embedding model preference ----

    private val selectedEmbeddingModelKey = stringPreferencesKey("selected_embedding_model_id")

    /** The currently selected embedding model ID. Defaults to null (n-gram fallback). */
    val selectedEmbeddingModelId: Flow<String?> = context.modelDataStore.data.map { prefs ->
        prefs[selectedEmbeddingModelKey]
    }

    suspend fun setSelectedEmbeddingModelId(id: String?) {
        context.modelDataStore.edit { prefs ->
            if (id != null) {
                prefs[selectedEmbeddingModelKey] = id
            } else {
                prefs.remove(selectedEmbeddingModelKey)
            }
        }
    }

    // ---- STT model preference ----

    private val selectedSttModelKey = stringPreferencesKey("selected_stt_model_id")

    /** The currently selected STT model ID. Defaults to null (system recognizer). */
    val selectedSttModelId: Flow<String?> = context.modelDataStore.data.map { prefs ->
        prefs[selectedSttModelKey]
    }

    suspend fun setSelectedSttModelId(id: String?) {
        context.modelDataStore.edit { prefs ->
            if (id != null) {
                prefs[selectedSttModelKey] = id
            } else {
                prefs.remove(selectedSttModelKey)
            }
        }
    }

    // ---- STT language preference ----

    private val sttLanguageKey = stringPreferencesKey("stt_language")

    /** ISO 639-1 language code for Whisper multilingual models. Empty = auto-detect. */
    val sttLanguage: Flow<String> = context.modelDataStore.data.map { prefs ->
        prefs[sttLanguageKey] ?: ""
    }

    suspend fun setSttLanguage(lang: String) {
        context.modelDataStore.edit { prefs ->
            prefs[sttLanguageKey] = lang
        }
    }

    // ---- STT accuracy mode preference ----

    private val sttAccuracyModeKey = stringPreferencesKey("stt_accuracy_mode")

    /** "fast" (default, 3s segments) or "accurate" (5s segments). Controls Whisper decode context. */
    val sttAccuracyMode: Flow<String> = context.modelDataStore.data.map { prefs ->
        prefs[sttAccuracyModeKey] ?: "fast"
    }

    suspend fun setSttAccuracyMode(mode: String) {
        context.modelDataStore.edit { prefs ->
            prefs[sttAccuracyModeKey] = mode
        }
    }

    companion object {
        const val DEFAULT_MODEL_ID = "qwen3.5_2b"
    }
}
