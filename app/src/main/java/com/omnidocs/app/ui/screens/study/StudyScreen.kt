package com.omnidocs.app.ui.screens.study

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.omnidocs.app.study.Flashcard
import com.omnidocs.app.study.StudyCardType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    onNavigateBack: () -> Unit,
    viewModel: StudyViewModel = hiltViewModel()
) {
    val deck by viewModel.deck.collectAsState()
    val activeQueue by viewModel.activeQueue.collectAsState()
    val currentIndex by viewModel.currentCardIndex.collectAsState()
    val isFlipped by viewModel.isFlipped.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val summary by viewModel.reviewSummary.collectAsState()
    var showExportMenu by remember { mutableStateOf(false) }
    var selectedOptionIndex by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current

    // Reset option selection when advancing to next card
    LaunchedEffect(currentIndex) {
        selectedOptionIndex = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Study & Practice", fontWeight = FontWeight.Bold)
                        deck?.let {
                            Text(
                                text = it.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (deck != null) {
                        Box {
                            IconButton(onClick = { showExportMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                            }
                            DropdownMenu(
                                expanded = showExportMenu,
                                onDismissRequest = { showExportMenu = false }
                            ) {
                                if (viewModel.noteId != null) {
                                    DropdownMenuItem(
                                        text = { Text("Regenerate Cards") },
                                        onClick = {
                                            showExportMenu = false
                                            viewModel.regenerateDeck()
                                        },
                                        leadingIcon = { Icon(Icons.Default.Refresh, null) }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Export Markdown (.md)") },
                                    onClick = {
                                        showExportMenu = false
                                        viewModel.exportDeckMarkdown(context)
                                    },
                                    leadingIcon = { Icon(Icons.Default.Description, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Export Anki (.txt)") },
                                    onClick = {
                                        showExportMenu = false
                                        viewModel.exportDeckAnki(context)
                                    },
                                    leadingIcon = { Icon(Icons.Default.Code, null) }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Loading study flashcards...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                summary != null -> {
                    // Session Completed Summary
                    StudySummaryCard(
                        summary = summary!!,
                        onRestart = { viewModel.restartDeck() },
                        onDone = onNavigateBack
                    )
                }
                activeQueue.isNotEmpty() -> {
                    val cards = activeQueue
                    val card = cards.getOrNull(currentIndex) ?: cards.first()

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Progress Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "Card ${currentIndex + 1} of ${cards.size}",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }

                            LinearProgressIndicator(
                                progress = { (currentIndex + 1).toFloat() / cards.size.toFloat() },
                                modifier = Modifier
                                    .width(140.dp)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 3D Flip Card — Keyed by card.id to eliminate backwards flip animation glitch
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            key(card.id) {
                                FlipFlashcard(
                                    card = card,
                                    isFlipped = isFlipped,
                                    selectedOptionIndex = selectedOptionIndex,
                                    onSelectOption = { selectedOptionIndex = it },
                                    onFlip = { viewModel.flipCard() },
                                    fallbackTitle = deck?.title?.takeIf { !it.startsWith("Daily Practice") }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Rating controls
                        if (isFlipped) {
                            val againInterval = viewModel.getProjectedInterval(1)
                            val hardInterval = viewModel.getProjectedInterval(3)
                            val goodInterval = viewModel.getProjectedInterval(4)
                            val easyInterval = viewModel.getProjectedInterval(5)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Again
                                Button(
                                    onClick = { viewModel.rateCard(1) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Again", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        if (againInterval.isNotBlank()) {
                                            Text(againInterval, fontSize = 10.sp, color = Color.White.copy(alpha = 0.85f))
                                        }
                                    }
                                }
                                // Hard
                                Button(
                                    onClick = { viewModel.rateCard(3) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF57C00)),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Hard", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        if (hardInterval.isNotBlank()) {
                                            Text(hardInterval, fontSize = 10.sp, color = Color.White.copy(alpha = 0.85f))
                                        }
                                    }
                                }
                                // Good
                                Button(
                                    onClick = { viewModel.rateCard(4) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Good", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        if (goodInterval.isNotBlank()) {
                                            Text(goodInterval, fontSize = 10.sp, color = Color.White.copy(alpha = 0.85f))
                                        }
                                    }
                                }
                                // Easy
                                Button(
                                    onClick = { viewModel.rateCard(5) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Easy", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        if (easyInterval.isNotBlank()) {
                                            Text(easyInterval, fontSize = 10.sp, color = Color.White.copy(alpha = 0.85f))
                                        }
                                    }
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.flipCard() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Flip, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Tap Card or Button to Show Answer")
                            }
                        }
                    }
                }
                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.School,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (viewModel.noteId.isNullOrBlank()) {
                                "All caught up! No cards are due for practice."
                            } else {
                                "No study cards generated yet for this note."
                            },
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        if (viewModel.noteId != null) {
                            Button(onClick = { viewModel.regenerateDeck() }) {
                                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Generate Study Flashcards")
                            }
                        } else {
                            OutlinedButton(onClick = onNavigateBack) {
                                Text("Go to Notes")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FlipFlashcard(
    card: Flashcard,
    isFlipped: Boolean,
    selectedOptionIndex: Int?,
    onSelectOption: (Int) -> Unit,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier,
    fallbackTitle: String? = null
) {
    val rotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "cardFlip"
    )

    Card(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density
            }
            .clickable { onFlip() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (rotation <= 90f) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        if (rotation <= 90f) {
            // Front (Question & Interactive Options)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = card.type.name.replace("_", " "),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (card.isUncertain) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFF57C00).copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "UNCERTAIN",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF57C00)
                            )
                        }
                    }
                }

                // Question Prompt (with Cloze mask if applicable)
                val displayPrompt = if (card.type == StudyCardType.CLOZE) {
                    formatClozePrompt(card.prompt)
                } else {
                    card.prompt
                }

                Text(
                    text = displayPrompt,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                )

                // Interactive Multiple Choice Options
                if (card.options.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        card.options.forEachIndexed { idx, opt ->
                            val isSelected = selectedOptionIndex == idx
                            val surfaceColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                            val borderColor = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = surfaceColor,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                                    .clickable {
                                        onSelectOption(idx)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "${('A' + idx)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = opt,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "Tap to reveal answer ➔",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            // Back (Answer, Explanation, MCQ Feedback & Citation)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationY = 180f }
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "ANSWER",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = card.answer,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // MCQ result breakdown if options were present
                    if (card.options.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            card.options.forEachIndexed { idx, opt ->
                                val isCorrect = idx == card.correctOptionIndex
                                val isUserChoice = idx == selectedOptionIndex

                                val optionBg = when {
                                    isCorrect -> Color(0xFF2E7D32).copy(alpha = 0.15f)
                                    isUserChoice -> Color(0xFFD32F2F).copy(alpha = 0.15f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                }
                                val optionBorder = when {
                                    isCorrect -> Color(0xFF2E7D32)
                                    isUserChoice -> Color(0xFFD32F2F)
                                    else -> Color.Transparent
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = optionBg,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, optionBorder, RoundedCornerShape(8.dp))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isCorrect) "✓ " else if (isUserChoice) "✗ " else "• ",
                                            fontWeight = FontWeight.Bold,
                                            color = if (isCorrect) Color(0xFF2E7D32) else if (isUserChoice) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "${('A' + idx)}. $opt",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!card.explanation.isNullOrBlank()) {
                        Text(
                            text = card.explanation,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    val displaySourceTitle = (card.sourceTitle ?: fallbackTitle)?.takeIf { it.isNotBlank() }
                    val displaySnippet = card.sourceSnippet?.takeIf {
                        it.isNotBlank() &&
                            !it.contains("[Truncated", ignoreCase = true) &&
                            !it.contains("Truncated text", ignoreCase = true) &&
                            !it.contains("showing first", ignoreCase = true)
                    }

                    if (displaySourceTitle != null || displaySnippet != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                if (displaySourceTitle != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Description,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Source: $displaySourceTitle",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                if (displaySnippet != null) {
                                    if (displaySourceTitle != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                    }
                                    Text(
                                        text = "\"$displaySnippet\"",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "Rate your recall below to schedule next review",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun formatClozePrompt(rawPrompt: String): String {
    // Replaces {{c1::hidden text}} or {{hidden text}} with [ ... ]
    return rawPrompt
        .replace(Regex("""\{\{c\d+::(.*?)\}\}"""), "[ ... ]")
        .replace(Regex("""\{\{(.*?)\}\}"""), "[ ... ]")
}

@Composable
fun StudySummaryCard(
    summary: StudyReviewSummary,
    onRestart: () -> Unit,
    onDone: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF2E7D32),
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Session Complete!",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "You reviewed all ${summary.totalCards} cards.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Score breakdown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ScoreBadge("Easy", summary.easyCount, Color(0xFF2E7D32))
                ScoreBadge("Good", summary.goodCount, MaterialTheme.colorScheme.primary)
                ScoreBadge("Hard", summary.hardCount, Color(0xFFF57C00))
                ScoreBadge("Again", summary.againCount, Color(0xFFD32F2F))
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onRestart,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Restart")
                }
                Button(
                    onClick = onDone,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
fun ScoreBadge(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
