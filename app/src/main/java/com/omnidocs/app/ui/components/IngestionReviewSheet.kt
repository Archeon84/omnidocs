package com.omnidocs.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class IngestionReviewState(
    val initialTitle: String,
    val initialTags: List<String>,
    val extractedTasks: List<String>,
    val pageCount: Int? = null,
    val language: String = "en",
    val characterCount: Int = 0
)

/**
 * Human-in-the-loop review card shown after document import or audio transcription.
 * Lets the user inspect, edit, and confirm AI-suggested title, tags, and tasks
 * before committing them into the workspace.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngestionReviewSheet(
    reviewState: IngestionReviewState,
    onConfirm: (confirmedTitle: String, confirmedTags: List<String>, selectedTasks: List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(reviewState.initialTitle) }
    var tags by remember { mutableStateOf(reviewState.initialTags) }
    var newTagInput by remember { mutableStateOf("") }
    val taskSelections = remember {
        mutableStateMapOf<Int, Boolean>().apply {
            reviewState.extractedTasks.indices.forEach { put(it, true) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .imePadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Review Ingested Content",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${if (reviewState.pageCount != null) "${reviewState.pageCount} pages · " else ""}${reviewState.characterCount} chars · ${reviewState.language.uppercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "AI PROPOSAL",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Title Input
                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Note Title") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                // 2. Tags Section
                item {
                    Column {
                        Text(
                            text = "Suggested Tags",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            tags.forEach { tag ->
                                InputChip(
                                    selected = true,
                                    onClick = { tags = tags - tag },
                                    label = { Text(tag) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove tag",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                // 3. Extracted Tasks Section
                if (reviewState.extractedTasks.isNotEmpty()) {
                    item {
                        Text(
                            text = "Detected Action Items (${reviewState.extractedTasks.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    itemsIndexed(reviewState.extractedTasks) { index, taskText ->
                        val isChecked = taskSelections[index] ?: true
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isChecked) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { taskSelections[index] = it }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = taskText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Discard")
                }
                Button(
                    onClick = {
                        val acceptedTasks = reviewState.extractedTasks.filterIndexed { index, _ ->
                            taskSelections[index] == true
                        }
                        onConfirm(title, tags, acceptedTasks)
                    },
                    modifier = Modifier.weight(1.5f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Accept & Save")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
