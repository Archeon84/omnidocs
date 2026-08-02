package com.omnidocs.app.docimport

import android.content.Context
import android.net.Uri
import org.jsoup.Jsoup

/**
 * Converts HTML files to clean HTML for the editor.
 * Strcripts, styles, and non-content elements.
 */
class HtmlDocumentConverter : DocumentConverter {

    override val supportedMimeTypes = listOf(
        "text/html",
        "application/xhtml+xml"
    )

    override suspend fun convert(context: Context, uri: Uri, fileName: String): ConversionResult? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val rawHtml = inputStream.bufferedReader().use { it.readText() }

            if (rawHtml.isBlank()) return null

            val doc = Jsoup.parse(rawHtml)

            // Remove non-content elements
            doc.select("script, style, nav, footer, header, aside, .sidebar, .ad, .advertisement").remove()

            // Get title from <title> tag or fallback to filename
            val title = doc.title().ifBlank { fileName.substringBeforeLast('.') }

            // Get cleaned body HTML
            val bodyHtml = doc.body()?.html() ?: rawHtml
            val plainText = doc.body()?.text() ?: ""

            ConversionResult(
                title = title,
                htmlContent = bodyHtml,
                plainText = plainText
            )
        } catch (e: Exception) {
            null
        }
    }
}
