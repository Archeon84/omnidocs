package com.omnidocs.app.ui.screens.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.omnidocs.app.ui.screens.recordings.formatDurationMs
import com.omnidocs.app.ui.theme.isReducedMotionEnabled

@Composable
fun VoiceCaptureOverlay(
    onNoteCreated: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: VoiceCaptureViewModel = hiltViewModel()
) {
    val displayTranscript by viewModel.displayTranscript.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val liveAudioLevel by viewModel.liveAudioLevel.collectAsState()
    val error by viewModel.error.collectAsState()
    val isStructuring by viewModel.isStructuring.collectAsState()
    val savedNoteId by viewModel.savedNoteId.collectAsState()
    val engineType by viewModel.engineType.collectAsState()
    val elapsedMs by viewModel.elapsedMs.collectAsState()
    val hasRecorded by viewModel.hasRecorded.collectAsState()
    val selectedLanguageCode by viewModel.selectedLanguageCode.collectAsState()
    val reducedMotion = isReducedMotionEnabled()
    val context = LocalContext.current
    val transcriptScroll = rememberScrollState()

    // Keep the newest words visible as the transcript streams in.
    LaunchedEffect(displayTranscript) {
        transcriptScroll.animateScrollTo(transcriptScroll.maxValue)
    }

    // Permission handling
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            viewModel.startListening()
        }
    }

    // Navigate when note is saved
    LaunchedEffect(savedNoteId) {
        savedNoteId?.let {
            onNoteCreated(it)
            viewModel.dismiss()
        }
    }

    // Pulsing animation for recording indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Close button (top-right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = {
                    viewModel.dismiss()
                    onDismiss()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Status text
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when {
                        isStructuring -> "Structuring your note..."
                        isListening -> "Live Whisper Transcription"
                        displayTranscript.isBlank() && hasRecorded && error == null ->
                            "No speech detected — try again"
                        displayTranscript.isEmpty() -> "Tap to start recording"
                        else -> "Review your transcript"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isListening) {
                        Surface(
                            modifier = Modifier.size(8.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.error
                        ) {}
                        Text(
                            text = "Streaming live speech...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = formatDurationMs(elapsedMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            }
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = if (engineType == "system") "System speech" else "On-device Whisper",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Real-time audio waveform visualizer
            AudioWaveformVisualizer(
                audioLevel = liveAudioLevel,
                isListening = isListening,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Recording button
            Box(contentAlignment = Alignment.Center) {
                if (isListening && !reducedMotion) {
                    // Pulsing ring
                    Surface(
                        modifier = Modifier
                            .size(96.dp)
                            .scale(pulseScale),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                    ) {}
                }
                FilledIconButton(
                    onClick = {
                        if (isListening) {
                            viewModel.stopListening()
                        } else if (displayTranscript.isEmpty()) {
                            if (hasAudioPermission) {
                                viewModel.startListening()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isListening)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    ),
                    enabled = !isStructuring
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop
                            else Icons.Default.Mic,
                        contentDescription = if (isListening) "Stop recording" else "Start recording",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Language selector (before recording starts or between takes).
            // Same list and same persisted preference as Settings → Speech
            // Language; a tap here sticks everywhere.
            if (!isListening && !isStructuring) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    com.omnidocs.app.stt.STT_LANGUAGES.forEach { (code, label) ->
                        FilterChip(
                            selected = selectedLanguageCode == code,
                            onClick = { viewModel.setLanguage(code) },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Transcript area
            if (displayTranscript.isNotBlank() || error != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (error != null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = error!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = {
                                if (hasAudioPermission) {
                                    viewModel.startListening()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Try again")
                            }
                        }
                    } else if (isListening) {
                        // Streaming view: read-only + auto-scrolled to newest words
                        Text(
                            text = displayTranscript,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(transcriptScroll)
                                .padding(16.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    } else {
                        // Review view: editable so STT mistakes can be fixed before saving
                        OutlinedTextField(
                            value = displayTranscript,
                            onValueChange = viewModel::updateEditedTranscript,
                            label = { Text("Transcript (tap to edit)") },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            } else {
                // Empty state
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.MicOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Tap the microphone to start",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons
            if (displayTranscript.isNotBlank() && !isListening && !isStructuring) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (hasAudioPermission) {
                                viewModel.startListening()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Record More")
                    }
                    Button(
                        onClick = { viewModel.confirmAndSave() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Note")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = { viewModel.startOver() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Start over")
                    }
                    TextButton(
                        onClick = { viewModel.saveRaw() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save raw text")
                    }
                }
            }

            if (isStructuring) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 32.dp)
                    )
                    IconButton(onClick = { viewModel.cancelSaving() }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel structuring")
                    }
                }
            }
        }
    }
}

@Composable
fun AudioWaveformVisualizer(
    audioLevel: Float,
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
    val barCount = 15
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_anim")
    val animatedPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error

    Canvas(modifier = modifier) {
        val totalWidth = size.width
        val totalHeight = size.height
        val barWidth = 6.dp.toPx()
        val spacing = (totalWidth - (barCount * barWidth)) / (barCount - 1).coerceAtLeast(1)
        val minHeight = 4.dp.toPx()

        for (i in 0 until barCount) {
            val centerFactor = 1f - kotlin.math.abs(i - (barCount / 2)) / (barCount / 2f)
            val waveMod = if (isListening) {
                (kotlin.math.sin(animatedPhase + i * 0.45f) * 0.35f + 0.65f).toFloat()
            } else {
                0.15f
            }

            val dynamicHeight = if (isListening) {
                val level = (audioLevel * 0.8f + 0.2f) * centerFactor * waveMod
                (totalHeight * level).coerceIn(minHeight, totalHeight)
            } else {
                minHeight
            }

            val x = i * (barWidth + spacing)
            val y = (totalHeight - dynamicHeight) / 2f

            drawRoundRect(
                color = if (isListening) errorColor else primaryColor.copy(alpha = 0.3f),
                topLeft = Offset(x, y),
                size = Size(barWidth, dynamicHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}