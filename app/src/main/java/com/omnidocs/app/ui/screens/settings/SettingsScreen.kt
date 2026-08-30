package com.omnidocs.app.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.omnidocs.app.ai.DownloadState
import com.omnidocs.app.ai.ModelInfo
import com.omnidocs.app.stt.SttModelInfo
import com.omnidocs.app.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onFeedClick: () -> Unit = {},
    onAuthClick: () -> Unit = {},
    onPrivacyClick: () -> Unit = {},
    onAgentJobsClick: () -> Unit = {},
    lifecycleOwner: LifecycleOwner? = null,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val currentTheme by viewModel.currentTheme.collectAsState()
    val isSignedIn by viewModel.isSignedIn.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncMessage by viewModel.syncMessage.collectAsState()
    val selectedModelId by viewModel.selectedModelId.collectAsState()
    val selectedSttModelId by viewModel.selectedSttModelId.collectAsState()
    val selectedEmbeddingModelId by viewModel.selectedEmbeddingModelId.collectAsState()
    val sttLanguage by viewModel.sttLanguage.collectAsState()
    val sttAccuracyMode by viewModel.sttAccuracyMode.collectAsState()
    val backupFolderUri by viewModel.backupFolderUri.collectAsState()

    var showThemeDialog by remember { mutableStateOf(false) }
    var showLlmModelDialog by remember { mutableStateOf(false) }
    var showSttModelDialog by remember { mutableStateOf(false) }
    var showEmbeddingModelDialog by remember { mutableStateOf(false) }
    var showSttLanguageDialog by remember { mutableStateOf(false) }
    var showSttAccuracyDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.setBackupFolder(uri)
            viewModel.backupToDevice(uri)
        }
    }

    val backupFilePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) viewModel.restoreFromDevice(uri)
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    if (lifecycleOwner != null) {
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.checkSignInStatus()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Settings", fontWeight = FontWeight.Bold)
                        Text(
                            "Preferences, models, and backup management",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Appearance ──
            item {
                SettingsSection(title = "Appearance & Interface") {
                    SettingsTile(
                        icon = Icons.Default.Palette,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = "App Theme",
                        description = "Choose light, dark, AMOLED black, or system accent color theme",
                        trailingText = currentTheme.name.replaceFirstChar { it.titlecase() },
                        onClick = { showThemeDialog = true }
                    )
                }
            }

            // ── Cloud Sync & Backups ──
            item {
                SettingsSection(title = "Account & Cloud Sync") {
                    if (isSignedIn) {
                        SettingsTile(
                            icon = Icons.Default.AccountCircle,
                            iconTint = Color(0xFF2E7D32),
                            title = "Google Account",
                            description = "Connected and ready for Google Drive backup synchronization",
                            trailingText = "Connected",
                            trailingIcon = Icons.AutoMirrored.Filled.Logout,
                            onTrailingClick = { viewModel.signOut() }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                        SettingsTile(
                            icon = Icons.Default.CloudUpload,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = "Backup to Google Drive",
                            description = "Upload an encrypted snapshot of your workspace to Drive",
                            trailingText = if (isSyncing) "Syncing..." else "Backup",
                            onClick = if (!isSyncing) {{ viewModel.syncToCloud() }} else null
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                        SettingsTile(
                            icon = Icons.Default.CloudDownload,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = "Restore from Google Drive",
                            description = "Download and restore latest note backups from Google Drive",
                            trailingText = if (isSyncing) "Syncing..." else "Restore",
                            onClick = if (!isSyncing) {{ viewModel.syncFromCloud() }} else null
                        )
                    } else {
                        SettingsTile(
                            icon = Icons.Default.Person,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = "Sign In with Google",
                            description = "Connect your Google account to enable encrypted cloud backup",
                            trailingText = "Sign In",
                            onClick = onAuthClick
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                    SettingsTile(
                        icon = Icons.Default.SdStorage,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        title = "Local Device Backup",
                        description = "Export an encrypted backup file to your phone's internal storage",
                        trailingText = "Export",
                        onClick = if (!isSyncing) {{
                            if (backupFolderUri != null) viewModel.backupToDevice()
                            else folderPickerLauncher.launch(null)
                        }} else null
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                    SettingsTile(
                        icon = Icons.Default.FolderOpen,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        title = "Local Device Restore",
                        description = "Pick and restore a backup ZIP archive from device storage",
                        trailingText = "Restore",
                        onClick = if (!isSyncing) {{
                            backupFilePickerLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                        }} else null
                    )

                    syncMessage?.let { msg ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(msg, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // ── On-Device AI Models ──
            item {
                SettingsSection(title = "On-Device AI Engine") {
                    val activeModel = viewModel.modelDownloadManager.availableModels.find { it.id == selectedModelId }
                    SettingsTile(
                        icon = Icons.Default.SmartToy,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = "Intelligence & Q&A Model",
                        description = "On-device LLM for summarizing, source-backed Q&A, and task extraction",
                        trailingText = activeModel?.name ?: "Select Model",
                        onClick = { showLlmModelDialog = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                    val activeStt = viewModel.modelDownloadManager.sttModels.find { it.id == selectedSttModelId }
                    SettingsTile(
                        icon = Icons.Default.GraphicEq,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = "Speech-to-Text (STT)",
                        description = "Offline Whisper engine for real-time voice transcription & meetings",
                        trailingText = activeStt?.name ?: "System Recognizer",
                        onClick = { showSttModelDialog = true }
                    )

                    if (selectedSttModelId != null && selectedSttModelId != "moonshine_tiny_en") {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                        val langLabel = STT_LANGUAGES.find { it.first == sttLanguage }?.second ?: "Auto-detect"
                        SettingsTile(
                            icon = Icons.Default.Translate,
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            title = "Speech Language",
                            description = "Target spoken language for multilingual Whisper transcription",
                            trailingText = langLabel,
                            onClick = { showSttLanguageDialog = true }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                    SettingsTile(
                        icon = Icons.Default.Speed,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        title = "Transcription Accuracy Mode",
                        description = "Switch between sub-second Fast mode and 10s Accurate mode",
                        trailingText = if (sttAccuracyMode == "fast") "Fast (Low latency)" else "Accurate (Context)",
                        onClick = { showSttAccuracyDialog = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                    val activeEmb = viewModel.modelDownloadManager.embeddingModels.find { it.id == selectedEmbeddingModelId }
                    SettingsTile(
                        icon = Icons.Default.ManageSearch,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = "Semantic Search Embeddings",
                        description = "Multilingual vector embeddings to search notes by meaning",
                        trailingText = activeEmb?.name ?: "Select Model",
                        onClick = { showEmbeddingModelDialog = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                    SettingsTile(
                        icon = Icons.Default.History,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        title = "Agent Audit Logs & History",
                        description = "Inspect background multi-agent execution traces and provenance",
                        onClick = onAgentJobsClick
                    )
                }
            }

            // ── Privacy & Security ──
            item {
                SettingsSection(title = "Privacy, Security & Storage") {
                    SettingsTile(
                        icon = Icons.Default.Security,
                        iconTint = Color(0xFF2E7D32),
                        title = "Privacy & Security Dashboard",
                        description = "Configure on-device encryption, KeyStore keys, and retention policy",
                        onClick = onPrivacyClick
                    )
                }
            }

            // ── About & Information ──
            item {
                SettingsSection(title = "About OmniDocs") {
                    SettingsTile(
                        icon = Icons.Default.Update,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = "What's New",
                        description = "Explore recent feature additions and release changelogs",
                        onClick = onFeedClick
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                    SettingsTile(
                        icon = Icons.Default.Info,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        title = "Version",
                        description = "OmniDocs Evidence-First Knowledge Workspace",
                        trailingText = "1.0.0"
                    )
                }
            }
        }
    }

    // ── Dialogs ──

    if (showThemeDialog) {
        ThemeSelectionDialog(
            currentTheme = currentTheme,
            onThemeSelected = { theme ->
                viewModel.setTheme(theme)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    if (showLlmModelDialog) {
        LlmModelManagementDialog(
            viewModel = viewModel,
            selectedModelId = selectedModelId,
            onDismiss = { showLlmModelDialog = false }
        )
    }

    if (showSttModelDialog) {
        SttModelManagementDialog(
            viewModel = viewModel,
            selectedModelId = selectedSttModelId,
            onDismiss = { showSttModelDialog = false }
        )
    }

    if (showEmbeddingModelDialog) {
        EmbeddingModelManagementDialog(
            viewModel = viewModel,
            selectedModelId = selectedEmbeddingModelId,
            onDismiss = { showEmbeddingModelDialog = false }
        )
    }

    if (showSttLanguageDialog) {
        SttLanguageDialog(
            selectedLanguage = sttLanguage,
            onLanguageSelected = {
                viewModel.setSttLanguage(it)
                showSttLanguageDialog = false
            },
            onDismiss = { showSttLanguageDialog = false }
        )
    }

    if (showSttAccuracyDialog) {
        SttAccuracyDialog(
            selectedMode = sttAccuracyMode,
            onModeSelected = {
                viewModel.setSttAccuracyMode(it)
                showSttAccuracyDialog = false
            },
            onDismiss = { showSttAccuracyDialog = false }
        )
    }
}

// ── Reusable Grouped Card & Setting Tile Composables ──

@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
fun SettingsTile(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    trailingText: String? = null,
    trailingIcon: ImageVector? = null,
    onTrailingClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tinted Icon Container
        Surface(
            modifier = Modifier.size(38.dp),
            shape = RoundedCornerShape(10.dp),
            color = iconTint.copy(alpha = 0.12f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Title and 1-sentence concise description
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }

        if (trailingText != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            ) {
                Text(
                    text = trailingText,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        if (trailingIcon != null) {
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = { onTrailingClick?.invoke() }) {
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else if (onClick != null && trailingText == null) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── Model Management Dialogs (Decluttered) ──

@Composable
fun LlmModelManagementDialog(
    viewModel: SettingsViewModel,
    selectedModelId: String,
    onDismiss: () -> Unit
) {
    val downloadedModels by viewModel.downloadedModels.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Intelligence & LLM Models", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        "Download and select an on-device model for summarization, Q&A, and extraction. Runs locally without internet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(viewModel.modelDownloadManager.availableModels.size) { idx ->
                    val model = viewModel.modelDownloadManager.availableModels[idx]
                    val isDownloaded = downloadedModels.any { it.id == model.id && it.isDownloaded }
                    val isSelected = model.id == selectedModelId
                    val isDownloading = when (val state = downloadState) {
                        is DownloadState.Downloading -> state.modelId == model.id
                        else -> false
                    }
                    val progress = when (val state = downloadState) {
                        is DownloadState.Downloading -> state.progress
                        else -> 0f
                    }

                    ModelItemCard(
                        name = model.name,
                        description = model.description,
                        size = model.size,
                        isDownloaded = isDownloaded,
                        isSelected = isSelected,
                        isDownloading = isDownloading,
                        progress = progress,
                        onSelect = { viewModel.selectModel(model.id) },
                        onDownload = { viewModel.downloadModel(model) },
                        onDelete = { viewModel.deleteModel(model) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
fun SttModelManagementDialog(
    viewModel: SettingsViewModel,
    selectedModelId: String?,
    onDismiss: () -> Unit
) {
    val downloadedModels by viewModel.sttDownloadedModels.collectAsState()
    val downloadState by viewModel.sttDownloadState.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speech Recognition Models", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    // System recognizer option
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectSttModel(null) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedModelId == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedModelId == null, onClick = { viewModel.selectSttModel(null) })
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("System Recognizer", fontWeight = FontWeight.Bold)
                                Text("Android built-in speech recognition", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                items(viewModel.modelDownloadManager.sttModels.size) { idx ->
                    val model = viewModel.modelDownloadManager.sttModels[idx]
                    val isDownloaded = downloadedModels.any { it.id == model.id && it.isDownloaded }
                    val isSelected = model.id == selectedModelId
                    val isDownloading = when (val state = downloadState) {
                        is DownloadState.Downloading -> state.modelId == model.id
                        else -> false
                    }
                    val progress = when (val state = downloadState) {
                        is DownloadState.Downloading -> state.progress
                        else -> 0f
                    }

                    ModelItemCard(
                        name = model.name,
                        description = "${model.description} • (${model.languages.joinToString(", ")})",
                        size = model.size,
                        isDownloaded = isDownloaded,
                        isSelected = isSelected,
                        isDownloading = isDownloading,
                        progress = progress,
                        onSelect = { viewModel.selectSttModel(model.id) },
                        onDownload = { viewModel.downloadSttModel(model) },
                        onDelete = { viewModel.deleteSttModel(model) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
fun EmbeddingModelManagementDialog(
    viewModel: SettingsViewModel,
    selectedModelId: String?,
    onDismiss: () -> Unit
) {
    val downloadedModels by viewModel.embeddingDownloadedModels.collectAsState()
    val downloadState by viewModel.embeddingDownloadState.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Semantic Search Models", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(viewModel.modelDownloadManager.embeddingModels.size) { idx ->
                    val model = viewModel.modelDownloadManager.embeddingModels[idx]
                    val isDownloaded = downloadedModels.any { it.id == model.id && it.isDownloaded }
                    val isSelected = model.id == selectedModelId
                    val isDownloading = when (val state = downloadState) {
                        is DownloadState.Downloading -> state.modelId == model.id
                        else -> false
                    }
                    val progress = when (val state = downloadState) {
                        is DownloadState.Downloading -> state.progress
                        else -> 0f
                    }

                    ModelItemCard(
                        name = model.name,
                        description = model.description,
                        size = model.size,
                        isDownloaded = isDownloaded,
                        isSelected = isSelected,
                        isDownloading = isDownloading,
                        progress = progress,
                        onSelect = { viewModel.selectEmbeddingModel(model.id) },
                        onDownload = { viewModel.downloadEmbeddingModel(model) },
                        onDelete = { viewModel.deleteEmbeddingModel(model) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
fun ModelItemCard(
    name: String,
    description: String,
    size: String,
    isDownloaded: Boolean,
    isSelected: Boolean,
    isDownloading: Boolean,
    progress: Float,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected && isDownloaded)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Size: $size", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isDownloaded && isSelected) {
                    Icon(Icons.Default.CheckCircle, "Active", tint = MaterialTheme.colorScheme.primary)
                }
            }

            if (isDownloading) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("Downloading: ${progress.toInt()}%", style = MaterialTheme.typography.labelSmall)
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (isDownloaded) {
                    if (!isSelected) {
                        TextButton(onClick = onSelect) { Text("Select") }
                    }
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                } else {
                    Button(onClick = onDownload, enabled = !isDownloading) {
                        Text("Download")
                    }
                }
            }
        }
    }
}

@Composable
fun ThemeSelectionDialog(
    currentTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select App Theme", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                AppTheme.entries.forEach { theme ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onThemeSelected(theme) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentTheme == theme,
                            onClick = { onThemeSelected(theme) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = theme.name.replaceFirstChar { it.titlecase() },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SttLanguageDialog(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speech Language", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn {
                items(STT_LANGUAGES.size) { idx ->
                    val (code, name) = STT_LANGUAGES[idx]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLanguageSelected(code) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selectedLanguage == code, onClick = { onLanguageSelected(code) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SttAccuracyDialog(
    selectedMode: String,
    onModeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transcription Mode", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onModeSelected("fast") }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedMode == "fast", onClick = { onModeSelected("fast") })
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Fast Mode (Recommended)", fontWeight = FontWeight.Bold)
                        Text("Sub-second partials on speech pauses", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onModeSelected("accurate") }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedMode == "accurate", onClick = { onModeSelected("accurate") })
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Accurate Mode", fontWeight = FontWeight.Bold)
                        Text("10-second decode windows for maximum word accuracy", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private val STT_LANGUAGES = listOf(
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
