package com.omnidocs.app.docimport

import android.content.Context
import android.net.Uri

/**
 * Converts plain text and CSV files to HTML.
 */
class PlainTextViewConverter : DocumentConverter {

    override val supportedMimeTypes = listOf(
        "text/plain",
        "text/csv",
        "text/*"
    )

    override suspend fun convert(context: Context, uri: Uri, fileName: String): ConversionOutcome {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ConversionOutcome.Failure("Couldn't open \"$fileName\"")
            val text = inputStream.bufferedReader().use { it.readText() }

            if (text.isBlank()) return ConversionOutcome.Failure("No text content found in \"$fileName\"")

            val title = fileName.substringBeforeLast('.')
            val html = text.lines()
                .filter { it.isNotBlank() }
                .joinToString("") { "<p>${escapeHtml(it)}</p>" }

            ConversionOutcome.Success(
                ConversionResult(
                    title = title,
                    htmlContent = html,
                    plainText = text.trim()
                )
            )
        } catch (e: Exception) {
            ConversionOutcome.Failure("Couldn't import \"$fileName\": ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
