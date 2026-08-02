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

    override suspend fun convert(context: Context, uri: Uri, fileName: String): ConversionResult? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val doc = HWPFDocument(inputStream)

            val text = doc.text?.toString() ?: return null
            doc.close()

            if (text.isBlank()) return null

            val title = fileName.substringBeforeLast('.')
            val html = text.lines()
                .filter { it.isNotBlank() }
                .joinToString("") { "<p>${escapeHtml(it.trim())}</p>" }

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
