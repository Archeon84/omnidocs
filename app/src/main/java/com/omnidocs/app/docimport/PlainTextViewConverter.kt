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

    override suspend fun convert(context: Context, uri: Uri, fileName: String): ConversionResult? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val text = inputStream.bufferedReader().use { it.readText() }

            if (text.isBlank()) return null

            val title = fileName.substringBeforeLast('.')
            val html = text.lines()
                .filter { it.isNotBlank() }
                .joinToString("") { "<p>${escapeHtml(it)}</p>" }

            ConversionResult(
                title = title,
                htmlContent = html,
                plainText = text.trim()
            )
        } catch (e: Exception) {
            null
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
