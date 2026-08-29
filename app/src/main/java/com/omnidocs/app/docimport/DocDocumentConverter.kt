package com.omnidocs.app.docimport

import android.content.Context
import android.net.Uri
import org.apache.poi.hwpf.HWPFDocument

/**
 * Converts legacy .doc (Word 97-2003) files to HTML using Apache POI.
 */
class DocDocumentConverter : DocumentConverter {

    override val supportedMimeTypes = listOf(
        "application/msword"
    )

    override suspend fun convert(context: Context, uri: Uri, fileName: String): ConversionOutcome {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ConversionOutcome.Failure("Couldn't open \"$fileName\"")
            val doc = HWPFDocument(inputStream)

            val text = doc.text?.toString()
                ?: return ConversionOutcome.Failure("No text content found in \"$fileName\"")
            doc.close()

            if (text.isBlank()) return ConversionOutcome.Failure("No text content found in \"$fileName\"")

            val title = fileName.substringBeforeLast('.')
            val html = text.lines()
                .filter { it.isNotBlank() }
                .joinToString("") { "<p>${escapeHtml(it.trim())}</p>" }

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
