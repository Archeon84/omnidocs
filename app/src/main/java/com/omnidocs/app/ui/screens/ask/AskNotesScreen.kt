package com.omnidocs.app.ui.screens.ask

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.omnidocs.app.agent.Citation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskNotesScreen(
    onNavigateBack: () -> Unit,
    onSourceClick: (noteId: String, snippet: String?) -> Unit,
    viewModel: AskNotesViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val error by viewModel.error.collectAsState()
    val isDeviceWarm by viewModel.isDeviceWarm.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var selectedCitationForPreview by remember { mutableStateOf<Citation?>(null) }

    // Auto-scroll to the newest round as answers arrive.
    LaunchedEffect(messages.size, streamingText.length) {
        val last = messages.size - 1 + (if (isLoading) 1 else 0)
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Ask your notes",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Evidence-grounded RAG with source verification",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            if (messages.isEmpty() && error == null && !isLoading) {
                // Empty state explaining the feature.
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome, null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Evidence-Backed Knowledge Q&A",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Questions are answered using hybrid search, reciprocal rank fusion, and rigorous fact verification against your notes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(messages) { msg ->
                        AskRound(
                            message = msg,
                            onCitationClick = { citation ->
                                selectedCitationForPreview = citation
                            },
                            onRetry = { viewModel.retry(msg) },
                            onContinue = { viewModel.continueAnswer(msg) }
                        )
                    }
                    error?.let {
                        item {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    if (isLoading) {
                        item {
                            if (streamingText.isNotBlank()) {
                                // Live streaming answer
                                Card(
                                    shape = RoundedCornerShape(
                                        topStart = 4.dp, topEnd = 14.dp,
                                        bottomStart = 14.dp, bottomEnd = 14.dp
                                    ),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Text(
                                        text = streamingText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Retrieving evidence and verifying claims...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Warm-device notice: a long spinner reads as "warm phone", not "broken".
            if (isDeviceWarm) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning, null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Device is warm — answers may be slower than usual",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Input row
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask a question...") },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                if (isLoading) {
                    FilledIconButton(
                        onClick = { viewModel.stop() },
                        enabled = true
                    ) {
                        Icon(Icons.Default.Stop, "Stop")
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            if (input.isNotBlank() && !isLoading) {
                                viewModel.ask(input)
                                input = ""
                            }
                        },
                        enabled = input.isNotBlank() && !isLoading
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }

    selectedCitationForPreview?.let { citation ->
        CitationPreviewBottomSheet(
            citation = citation,
            onJumpToNote = {
                val targetNoteId = citation.noteId
                val targetSnippet = citation.quoteSnippet
                selectedCitationForPreview = null
                onSourceClick(targetNoteId, targetSnippet)
            },
            onDismiss = {
                selectedCitationForPreview = null
            }
        )
    }
}

@Composable
private fun AskRound(
    message: GroundedAskMessage,
    onCitationClick: (Citation) -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Question bubble (right-aligned)
        Surface(
            shape = RoundedCornerShape(
                topStart = 14.dp, topEnd = 14.dp,
                bottomStart = 14.dp, bottomEnd = 4.dp
            ),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(0.9f).align(Alignment.End)
        ) {
            Text(
                message.question,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp)
            )
        }

        // Answer card (left-aligned)
        Card(
            shape = RoundedCornerShape(
                topStart = 4.dp, topEnd = 14.dp,
                bottomStart = 14.dp, bottomEnd = 14.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (message.insufficientEvidence) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Insufficient evidence",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "Insufficient Evidence",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        } else if (message.isVerified) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Verified",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Verified Evidence",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Surface(
                        color = when (message.confidence) {
                            "HIGH" -> MaterialTheme.colorScheme.primaryContainer
                            "MEDIUM" -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${message.confidence} CONFIDENCE",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                ClickableAnswerText(
                    message = message,
                    onCitationClick = onCitationClick
                )
                if (message.ruleBased) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Quoted from your notes — no AI model downloaded",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (message.answerIncomplete && !message.insufficientEvidence) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Incomplete answer",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Answer was cut short (${message.stopReason})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onContinue) {
                            Text("Continue")
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = onRetry) {
                            Text("Retry")
                        }
                    }
                }
            }
        }

        // Citations & Sources (tappable → preview and open the note), ordered by first
        // mention so card numbers match the answer's [Source N] labels.
        if (message.citations.isNotEmpty()) {
            Text(
                text = "Citations (${message.citations.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            message.citations.sortedBy { it.sourceIndex }.forEach { citation ->
                CitationItem(citation = citation, onClick = { onCitationClick(citation) })
            }
        }
    }
}

internal val CITATION_INLINE_REGEX = Regex(
    """\[(?:Sources?\s*:?\s*(\d+)(?:\s*[-–]\s*(\d+))?(?::[^\]]+)?|(\d{1,2}(?:\s*,\s*\d{1,2})*))\]""",
    RegexOption.IGNORE_CASE
)

private sealed class AnswerInlineToken(val start: Int, val end: Int) {
    class CitationToken(
        start: Int,
        end: Int,
        val citation: Citation,
        val text: String
    ) : AnswerInlineToken(start, end)

    class BoldToken(
        start: Int,
        end: Int,
        val content: String
    ) : AnswerInlineToken(start, end)

    class CodeToken(
        start: Int,
        end: Int,
        val content: String
    ) : AnswerInlineToken(start, end)

    class ItalicToken(
        start: Int,
        end: Int,
        val content: String
    ) : AnswerInlineToken(start, end)
}

internal fun buildAnnotatedAnswer(
    answer: String,
    citations: List<Citation>,
    primaryColor: androidx.compose.ui.graphics.Color,
    containerColor: androidx.compose.ui.graphics.Color,
    headingColor: androidx.compose.ui.graphics.Color = primaryColor,
    bulletColor: androidx.compose.ui.graphics.Color = primaryColor
): AnnotatedString {
    return buildAnnotatedString {
        val lines = answer.split('\n')
        for ((lineIdx, rawLine) in lines.withIndex()) {
            if (lineIdx > 0) {
                append("\n")
            }

            val trimmed = rawLine.trimStart()
            when {
                trimmed.startsWith("### ") -> {
                    val headingText = trimmed.removePrefix("### ").trim()
                    pushStyle(
                        SpanStyle(
                            color = headingColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    )
                    renderLineTokens(headingText, citations, primaryColor, containerColor)
                    pop()
                }
                trimmed.startsWith("## ") -> {
                    val headingText = trimmed.removePrefix("## ").trim()
                    pushStyle(
                        SpanStyle(
                            color = headingColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    )
                    renderLineTokens(headingText, citations, primaryColor, containerColor)
                    pop()
                }
                trimmed.startsWith("# ") -> {
                    val headingText = trimmed.removePrefix("# ").trim()
                    pushStyle(
                        SpanStyle(
                            color = headingColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    )
                    renderLineTokens(headingText, citations, primaryColor, containerColor)
                    pop()
                }
                trimmed.startsWith("> ") -> {
                    val quoteText = trimmed.removePrefix("> ").trim()
                    pushStyle(SpanStyle(color = bulletColor, fontWeight = FontWeight.Bold))
                    append("▍ ")
                    pop()
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    renderLineTokens(quoteText, citations, primaryColor, containerColor)
                    pop()
                }
                trimmed.matches(Regex("""^[-*+]\s+.*""")) -> {
                    val bulletMatch = Regex("""^[-*+]\s+""").find(trimmed)!!
                    val bulletContent = trimmed.substring(bulletMatch.range.last + 1)
                    pushStyle(SpanStyle(color = bulletColor, fontWeight = FontWeight.Bold))
                    append("  • ")
                    pop()
                    renderLineTokens(bulletContent, citations, primaryColor, containerColor)
                }
                trimmed.matches(Regex("""^(\d+\.)\s+.*""")) -> {
                    val numMatch = Regex("""^(\d+\.)\s+""").find(trimmed)!!
                    val numStr = numMatch.groupValues[1]
                    val numContent = trimmed.substring(numMatch.range.last + 1)
                    pushStyle(SpanStyle(color = bulletColor, fontWeight = FontWeight.Bold))
                    append("  $numStr ")
                    pop()
                    renderLineTokens(numContent, citations, primaryColor, containerColor)
                }
                else -> {
                    renderLineTokens(rawLine, citations, primaryColor, containerColor)
                }
            }
        }
    }
}

private fun AnnotatedString.Builder.renderLineTokens(
    line: String,
    citations: List<Citation>,
    primaryColor: androidx.compose.ui.graphics.Color,
    containerColor: androidx.compose.ui.graphics.Color
) {
    val tokens = mutableListOf<AnswerInlineToken>()

    // 1. Citations
    CITATION_INLINE_REGEX.findAll(line).forEach { match ->
        val g1 = match.groups[1]?.value?.toIntOrNull()
        val g2 = match.groups[2]?.value?.toIntOrNull()
        val g3 = match.groups[3]?.value

        val indices = when {
            g1 != null && g2 != null -> (g1..g2).toList()
            g1 != null -> listOf(g1)
            g3 != null -> g3.split(',').mapNotNull { it.trim().toIntOrNull() }
            else -> emptyList()
        }

        val matchedCitations = indices.mapNotNull { idx ->
            citations.find { it.sourceIndex == idx } ?: citations.getOrNull(idx - 1)
        }

        if (matchedCitations.isNotEmpty()) {
            tokens.add(
                AnswerInlineToken.CitationToken(
                    start = match.range.first,
                    end = match.range.last + 1,
                    citation = matchedCitations.first(),
                    text = match.value
                )
            )
        }
    }

    // 2. Bold: **text**
    Regex("""\*\*([^*\n]+?)\*\*""").findAll(line).forEach { match ->
        val s = match.range.first
        val e = match.range.last + 1
        if (tokens.none { maxOf(it.start, s) < minOf(it.end, e) }) {
            tokens.add(AnswerInlineToken.BoldToken(s, e, match.groupValues[1]))
        }
    }

    // 3. Inline code: `code`
    Regex("""`([^`\n]+?)`""").findAll(line).forEach { match ->
        val s = match.range.first
        val e = match.range.last + 1
        if (tokens.none { maxOf(it.start, s) < minOf(it.end, e) }) {
            tokens.add(AnswerInlineToken.CodeToken(s, e, match.groupValues[1]))
        }
    }

    // 4. Italic: *text* (single asterisk)
    Regex("""(?<!\*)\*([^*\n]+?)\*(?!\*)""").findAll(line).forEach { match ->
        val s = match.range.first
        val e = match.range.last + 1
        if (tokens.none { maxOf(it.start, s) < minOf(it.end, e) }) {
            tokens.add(AnswerInlineToken.ItalicToken(s, e, match.groupValues[1]))
        }
    }

    tokens.sortBy { it.start }

    var cursor = 0
    for (token in tokens) {
        if (token.start > cursor) {
            append(line.substring(cursor, token.start))
        }
        when (token) {
            is AnswerInlineToken.CitationToken -> {
                pushStringAnnotation(
                    tag = "CITATION",
                    annotation = "${token.citation.noteId}|||${token.citation.quoteSnippet}"
                )
                pushStyle(
                    SpanStyle(
                        color = primaryColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        baselineShift = BaselineShift(0.25f),
                        background = containerColor
                    )
                )
                append(" ${token.text} ")
                pop()
                pop()
            }
            is AnswerInlineToken.BoldToken -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(token.content)
                pop()
            }
            is AnswerInlineToken.CodeToken -> {
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        background = containerColor.copy(alpha = 0.35f)
                    )
                )
                append(" ${token.content} ")
                pop()
            }
            is AnswerInlineToken.ItalicToken -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(token.content)
                pop()
            }
        }
        cursor = token.end
    }
    if (cursor < line.length) {
        append(line.substring(cursor))
    }
}

@Composable
private fun ClickableAnswerText(
    message: GroundedAskMessage,
    onCitationClick: (Citation) -> Unit,
    modifier: Modifier = Modifier
) {
    val answer = message.answer
    val citations = message.citations

    val primaryColor = MaterialTheme.colorScheme.primary
    val containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
    val headingColor = MaterialTheme.colorScheme.primary
    val bulletColor = MaterialTheme.colorScheme.tertiary
    val annotatedText = remember(answer, citations, primaryColor, containerColor, headingColor, bulletColor) {
        buildAnnotatedAnswer(answer, citations, primaryColor, containerColor, headingColor, bulletColor)
    }

    ClickableText(
        text = annotatedText,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier,
        onClick = { offset ->
            annotatedText.getStringAnnotations(tag = "CITATION", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    val parts = annotation.item.split("|||", limit = 2)
                    val noteId = parts.getOrNull(0) ?: return@let
                    val snippet = parts.getOrNull(1) ?: ""
                    val citation = citations.find { it.noteId == noteId && (snippet.isBlank() || it.quoteSnippet == snippet) }
                        ?: citations.find { it.noteId == noteId }
                        ?: Citation(noteId = noteId, noteTitle = "Referenced Note", quoteSnippet = snippet, quoteHash = "")
                    onCitationClick(citation)
                }
        }
    )
}

@Composable
private fun CitationItem(citation: Citation, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Source,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (citation.sourceIndex > 0) {
                            "Source ${citation.sourceIndex} — ${citation.noteTitle.ifEmpty { "Untitled" }}"
                        } else {
                            citation.noteTitle.ifEmpty { "Untitled" }
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (citation.quoteSnippet.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "\"${citation.quoteSnippet.take(120)}...\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "Jump to note",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CitationPreviewBottomSheet(
    citation: Citation,
    onJumpToNote: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: Source badge and Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (citation.sourceIndex > 0) "Source ${citation.sourceIndex}" else "Cited Source",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            // Note Title
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Referenced Note",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = citation.noteTitle.ifBlank { "Untitled Note" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Quoted Evidence Passage Card
            if (citation.quoteSnippet.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Source,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = "VERIFIED EVIDENCE SNIPPET",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Text(
                            text = "\"${citation.quoteSnippet}\"",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Deep-link Action: Jump to Note Button
            Button(
                onClick = onJumpToNote,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Jump to Note",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
