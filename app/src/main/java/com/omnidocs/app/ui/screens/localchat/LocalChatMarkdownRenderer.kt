package com.omnidocs.app.ui.screens.localchat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

sealed class ChatContentSegment {
    data class Text(val content: String) : ChatContentSegment()
    data class CodeBlock(val language: String, val code: String) : ChatContentSegment()
}

/**
 * Parses raw LLM text output into distinct Code Blocks and Markdown Text blocks.
 */
object ChatMarkdownParser {
    private val CODE_BLOCK_REGEX = Regex("""```([a-zA-Z0-9_\-\+\#]*)\n([\s\S]*?)```""")

    fun parse(rawText: String): List<ChatContentSegment> {
        if (rawText.isBlank()) return emptyList()

        val segments = mutableListOf<ChatContentSegment>()
        var lastIndex = 0

        for (match in CODE_BLOCK_REGEX.findAll(rawText)) {
            val range = match.range
            if (range.first > lastIndex) {
                val textPart = rawText.substring(lastIndex, range.first)
                if (textPart.isNotBlank()) {
                    segments.add(ChatContentSegment.Text(textPart))
                }
            }
            val lang = match.groupValues[1].trim().ifEmpty { "CODE" }
            val code = match.groupValues[2].trimEnd()
            segments.add(ChatContentSegment.CodeBlock(language = lang, code = code))
            lastIndex = range.last + 1
        }

        if (lastIndex < rawText.length) {
            val remaining = rawText.substring(lastIndex)
            // Handle unclosed code block during active streaming
            if (remaining.contains("```")) {
                val openIdx = remaining.indexOf("```")
                if (openIdx > 0) {
                    segments.add(ChatContentSegment.Text(remaining.substring(0, openIdx)))
                }
                val codeAfter = remaining.substring(openIdx + 3)
                val firstNl = codeAfter.indexOf('\n')
                if (firstNl != -1) {
                    val lang = codeAfter.substring(0, firstNl).trim().ifEmpty { "CODE" }
                    val code = codeAfter.substring(firstNl + 1)
                    segments.add(ChatContentSegment.CodeBlock(language = lang, code = code))
                } else {
                    segments.add(ChatContentSegment.CodeBlock(language = codeAfter.trim().ifEmpty { "CODE" }, code = ""))
                }
            } else if (remaining.isNotBlank()) {
                segments.add(ChatContentSegment.Text(remaining))
            }
        }

        return segments.ifEmpty { listOf(ChatContentSegment.Text(rawText)) }
    }

    /**
     * Converts a markdown text line/block into an AnnotatedString with formatting.
     */
    fun buildAnnotatedMarkdown(
        text: String,
        primaryColor: Color,
        headingColor: Color,
        codeBgColor: Color,
        codeTextColor: Color
    ): AnnotatedString {
        return buildAnnotatedString {
            val lines = text.split('\n')
            for ((idx, rawLine) in lines.withIndex()) {
                if (idx > 0) append("\n")
                val trimmed = rawLine.trimStart()

                when {
                    trimmed.startsWith("### ") -> {
                        pushStyle(SpanStyle(color = headingColor, fontWeight = FontWeight.Bold, fontSize = 15.sp))
                        append(renderInlineMarkdown(trimmed.removePrefix("### "), primaryColor, codeBgColor, codeTextColor))
                        pop()
                    }
                    trimmed.startsWith("## ") -> {
                        pushStyle(SpanStyle(color = headingColor, fontWeight = FontWeight.Bold, fontSize = 16.sp))
                        append(renderInlineMarkdown(trimmed.removePrefix("## "), primaryColor, codeBgColor, codeTextColor))
                        pop()
                    }
                    trimmed.startsWith("# ") -> {
                        pushStyle(SpanStyle(color = headingColor, fontWeight = FontWeight.Bold, fontSize = 18.sp))
                        append(renderInlineMarkdown(trimmed.removePrefix("# "), primaryColor, codeBgColor, codeTextColor))
                        pop()
                    }
                    trimmed.startsWith("> ") -> {
                        pushStyle(SpanStyle(color = primaryColor, fontWeight = FontWeight.Bold))
                        append("▎ ")
                        pop()
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append(renderInlineMarkdown(trimmed.removePrefix("> "), primaryColor, codeBgColor, codeTextColor))
                        pop()
                    }
                    trimmed.matches(Regex("""^[-*+]\s+.*""")) -> {
                        val match = Regex("""^[-*+]\s+""").find(trimmed)!!
                        val bulletText = trimmed.substring(match.range.last + 1)
                        pushStyle(SpanStyle(color = primaryColor, fontWeight = FontWeight.Bold))
                        append("  • ")
                        pop()
                        append(renderInlineMarkdown(bulletText, primaryColor, codeBgColor, codeTextColor))
                    }
                    trimmed.matches(Regex("""^(\d+\.)\s+.*""")) -> {
                        val match = Regex("""^(\d+\.)\s+""").find(trimmed)!!
                        val num = match.groupValues[1]
                        val itemText = trimmed.substring(match.range.last + 1)
                        pushStyle(SpanStyle(color = primaryColor, fontWeight = FontWeight.Bold))
                        append("  $num ")
                        pop()
                        append(renderInlineMarkdown(itemText, primaryColor, codeBgColor, codeTextColor))
                    }
                    else -> {
                        append(renderInlineMarkdown(rawLine, primaryColor, codeBgColor, codeTextColor))
                    }
                }
            }
        }
    }

    private fun renderInlineMarkdown(
        line: String,
        primaryColor: Color,
        codeBgColor: Color,
        codeTextColor: Color
    ): AnnotatedString {
        return buildAnnotatedString {
            // Tokenize inline bold (**), italic (*), inline code (`)
            var cursor = 0
            while (cursor < line.length) {
                val nextBold = line.indexOf("**", cursor)
                val nextCode = line.indexOf('`', cursor)

                // Pick earliest token
                var earliest = -1
                var tokenType = ""

                if (nextBold != -1 && (earliest == -1 || nextBold < earliest)) {
                    val endBold = line.indexOf("**", nextBold + 2)
                    if (endBold != -1) {
                        earliest = nextBold
                        tokenType = "bold"
                    }
                }
                if (nextCode != -1 && (earliest == -1 || nextCode < earliest)) {
                    val endCode = line.indexOf('`', nextCode + 1)
                    if (endCode != -1) {
                        earliest = nextCode
                        tokenType = "code"
                    }
                }

                if (earliest == -1) {
                    append(line.substring(cursor))
                    break
                }

                if (earliest > cursor) {
                    append(line.substring(cursor, earliest))
                }

                when (tokenType) {
                    "bold" -> {
                        val endBold = line.indexOf("**", earliest + 2)
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        append(line.substring(earliest + 2, endBold))
                        pop()
                        cursor = endBold + 2
                    }
                    "code" -> {
                        val endCode = line.indexOf('`', earliest + 1)
                        pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBgColor,
                                color = codeTextColor,
                                fontSize = 13.sp
                            )
                        )
                        append(" ${line.substring(earliest + 1, endCode)} ")
                        pop()
                        cursor = endCode + 1
                    }
                    else -> {
                        append(line[cursor])
                        cursor++
                    }
                }
            }
        }
    }
}

/**
 * Rich code block view with language badge, syntax styling container, and 1-tap copy button.
 */
@Composable
fun CodeBlockView(
    language: String,
    code: String,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        tonalElevation = 2.dp
    ) {
        Column {
            // Header bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = language.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledTonalButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(code))
                        copied = true
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        modifier = Modifier.size(14.dp),
                        tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (copied) "Copied!" else "Copy",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp
            )

            // Code content with horizontal scroll
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
