package com.omnidocs.app.ui.screens.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omnidocs.app.domain.model.AppUpdate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onNavigateBack: () -> Unit
) {
    val updates = remember { getAppUpdates() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("What's New") },
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // App version header
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "OmniDocs",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Version 1.0.0",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.NoteAlt,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Updates header
            item {
                Text(
                    text = "Recent Updates",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Update items
            items(updates) { update ->
                UpdateCard(update = update)
            }
        }
    }
}

private fun getAppUpdates(): List<AppUpdate> {
    return listOf(
        AppUpdate(
            version = "1.0.0",
            title = "Welcome to OmniDocs!",
            description = "Your new note-taking companion is here. Create, edit, and organize your notes with powerful features including OCR scanning, AI-powered assistance, and beautiful themes.",
            date = "July 9, 2026",
            isNew = true
        ),
        AppUpdate(
            version = "1.0.0",
            title = "Rich Text Editor",
            description = "Format your notes with bold, italic, lists, headings, quotes, and code blocks. Your notes are stored as HTML for maximum flexibility.",
            date = "July 9, 2026"
        ),
        AppUpdate(
            version = "1.0.0",
            title = "OCR Scanner",
            description = "Extract text from images and documents using your camera or gallery. Supports English and Chinese text recognition.",
            date = "July 9, 2026"
        ),
        AppUpdate(
            version = "1.0.0",
            title = "AI Assistant",
            description = "On-device AI for offline text summarization, proofreading, and rewriting. Works in English and Bahasa Melayu.",
            date = "July 9, 2026"
        ),
        AppUpdate(
            version = "1.0.0",
            title = "Customizable Themes",
            description = "Choose from 7 beautiful themes: Light, Dark, AMOLED, Sepia, Ocean, Forest, and Lavender. Personalize your note-taking experience.",
            date = "July 9, 2026"
        ),
        AppUpdate(
            version = "1.0.0",
            title = "Cloud Sync",
            description = "Sign in with your Google account to sync notes across devices. Your notes are securely backed up to Firebase.",
            date = "July 9, 2026"
        )
    )
}

@Composable
fun UpdateCard(update: AppUpdate) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = update.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (update.isNew) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                text = "NEW",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "v${update.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = update.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = update.date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

