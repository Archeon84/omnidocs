package com.omnidocs.app.docimport

import android.content.Context
import android.net.Uri
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser

/**
 * Converts Markdown files to HTML using flexmark-java.
 */
class MarkdownDocumentConverter : DocumentConverter {

    override val supportedMimeTypes = listOf(
        "text/markdown",
        "text/x-markdown"
    )

    private val parser = Parser.builder().build()
    private val renderer = HtmlRenderer.builder().build()

    override suspend fun convert(context: Context, uri: Uri, fileName: String): ConversionResult? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val markdown = inputStream.bufferedReader().use { it.readText() }

            if (markdown.isBlank()) return null

            val title = extractTitle(markdown, fileName)
            val document = parser.parse(markdown)
            val html = renderer.render(document)

            ConversionResult(
                title = title,
                htmlContent = html,
                plainText = markdown.trim()
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun extractTitle(markdown: String, fileName: String): String {
        // Try to extract from first heading
        val firstHeading = markdown.lines()
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            ?.trim()

        return firstHeading ?: fileName.substringBeforeLast('.')
    }
}
