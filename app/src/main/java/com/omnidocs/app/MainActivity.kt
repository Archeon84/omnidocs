package com.omnidocs.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.omnidocs.app.security.BiometricAuthManager
import com.omnidocs.app.security.SecurityPreferences
import com.omnidocs.app.ui.navigation.NotesNavHost
import com.omnidocs.app.ui.screens.security.AppLockScreen
import com.omnidocs.app.ui.theme.NotesAppTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    @Inject
    lateinit var securityPreferences: SecurityPreferences

    private var isUnlocked = mutableStateOf(false)
    private var authErrorMessage = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val appLockEnabled by securityPreferences.isAppLockEnabled.collectAsState()
            val unlocked by isUnlocked
            val errorMsg by authErrorMessage

            LaunchedEffect(appLockEnabled) {
                if (appLockEnabled && !unlocked && biometricAuthManager.canAuthenticate()) {
                    triggerBiometricAuth()
                }
            }

            NotesAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (appLockEnabled && !unlocked) {
                        AppLockScreen(
                            onUnlockClick = { triggerBiometricAuth() },
                            errorMessage = errorMsg
                        )
                    } else {
                        NotesNavHost()
                    }
                }
            }
        }
    }

    private fun triggerBiometricAuth() {
        authErrorMessage.value = null
        biometricAuthManager.authenticate(
            activity = this,
            onSuccess = {
                isUnlocked.value = true
                authErrorMessage.value = null
            },
            onError = { error ->
                authErrorMessage.value = error
            }
        )
    }

    override fun onPause() {
        super.onPause()
        // Reset unlock state when leaving the foreground so the lock screen
        // reappears on the next resume/device handoff.
        isUnlocked.value = false
    }

    override fun onResume() {
        super.onResume()
        if (securityPreferences.isAppLockEnabled.value && biometricAuthManager.canAuthenticate()) {
            triggerBiometricAuth()
        }
    }
}
