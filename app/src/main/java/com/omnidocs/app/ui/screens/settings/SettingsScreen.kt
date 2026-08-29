package com.omnidocs.app.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.omnidocs.app.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var showThemeDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Local device backup: folder picker for Backup to Device, file picker for Restore.
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

    // Collect snackbar events from ViewModel
    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    // Section expand/collapse state
    var appearanceExpanded by remember { mutableStateOf(true) }
    var accountExpanded by remember { mutableStateOf(true) }
    var syncExpanded by remember { mutableStateOf(true) }
    var modelsExpanded by remember { mutableStateOf(true) }
    var speechExpanded by remember { mutableStateOf(true) }
    var semanticExpanded by remember { mutableStateOf(true) }
    var privacyExpanded by remember { mutableStateOf(true) }
    var aboutExpanded by remember { mutableStateOf(true) }

    val selectedSttModelId by viewModel.selectedSttModelId.collectAsState()
    val selectedEmbeddingModelId by viewModel.selectedEmbeddingModelId.collectAsState()
    val sttLanguage by viewModel.sttLanguage.collectAsState()
    val sttAccuracyMode by viewModel.sttAccuracyMode.collectAsState()
    val backupFolderUri by viewModel.backupFolderUri.collectAsState()

    // Refresh sign-in status every time the screen resumes
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
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── Appearance section ──
            stickyHeader {
                CollapsibleSectionHeader(
                    title = "Appearance",
                    isExpanded = appearanceExpanded,
                    onToggle = { appearanceExpanded = !appearanceExpanded }
                )
            }
            item {
                AnimatedVisibility(
                    visible = appearanceExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    SettingsItem(
                        icon = Icons.Default.Palette,
                        title = "Theme",
                        subtitle = currentTheme.name.replaceFirstChar { it.titlecase() },
                        onClick = { showThemeDialog = true }
                    )
                }
            }

            // ── Account section ──
            stickyHeader {
                CollapsibleSectionHeader(
                    title = "Account",
                    isExpanded = accountExpanded,
                    onToggle = { accountExpanded = !accountExpanded }
                )
            }
            item {
                AnimatedVisibility(
                    visible = accountExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    if (isSignedIn) {
                        Column {
                            SettingsItem(
                                icon = Icons.Default.Person,
                                title = "Signed in",
                                subtitle = "Google account connected"
                            )
                            SettingsItem(
                                icon = Icons.AutoMirrored.Filled.Logout,
                                title = "Sign Out",
                                subtitle = "Disconnect Google account",
                                onClick = { viewModel.signOut() }
                            )
                        }
                    } else {
                        SettingsItem(
                            icon = Icons.Default.Person,
                            title = "Sign In",
                            subtitle = "Connect Google account for sync",
                            onClick = onAuthClick
                        )
                    }
                }
            }

            // ── Sync section ──
            stickyHeader {
                CollapsibleSectionHeader(
                    title = "Sync",
                    isExpanded = syncExpanded,
                    onToggle = { syncExpanded = !syncExpanded }
                )
            }
            item {
                AnimatedVisibility(
                    visible = syncExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column {
                        SettingsItem(
                            icon = Icons.Default.CloudUpload,
                            title = "Backup to Drive",
                            subtitle = when {
                                !isSignedIn -> "Sign in to use sync"
                                isSyncing -> "Syncing..."
                                else -> "Save notes to Google Drive"
                            },
                            onClick = if (isSignedIn && !isSyncing) {{ viewModel.syncToCloud() }} else null
                        )
                        SettingsItem(
                            icon = Icons.Default.CloudDownload,
                            title = "Restore from Drive",
                            subtitle = when {
                                !isSignedIn -> "Sign in to use sync"
                                isSyncing -> "Syncing..."
                                else -> "Load notes from Google Drive"
                            },
                            onClick = if (isSignedIn && !isSyncing) {{ viewModel.syncFromCloud() }} else null
                        )
                        SettingsItem(
                            icon = Icons.Default.SdStorage,
                            title = "Backup to Device",
                            subtitle = if (isSyncing) "Syncing..." else "Save notes & recordings to phone storage",
                            onClick = if (!isSyncing) {{
                                if (backupFolderUri != null) viewModel.backupToDevice()
                                else folderPickerLauncher.launch(null)
                            }} else null
                        )
                        SettingsItem(
                            icon = Icons.Default.FolderOpen,
                            title = "Restore from Device",
                            subtitle = if (isSyncing) "Syncing..." else "Pick a backup ZIP to restore",
                            onClick = if (!isSyncing) {{
                                backupFilePickerLauncher.launch(
                                    arrayOf("application/zip", "application/octet-stream")
                                )
                            }} else null
                        )
                        syncMessage?.let { message ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            ) {
                                Text(
                                    text = message,
                                    modifier = Modifier.padding(12.dp),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }

            // ── Offline Models section ──
            stickyHeader {
                CollapsibleSectionHeader(
                    title = "Offline AI Models",
                    isExpanded = modelsExpanded,
                    onToggle = { modelsExpanded = !modelsExpanded }
                )
            }
            item {
                AnimatedVisibility(
                    visible = modelsExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column {
                        Text(
                            text = "Download models for offline use. These models run on your device without internet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        val activeModelName = viewModel.modelDownloadManager.availableModels
                            .find { it.id == selectedModelId }?.name ?: "None selected"
                        SettingsItem(
                            icon = Icons.Default.SmartToy,
                            title = "Active Model",
                            subtitle = activeModelName,
                            onClick = { showModelDialog = true }
                        )
                        SettingsItem(
                            icon = Icons.Default.History,
                            title = "Agent Jobs & Audit Trail",
                            subtitle = "View background agent operations and provenance logs",
                            onClick = onAgentJobsClick
                        )
                        ModelDownloadSection(
                            viewModel = viewModel,
                            selectedModelId = selectedModelId
                        )
                    }
                }
            }

            // ── Speech Recognition section ──
            stickyHeader {
                CollapsibleSectionHeader(
                    title = "Speech Recognition",
                    isExpanded = speechExpanded,
                    onToggle = { speechExpanded = !speechExpanded }
                )
            }
            item {
                AnimatedVisibility(
                    visible = speechExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column {
                        Text(
                            text = "Download a model for offline speech-to-text. Works without internet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        SttLanguageSelector(
                            selectedLanguage = sttLanguage,
                            onLanguageSelected = { viewModel.setSttLanguage(it) },
                            hasMultilingualModel = selectedSttModelId != null && selectedSttModelId != "moonshine_tiny_en"
                        )
                        SttAccuracyModeSelector(
                            selectedMode = sttAccuracyMode,
                            onModeSelected = { viewModel.setSttAccuracyMode(it) }
                        )
                        SttModelDownloadSection(
                            viewModel = viewModel,
                            selectedModelId = selectedSttModelId
                        )
                    }
                }
            }

            // ── Semantic Search section ──
            stickyHeader {
                CollapsibleSectionHeader(
                    title = "Semantic Search",
                    isExpanded = semanticExpanded,
                    onToggle = { semanticExpanded = !semanticExpanded }
                )
            }
            item {
                AnimatedVisibility(
                    visible = semanticExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column {
                        Text(
                            text = "Download an embedding model to find notes by meaning, not just keywords. Without one, search and Q&A fall back to keyword matching.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        EmbeddingModelDownloadSection(
                            viewModel = viewModel,
                            selectedModelId = selectedEmbeddingModelId
                        )
                    }
                }
            }

            // ── Privacy & Security section ──
            stickyHeader {
                CollapsibleSectionHeader(
                    title = "Privacy & Security",
                    isExpanded = privacyExpanded,
                    onToggle = { privacyExpanded = !privacyExpanded }
                )
            }
            item {
                AnimatedVisibility(
                    visible = privacyExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column {
                        SettingsItem(
                            icon = Icons.Default.Security,
                            title = "Privacy Controls",
                            subtitle = "Local processing, data retention, export & delete",
                            onClick = onPrivacyClick
                        )
                    }
                }
            }

            // ── About section ──
            stickyHeader {
                CollapsibleSectionHeader(
                    title = "About",
                    isExpanded = aboutExpanded,
                    onToggle = { aboutExpanded = !aboutExpanded }
                )
            }
            item {
                AnimatedVisibility(
                    visible = aboutExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column {
                        SettingsItem(
                            icon = Icons.Default.Update,
                            title = "What's New",
                            subtitle = "See app updates and features",
                            onClick = onFeedClick
                        )
                        SettingsItem(
                            icon = Icons.Default.Info,
                            title = "Version",
                            subtitle = "1.0.0"
                        )
                    }
                }
            }
        }
    }

    // Theme dialog
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

    // Model selection dialog
    if (showModelDialog) {
        val downloadedModels = viewModel.downloadedModels.collectAsState()
        ModelSelectionDialog(
            selectedModelId = selectedModelId,
            downloadedModels = downloadedModels.value.filter { it.isDownloaded },
            onModelSelected = { modelId ->
                viewModel.selectModel(modelId)
                showModelDialog = false
            },
            onDismiss = { showModelDialog = false }
        )
    }
}

@Composable
fun CollapsibleSectionHeader(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        )
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
        title = { Text("Select Theme") },
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
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ModelSelectionDialog(
    selectedModelId: String,
    downloadedModels: List<com.omnidocs.app.ai.ModelInfo>,
    onModelSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Active Model") },
        text = {
            Column {
                if (downloadedModels.isEmpty()) {
                    Text(
                        text = "No models downloaded yet. Download a model below to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    downloadedModels.forEach { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onModelSelected(model.id) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedModelId == model.id,
                                onClick = { onModelSelected(model.id) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = model.name,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = model.size,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ModelDownloadSection(
    viewModel: SettingsViewModel,
    selectedModelId: String
) {
    val models = viewModel.downloadedModels.collectAsState()
    val downloadState = viewModel.downloadState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        viewModel.modelDownloadManager.availableModels.forEach { model ->
            val isDownloaded = models.value.any { it.id == model.id && it.isDownloaded }
            val isSelected = model.id == selectedModelId
            val isDownloading = when (val state = downloadState.value) {
                is com.omnidocs.app.ai.DownloadState.Downloading -> state.modelId == model.id
                else -> false
            }
            val progress = when (val state = downloadState.value) {
                is com.omnidocs.app.ai.DownloadState.Downloading -> state.progress
                else -> 0f
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected && isDownloaded)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = model.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Size: ${model.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isDownloaded) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Active",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Downloaded",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (isDownloading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Downloading: ${progress.toInt()}%",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    // Show error if download failed for this model
                    val downloadError = when (val state = downloadState.value) {
                        is com.omnidocs.app.ai.DownloadState.Error -> {
                            if (state.modelId == model.id) state.message else null
                        }
                        else -> null
                    }
                    downloadError?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Error: $error",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Show model status
                    val isModelReady = isDownloaded && viewModel.isModelReady() && isSelected
                    if (isDownloaded) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isModelReady) Icons.Default.CheckCircle else Icons.Default.Info,
                                contentDescription = null,
                                tint = if (isModelReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = when {
                                    isModelReady -> "Active"
                                    isSelected -> "Selected (loading...)"
                                    else -> "Downloaded"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isModelReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (isDownloaded) {
                            if (!isSelected) {
                                TextButton(
                                    onClick = { viewModel.selectModel(model.id) }
                                ) {
                                    Text("Select")
                                }
                            }
                            TextButton(
                                onClick = { viewModel.deleteModel(model) }
                            ) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            TextButton(
                                onClick = { viewModel.downloadModel(model) },
                                enabled = !isDownloading
                            ) {
                                Text("Download")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SttModelDownloadSection(
    viewModel: SettingsViewModel,
    selectedModelId: String?
) {
    val models = viewModel.sttDownloadedModels.collectAsState()
    val downloadState = viewModel.sttDownloadState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // System recognizer option
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (selectedModelId == null)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.selectSttModel(null) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedModelId == null,
                    onClick = { viewModel.selectSttModel(null) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("System Recognizer", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Uses Google speech services (requires internet)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Downloaded and available STT models
        viewModel.modelDownloadManager.sttModels.forEach { model ->
            val isDownloaded = models.value.any { it.id == model.id && it.isDownloaded }
            val isSelected = model.id == selectedModelId
            val isDownloading = when (val state = downloadState.value) {
                is com.omnidocs.app.ai.DownloadState.Downloading -> state.modelId == model.id
                else -> false
            }
            val progress = when (val state = downloadState.value) {
                is com.omnidocs.app.ai.DownloadState.Downloading -> state.progress
                else -> 0f
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected && isDownloaded)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.name, style = MaterialTheme.typography.titleMedium)
                            Text(model.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Size: ${model.size}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Languages: ${model.languages.joinToString(", ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isDownloaded && isSelected) {
                            Icon(Icons.Default.CheckCircle, "Active",
                                tint = MaterialTheme.colorScheme.primary)
                        } else if (isDownloaded) {
                            Icon(Icons.Default.Check, "Downloaded",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    if (isDownloading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth())
                        Text("Downloading: ${progress.toInt()}%",
                            style = MaterialTheme.typography.labelSmall)
                    }

                    val downloadError = when (val state = downloadState.value) {
                        is com.omnidocs.app.ai.DownloadState.Error ->
                            if (state.modelId == model.id) state.message else null
                        else -> null
                    }
                    downloadError?.let { error ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Error: $error", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (isDownloaded) {
                            if (!isSelected) {
                                TextButton(onClick = { viewModel.selectSttModel(model.id) }) {
                                    Text("Select")
                                }
                            }
                            TextButton(onClick = { viewModel.deleteSttModel(model) }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            TextButton(onClick = { viewModel.downloadSttModel(model) },
                                enabled = !isDownloading) {
                                Text("Download")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmbeddingModelDownloadSection(
    viewModel: SettingsViewModel,
    selectedModelId: String?
) {
    val models = viewModel.embeddingDownloadedModels.collectAsState()
    val downloadState = viewModel.embeddingDownloadState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        viewModel.modelDownloadManager.embeddingModels.forEach { model ->
            val isDownloaded = models.value.any { it.id == model.id && it.isDownloaded }
            val isSelected = model.id == selectedModelId
            val isDownloading = when (val state = downloadState.value) {
                is com.omnidocs.app.ai.DownloadState.Downloading -> state.modelId == model.id
                else -> false
            }
            val progress = when (val state = downloadState.value) {
                is com.omnidocs.app.ai.DownloadState.Downloading -> state.progress
                else -> 0f
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected && isDownloaded)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.name, style = MaterialTheme.typography.titleMedium)
                            Text(model.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Size: ${model.size}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isDownloaded && isSelected) {
                            Icon(Icons.Default.CheckCircle, "Active",
                                tint = MaterialTheme.colorScheme.primary)
                        } else if (isDownloaded) {
                            Icon(Icons.Default.Check, "Downloaded",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    if (isDownloading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth())
                        Text("Downloading: ${progress.toInt()}%",
                            style = MaterialTheme.typography.labelSmall)
                    }

                    val downloadError = when (val state = downloadState.value) {
                        is com.omnidocs.app.ai.DownloadState.Error ->
                            if (state.modelId == model.id) state.message else null
                        else -> null
                    }
                    downloadError?.let { error ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Error: $error", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (isDownloaded) {
                            if (!isSelected) {
                                TextButton(onClick = { viewModel.selectEmbeddingModel(model.id) }) {
                                    Text("Select")
                                }
                            }
                            TextButton(onClick = { viewModel.deleteEmbeddingModel(model) }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            TextButton(onClick = { viewModel.downloadEmbeddingModel(model) },
                                enabled = !isDownloading) {
                                Text("Download")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Supported language options for Whisper multilingual models. */
private val STT_LANGUAGES = listOf(
    "" to "Auto-detect",
    "en" to "English",
    "ms" to "Malay",
    "zh" to "Chinese",
    "ja" to "Japanese",
    "ko" to "Korean",
    "es" to "Spanish",
    "ar" to "Arabic",
    "vi" to "Vietnamese",
    "uk" to "Ukrainian",
)

@Composable
fun SttLanguageSelector(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    hasMultilingualModel: Boolean,
    modifier: Modifier = Modifier
) {
    if (!hasMultilingualModel) return

    var expanded by remember { mutableStateOf(false) }
    val displayLabel = STT_LANGUAGES.find { it.first == selectedLanguage }?.second ?: "Auto-detect"

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = "Transcription language",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box {
            OutlinedTextField(
                value = displayLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = {
                    Icon(
                        if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = null
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
            // Invisible overlay to capture clicks on the entire field
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { expanded = true }
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            STT_LANGUAGES.forEach { (code, name) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = name,
                            fontWeight = if (code == selectedLanguage) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        onLanguageSelected(code)
                        expanded = false
                    },
                    leadingIcon = {
                        if (code == selectedLanguage) {
                            Icon(Icons.Default.Check, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
            }
        }
    }
}

/** Recognition mode options for offline STT. */
private val STT_ACCURACY_MODES = listOf(
    "fast" to "Fast",
    "accurate" to "Higher accuracy",
)

@Composable
fun SttAccuracyModeSelector(
    selectedMode: String,
    onModeSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val displayLabel = STT_ACCURACY_MODES.find { it.first == selectedMode }?.second ?: "Fast"

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = "Recognition mode",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box {
            OutlinedTextField(
                value = displayLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = {
                    Icon(
                        if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = null
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
            // Invisible overlay to capture clicks on the entire field
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { expanded = true }
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Fast decodes shorter segments for snappier results. Higher accuracy uses more audio context per decode for better word accuracy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            STT_ACCURACY_MODES.forEach { (mode, name) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = name,
                            fontWeight = if (mode == selectedMode) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        onModeSelected(mode)
                        expanded = false
                    },
                    leadingIcon = {
                        if (mode == selectedMode) {
                            Icon(Icons.Default.Check, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
            }
        }
    }
}
