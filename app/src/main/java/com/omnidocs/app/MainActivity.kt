package com.omnidocs.app

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import com.omnidocs.app.ui.navigation.Screen
import com.omnidocs.app.ui.screens.security.AppLockScreen
import com.omnidocs.app.ui.screens.welcome.OnboardingPreferences
import com.omnidocs.app.ui.theme.NotesAppTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Payload extracted from an incoming `ACTION_SEND` or `ACTION_SEND_MULTIPLE` intent.
 */
data class ShareIntentPayload(
    val sharedText: String? = null,
    val sharedTitle: String? = null,
    val sharedUris: List<Uri> = emptyList(),
    val mimeType: String? = null
)

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    @Inject
    lateinit var securityPreferences: SecurityPreferences

    @Inject
    lateinit var onboardingPreferences: OnboardingPreferences

    private var isUnlocked = mutableStateOf(false)
    private var authErrorMessage = mutableStateOf<String?>(null)
    private var lastBackgroundTimestamp = 0L
    private val lockGracePeriodMs = 15_000L

    private var pendingQuickAction = mutableStateOf<String?>(null)
    private var pendingSharePayload = mutableStateOf<ShareIntentPayload?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (savedInstanceState == null) {
            parseIncomingIntent(intent)
        }

        setContent {
            val appLockEnabled by securityPreferences.isAppLockEnabled.collectAsState()
            val hasCompletedOnboarding by onboardingPreferences.hasCompletedOnboarding.collectAsState()
            val unlocked by isUnlocked
            val errorMsg by authErrorMessage
            val quickAction by pendingQuickAction
            val sharePayload by pendingSharePayload

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
                    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                        val startDestination = remember {
                            if (onboardingPreferences.hasCompletedOnboarding.value ||
                                pendingQuickAction.value != null ||
                                pendingSharePayload.value != null
                            ) {
                                Screen.Home.route
                            } else {
                                Screen.Welcome.route
                            }
                        }
                        NotesNavHost(
                            startDestination = startDestination,
                            pendingQuickAction = quickAction,
                            onConsumeQuickAction = { pendingQuickAction.value = null },
                            pendingSharePayload = sharePayload,
                            onConsumeSharePayload = { pendingSharePayload.value = null }
                        )

                        if (appLockEnabled && !unlocked) {
                            AppLockScreen(
                                onUnlockClick = { triggerBiometricAuth() },
                                errorMessage = errorMsg
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseIncomingIntent(intent)
    }

    private fun parseIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme == "omnidocs") {
                    when (data.host) {
                        "quick_action" -> {
                            val action = data.lastPathSegment
                            if (!action.isNullOrBlank()) {
                                pendingQuickAction.value = action
                            }
                        }
                        "note" -> {
                            val noteId = data.lastPathSegment
                            if (!noteId.isNullOrBlank()) {
                                pendingQuickAction.value = "open_note:$noteId"
                            }
                        }
                    }
                }
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    ?: intent.getStringExtra(Intent.EXTRA_TITLE)
                val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (!text.isNullOrBlank() || streamUri != null) {
                    pendingSharePayload.value = ShareIntentPayload(
                        sharedText = text,
                        sharedTitle = subject,
                        sharedUris = listOfNotNull(streamUri),
                        mimeType = intent.type
                    )
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
                }
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    ?: intent.getStringExtra(Intent.EXTRA_TITLE)
                if (uris.isNotEmpty()) {
                    pendingSharePayload.value = ShareIntentPayload(
                        sharedText = intent.getStringExtra(Intent.EXTRA_TEXT),
                        sharedTitle = subject,
                        sharedUris = uris,
                        mimeType = intent.type
                    )
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

    override fun onStop() {
        super.onStop()
        lastBackgroundTimestamp = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        com.omnidocs.app.widget.OmniDocsWidgetManager.notifyDataChanged(this)
        if (securityPreferences.isAppLockEnabled.value) {
            val now = System.currentTimeMillis()
            if (lastBackgroundTimestamp > 0L && (now - lastBackgroundTimestamp > lockGracePeriodMs)) {
                isUnlocked.value = false
            }
            if (!isUnlocked.value && biometricAuthManager.canAuthenticate()) {
                triggerBiometricAuth()
            }
        }
    }
}
