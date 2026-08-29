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

    fun setAppLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_LOCK, enabled).apply()
        _isAppLockEnabled.value = enabled
    }

    private companion object {
        const val KEY_APP_LOCK = "app_lock_enabled"
    }
}
