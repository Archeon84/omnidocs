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

    companion object {
        private const val MAX_IMPORT_CHARS = 500_000
    }

    override suspend fun convert(context: Context, uri: Uri, fileName: String): ConversionOutcome {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ConversionOutcome.Failure("Couldn't open \"$fileName\"")

            val rawText = inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                val sb = StringBuilder()
                var read: Int
                while (reader.read(buffer).also { read = it } != -1) {
                    sb.append(buffer, 0, read)
                    if (sb.length >= MAX_IMPORT_CHARS) {
                        sb.append("\n\n[Document truncated: exceeded mobile import limit of 500,000 characters]")
                        break
                    }
                }
                sb.toString()
            }

            if (rawText.isBlank()) return ConversionOutcome.Failure("No text content found in \"$fileName\"")

            val title = fileName.substringBeforeLast('.')
            val html = rawText.lines()
                .filter { it.isNotBlank() }
                .joinToString("") { "<p>${escapeHtml(it)}</p>" }

            ConversionOutcome.Success(
                ConversionResult(
                    title = title,
                    htmlContent = html,
                    plainText = rawText.trim()
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
