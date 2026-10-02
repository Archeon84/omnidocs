package com.omnidocs.app.ui.screens.welcome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.omnidocs.app.ai.DownloadState
import com.omnidocs.app.ai.ModelInfo
import com.omnidocs.app.stt.SttModelInfo
import kotlinx.coroutines.launch

private const val TOTAL_PAGES = 5

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WelcomeScreen(
    onFinish: () -> Unit,
    viewModel: WelcomeViewModel = hiltViewModel()
) {
    val pagerState = rememberPagerState(initialPage = 0) { TOTAL_PAGES }
    val coroutineScope = rememberCoroutineScope()

    val downloadedModels by viewModel.downloadedModels.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()

    val downloadedEmbeddingModels by viewModel.downloadedEmbeddingModels.collectAsState()
    val embeddingDownloadState by viewModel.embeddingDownloadState.collectAsState()

    val downloadedSttModels by viewModel.downloadedSttModels.collectAsState()
    val sttDownloadState by viewModel.sttDownloadState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Text(
                            text = "OmniDocs",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                actions = {
                    if (pagerState.currentPage < TOTAL_PAGES - 1) {
                        TextButton(
                            onClick = {
                                viewModel.completeOnboarding()
                                onFinish()
                            }
                        ) {
                            Text(
                                "Skip",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back / Previous Button
                    if (pagerState.currentPage > 0) {
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Previous page",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(48.dp))
                    }

                    // Expanding Pill Page Indicator
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(TOTAL_PAGES) { index ->
                            val isSelected = pagerState.currentPage == index
                            val targetWidth by animateDpAsState(
                                targetValue = if (isSelected) 24.dp else 8.dp,
                                animationSpec = tween(300),
                                label = "indicatorWidth"
                            )
                            val color by animateColorAsState(
                                targetValue = if (isSelected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                animationSpec = tween(300),
                                label = "indicatorColor"
                            )

                            Box(
                                modifier = Modifier
                                    .height(8.dp)
                                    .width(targetWidth)
                                    .clip(CircleShape)
                                    .background(color)
                                    .clickable {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(index)
                                        }
                                    }
                            )
                        }
                    }

                    // Next or Finish Button
                    if (pagerState.currentPage < TOTAL_PAGES - 1) {
                        FilledTonalButton(
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Next", fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                viewModel.completeOnboarding()
                                onFinish()
                            },
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Get Started", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) { pageIndex ->
            when (pageIndex) {
                0 -> WelcomeFeaturesShowcasePage(
                    onStartTutorial = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(1)
                        }
                    }
                )
                1 -> TutorialLlmSetupPage(
                    viewModel = viewModel,
                    downloadedModels = downloadedModels,
                    downloadState = downloadState
                )
                2 -> TutorialEmbeddingSetupPage(
                    viewModel = viewModel,
                    downloadedModels = downloadedEmbeddingModels,
                    downloadState = embeddingDownloadState
                )
                3 -> TutorialSttSetupPage(
                    viewModel = viewModel,
                    downloadedModels = downloadedSttModels,
                    downloadState = sttDownloadState
                )
                4 -> QuickStartChecklistPage(
                    onGetStarted = {
                        viewModel.completeOnboarding()
                        onFinish()
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Page 0: Feature Showcase & Value Proposition
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun WelcomeFeaturesShowcasePage(
    onStartTutorial: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Hero Header Badge
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "100% PRIVATE • AIR-GAPPED ON-DEVICE AI",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Text(
            text = "Welcome to OmniDocs",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Your private, evidence-first AI knowledge workspace. All intelligence, embeddings, and data stay on your device.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "TOP BEST FEATURES",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 8.dp)
        )

        FeatureHighlightCard(
            icon = Icons.Default.SmartToy,
            iconTint = MaterialTheme.colorScheme.primary,
            title = "On-Device LLM Intelligence",
            description = "Run Google Gemma 4 models directly on your hardware for Q&A, automatic summaries, and key insight extraction without cloud latency or subscription."
        )

        Spacer(modifier = Modifier.height(10.dp))

        FeatureHighlightCard(
            icon = Icons.Default.Search,
            iconTint = MaterialTheme.colorScheme.tertiary,
            title = "Semantic Vector Search",
            description = "Search your notes by concept and meaning using multilingual neural embeddings. Even if you forget the exact words, OmniDocs finds the right note."
        )

        Spacer(modifier = Modifier.height(10.dp))

        FeatureHighlightCard(
            icon = Icons.Default.Mic,
            iconTint = Color(0xFFE65100),
            title = "Offline Audio & Voice Notes",
            description = "Transcribe lectures, meetings, and voice thoughts into rich Markdown with embedded offline Whisper or fast Moonshine recognition."
        )

        Spacer(modifier = Modifier.height(10.dp))

        FeatureHighlightCard(
            icon = Icons.Default.DocumentScanner,
            iconTint = Color(0xFF2E7D32),
            title = "Live OCR & Smart Scanner",
            description = "Scan physical paper documents, receipts, and book pages with the camera directly into editable text notes with ML Kit OCR."
        )

        Spacer(modifier = Modifier.height(10.dp))

        FeatureHighlightCard(
            icon = Icons.Default.AccountTree,
            iconTint = MaterialTheme.colorScheme.secondary,
            title = "Knowledge Graph & Spaced Study",
            description = "Explore interactive visual connections between linked notes, and master knowledge with auto-generated spaced-repetition flashcards."
        )

        Spacer(modifier = Modifier.height(20.dp))

        FilledTonalButton(
            onClick = onStartTutorial,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Explore Setup Mini-Tutorial", fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Page 1: Mini Tutorial • Step 1: On-Device LLM
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TutorialLlmSetupPage(
    viewModel: WelcomeViewModel,
    downloadedModels: List<ModelInfo>,
    downloadState: DownloadState
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TutorialStepBadge(step = 1, totalSteps = 3, category = "ON-DEVICE LLM")

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Download On-Device LLM",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "OmniDocs runs open-weights language models completely offline using Google LiteRT-LM. No servers, no data leakage, and zero recurring fees.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        TutorialInfoCard(
            icon = Icons.Default.Lightbulb,
            title = "What Does the LLM Enable?",
            bulletPoints = listOf(
                "Ask Notes: Ask questions across all your notes with exact citation evidence.",
                "Offline Chat: Air-gapped private conversational assistant.",
                "Auto-Summarize: Generate concise TL;DRs and extract action items instantly."
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "RECOMMENDED MODEL",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 8.dp)
        )

        viewModel.availableLlmModels.forEach { model ->
            val isDownloaded = downloadedModels.any { it.id == model.id && it.isDownloaded }
            val isDownloading = when (downloadState) {
                is DownloadState.Downloading -> downloadState.modelId == model.id
                else -> false
            }
            val progress = when (downloadState) {
                is DownloadState.Downloading -> if (downloadState.modelId == model.id) downloadState.progress else 0f
                else -> 0f
            }
            val error = when (downloadState) {
                is DownloadState.Error -> if (downloadState.modelId == model.id) downloadState.message else null
                else -> null
            }

            ModelDownloadTutorialCard(
                title = model.name,
                size = model.size,
                description = model.description,
                isDownloaded = isDownloaded,
                isDownloading = isDownloading,
                progress = progress,
                error = error,
                onDownload = { viewModel.downloadModel(model) }
            )

            Spacer(modifier = Modifier.height(10.dp))
        }

        Text(
            text = "Tip: You can start downloading now or anytime later in Settings > On-Device AI.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Page 2: Mini Tutorial • Step 2: Semantic Vector Embeddings
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TutorialEmbeddingSetupPage(
    viewModel: WelcomeViewModel,
    downloadedModels: List<ModelInfo>,
    downloadState: DownloadState
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TutorialStepBadge(step = 2, totalSteps = 3, category = "VECTOR EMBEDDINGS")

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Enable Semantic Search",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Traditional search only matches exact words. Embeddings convert your notes into mathematical concepts so you can search by meaning.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Visual Illustration Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("How Semantic Search Works", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• Search Query: \"preparing for travel\"\n• Retrieved Note: \"Flight tickets booked, pack passports & power adapter\"\n• Result: Found even though the words \"travel\" or \"preparing\" never appeared!",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "RECOMMENDED EMBEDDING MODEL",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 8.dp)
        )

        viewModel.availableEmbeddingModels.forEach { model ->
            val isDownloaded = downloadedModels.any { it.id == model.id && it.isDownloaded }
            val isDownloading = when (downloadState) {
                is DownloadState.Downloading -> downloadState.modelId == model.id
                else -> false
            }
            val progress = when (downloadState) {
                is DownloadState.Downloading -> if (downloadState.modelId == model.id) downloadState.progress else 0f
                else -> 0f
            }
            val error = when (downloadState) {
                is DownloadState.Error -> if (downloadState.modelId == model.id) downloadState.message else null
                else -> null
            }

            ModelDownloadTutorialCard(
                title = model.name,
                size = model.size,
                description = model.description,
                isDownloaded = isDownloaded,
                isDownloading = isDownloading,
                progress = progress,
                error = error,
                onDownload = { viewModel.downloadEmbeddingModel(model) }
            )

            Spacer(modifier = Modifier.height(10.dp))
        }

        Text(
            text = "Compact size (~126 MB). Runs locally via USearch vector index for instantaneous search across thousands of notes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Page 3: Mini Tutorial • Step 3: Offline Speech-to-Text
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TutorialSttSetupPage(
    viewModel: WelcomeViewModel,
    downloadedModels: List<SttModelInfo>,
    downloadState: DownloadState
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TutorialStepBadge(step = 3, totalSteps = 3, category = "VOICE & TRANSCRIPTION")

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Offline Voice & Transcription",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Record lectures, meetings, and thoughts on the go. Spoken audio is transcribed directly into structured notes on your device.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // System recognizer card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Android System Recognizer", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Text("Pre-configured and ready immediately without downloading extra models.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "OPTIONAL OFFLINE STT ENGINES",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 8.dp)
        )

        // Moonshine Tiny card
        val moonshine = viewModel.availableSttModels.find { it.id == "moonshine_tiny_en" }
        if (moonshine != null) {
            val isDownloaded = downloadedModels.any { it.id == moonshine.id && it.isDownloaded }
            val isDownloading = when (downloadState) {
                is DownloadState.Downloading -> downloadState.modelId == moonshine.id
                else -> false
            }
            val progress = when (downloadState) {
                is DownloadState.Downloading -> if (downloadState.modelId == moonshine.id) downloadState.progress else 0f
                else -> 0f
            }
            val error = when (downloadState) {
                is DownloadState.Error -> if (downloadState.modelId == moonshine.id) downloadState.message else null
                else -> null
            }

            ModelDownloadTutorialCard(
                title = moonshine.name,
                size = moonshine.size,
                description = "${moonshine.description} • 100% offline transcription",
                isDownloaded = isDownloaded,
                isDownloading = isDownloading,
                progress = progress,
                error = error,
                onDownload = { viewModel.downloadSttModel(moonshine) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "You can also choose high-accuracy Whisper Multilingual (99 languages) anytime in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Page 4: Ready to Start Checklist
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun QuickStartChecklistPage(
    onGetStarted: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF2E7D32).copy(alpha = 0.15f),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Verified, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(16.dp))
                Text(
                    text = "YOU'RE ALL SET",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2E7D32)
                )
            }
        }

        Text(
            text = "Unlock Your Second Brain",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "OmniDocs is configured to protect your thoughts with zero telemetry and military-grade encryption.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        QuickTipCard(
            stepNumber = "1",
            icon = Icons.Default.EditNote,
            title = "Create or Scan Notes",
            description = "Tap '+' to start a new note, select meeting/study templates, or tap the camera icon to OCR scan paper documents."
        )

        Spacer(modifier = Modifier.height(10.dp))

        QuickTipCard(
            stepNumber = "2",
            icon = Icons.Default.AutoAwesome,
            title = "Ask AI & Synthesize",
            description = "Use Ask Notes to query all your writings simultaneously. The AI answers strictly backed by cited snippets from your notes."
        )

        Spacer(modifier = Modifier.height(10.dp))

        QuickTipCard(
            stepNumber = "3",
            icon = Icons.Default.Settings,
            title = "Manage Models & Backups",
            description = "Revisit model downloads, configure biometric App Lock, or connect encrypted Google Drive backups anytime in Settings."
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onGetStarted,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Enter OmniDocs Workspace", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable UI Components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TutorialStepBadge(step: Int, totalSteps: Int, category: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
    ) {
        Text(
            text = "MINI TUTORIAL • STEP $step OF $totalSteps • $category",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun FeatureHighlightCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = iconTint.copy(alpha = 0.12f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun TutorialInfoCard(
    icon: ImageVector,
    title: String,
    bulletPoints: List<String>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            bulletPoints.forEach { point ->
                Text(
                    text = "• $point",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ModelDownloadTutorialCard(
    title: String,
    size: String,
    description: String,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    progress: Float,
    error: String?,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDownloaded)
                Color(0xFF2E7D32).copy(alpha = 0.08f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        border = if (isDownloaded)
            androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E7D32).copy(alpha = 0.3f))
        else null
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = size,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isDownloading) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Downloading in background: ${progress.toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            } else if (error != null) {
                Text(
                    text = "Error: $error",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(6.dp))
                FilledTonalButton(
                    onClick = onDownload,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Retry Download")
                }
            } else if (isDownloaded) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Installed",
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Installed & Ready to Use",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )
                }
            } else {
                Button(
                    onClick = onDownload,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download Model ($size)", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun QuickTipCard(
    stepNumber: String,
    icon: ImageVector,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stepNumber,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
