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

    companion object {
        const val DEFAULT_MODEL_ID = "qwen3_1.7b"
    }
}
