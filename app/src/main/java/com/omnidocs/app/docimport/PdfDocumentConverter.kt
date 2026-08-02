package com.omnidocs.app.docimport

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * Converts PDF files to HTML using Apache PDFBox.
 * Extracts text content and wraps in paragraphs.
 */
class PdfDocumentConverter : DocumentConverter {

    override val supportedMimeTypes = listOf(
        "application/pdf"
    )

    private var initialized = false

    private fun init(context: Context) {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
    }

    override suspend fun convert(context: Context, uri: Uri, fileName: String): ConversionResult? {
        return try {
            init(context)

            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val document = PDDocument.load(inputStream)

            val stripper = PDFTextStripper()
            val text = stripper.getText(document)
            document.close()

            if (text.isBlank()) return null

            val title = extractTitle(text, fileName)
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

    private fun extractTitle(text: String, fileName: String): String {
        // Try to use the first non-blank line as title if it's short enough
        val firstLine = text.lines().firstOrNull { it.trim().isNotBlank() }?.trim()
        return if (firstLine != null && firstLine.length <= 80) {
            firstLine
        } else {
            fileName.substringBeforeLast('.')
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
