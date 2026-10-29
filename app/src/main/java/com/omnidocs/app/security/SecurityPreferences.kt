package com.omnidocs.app.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists security preferences: App Lock enabled status and biometric unlock preferences.
 */
@Singleton
class SecurityPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)

    private val _isAppLockEnabled = MutableStateFlow(prefs.getBoolean(KEY_APP_LOCK, false))
    val isAppLockEnabled: StateFlow<Boolean> = _isAppLockEnabled.asStateFlow()

    private val _localOnlyProcessing = MutableStateFlow(prefs.getBoolean(KEY_LOCAL_ONLY, true))
    val localOnlyProcessing: StateFlow<Boolean> = _localOnlyProcessing.asStateFlow()

    private val _cloudOptIn = MutableStateFlow(prefs.getBoolean(KEY_CLOUD_OPT_IN, false))
    val cloudOptIn: StateFlow<Boolean> = _cloudOptIn.asStateFlow()

    private val _deleteAudioAfterTranscription = MutableStateFlow(prefs.getBoolean(KEY_DELETE_AUDIO_AFTER_STT, false))
    val deleteAudioAfterTranscription: StateFlow<Boolean> = _deleteAudioAfterTranscription.asStateFlow()

    private val _disableAiSuggestions = MutableStateFlow(prefs.getBoolean(KEY_DISABLE_AI_SUGGESTIONS, false))
    val disableAiSuggestions: StateFlow<Boolean> = _disableAiSuggestions.asStateFlow()

    private val _audioRetentionDays = MutableStateFlow(prefs.getInt(KEY_AUDIO_RETENTION_DAYS, 90))
    val audioRetentionDays: StateFlow<Int> = _audioRetentionDays.asStateFlow()

    fun setAppLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_LOCK, enabled).apply()
        _isAppLockEnabled.value = enabled
    }

    fun setLocalOnlyProcessing(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCAL_ONLY, enabled).apply()
        _localOnlyProcessing.value = enabled
        if (enabled && _cloudOptIn.value) {
            setCloudOptIn(false)
        }
    }

    fun setCloudOptIn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLOUD_OPT_IN, enabled).apply()
        _cloudOptIn.value = enabled
    }

    fun setDeleteAudioAfterTranscription(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DELETE_AUDIO_AFTER_STT, enabled).apply()
        _deleteAudioAfterTranscription.value = enabled
    }

    fun setDisableAiSuggestions(disabled: Boolean) {
        prefs.edit().putBoolean(KEY_DISABLE_AI_SUGGESTIONS, disabled).apply()
        _disableAiSuggestions.value = disabled
    }

    fun setAudioRetentionDays(days: Int) {
        val clamped = days.coerceIn(7, 365)
        prefs.edit().putInt(KEY_AUDIO_RETENTION_DAYS, clamped).apply()
        _audioRetentionDays.value = clamped
    }

    private companion object {
        const val KEY_APP_LOCK = "app_lock_enabled"
        const val KEY_LOCAL_ONLY = "local_only_processing"
        const val KEY_CLOUD_OPT_IN = "cloud_opt_in"
        const val KEY_DELETE_AUDIO_AFTER_STT = "delete_audio_after_stt"
        const val KEY_DISABLE_AI_SUGGESTIONS = "disable_ai_suggestions"
        const val KEY_AUDIO_RETENTION_DAYS = "audio_retention_days"
    }
}
