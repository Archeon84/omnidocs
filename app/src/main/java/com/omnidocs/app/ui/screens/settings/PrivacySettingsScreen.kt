package com.omnidocs.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnidocs.app.security.BiometricAuthManager
import com.omnidocs.app.security.SecurityPreferences

/**
 * Privacy and security settings screen.
 * Controls for data processing, retention, export, biometric app lock, and deletion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    onBack: () -> Unit,
    onDashboardClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val securityPreferences = remember { SecurityPreferences(context) }
    val biometricAuthManager = remember { BiometricAuthManager(context) }

    val isAppLockEnabled by securityPreferences.isAppLockEnabled.collectAsState()
    val canAuthenticate = remember { biometricAuthManager.canAuthenticate() }

    var localOnlyProcessing by remember { mutableStateOf(true) }
    var cloudOptIn by remember { mutableStateOf(false) }
    var deleteAudioAfterTranscription by remember { mutableStateOf(false) }
    var disableAiSuggestions by remember { mutableStateOf(false) }
    var audioRetentionDays by remember { mutableIntStateOf(90) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy & Security") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Dashboard Banner Button
            Card(
                onClick = onDashboardClick,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Privacy & Data Sovereignty",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "View on-device execution stats & zero-leak metrics",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Open Dashboard",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            // Hardware Security & App Lock
            Text(
                text = "Security & App Lock",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Biometric App Lock", fontWeight = FontWeight.Medium)
                            Text(
                                if (canAuthenticate) "Require fingerprint, face, or device PIN to open app"
                                else "Biometrics not configured on this device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isAppLockEnabled,
                            onCheckedChange = { securityPreferences.setAppLockEnabled(it) },
                            enabled = canAuthenticate
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Hardware KeyStore Encryption", fontWeight = FontWeight.Medium)
                            Text(
                                "Database passphrases protected by AndroidKeyStore AES-256-GCM",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Hardware Secured",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Processing settings
            Text(
                text = "Processing",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Local-only processing", fontWeight = FontWeight.Medium)
                            Text(
                                "Keep all AI processing on-device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = localOnlyProcessing,
                            onCheckedChange = { localOnlyProcessing = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Cloud processing", fontWeight = FontWeight.Medium)
                            Text(
                                "Allow cloud AI when local model unavailable",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = cloudOptIn,
                            onCheckedChange = { cloudOptIn = it },
                            enabled = !localOnlyProcessing
                        )
                    }
                }
            }

            // Retention settings
            Text(
                text = "Data Retention",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Delete audio after transcription", fontWeight = FontWeight.Medium)
                            Text(
                                "Automatically remove audio files once transcribed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = deleteAudioAfterTranscription,
                            onCheckedChange = { deleteAudioAfterTranscription = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Audio retention: $audioRetentionDays days", fontWeight = FontWeight.Medium)
                    Slider(
                        value = audioRetentionDays.toFloat(),
                        onValueChange = { audioRetentionDays = it.toInt() },
                        valueRange = 7f..365f,
                        steps = 50
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("7 days", style = MaterialTheme.typography.bodySmall)
                        Text("365 days", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // AI settings
            Text(
                text = "AI",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Disable AI link suggestions", fontWeight = FontWeight.Medium)
                            Text(
                                "Stop automatic note relationship suggestions",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = disableAiSuggestions,
                            onCheckedChange = { disableAiSuggestions = it }
                        )
                    }
                }
            }

            // Data management
            Text(
                text = "Data Management",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedButton(
                        onClick = { /* TODO: Export all data */ },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export All Data")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { showDeleteAllDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete All Notes & Data")
                    }
                }
            }
        }
    }
}
