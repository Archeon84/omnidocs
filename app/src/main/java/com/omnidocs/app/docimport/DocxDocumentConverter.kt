package com.omnidocs.app.docimport

import android.content.Context
import android.net.Uri
import org.apache.poi.xwpf.usermodel.XWPFDocument

/**
 * Converts DOCX files to HTML using Apache POI.
 * Extracts paragraphs, tables, and headings.
 */
class DocxDocumentConverter : DocumentConverter {

    override val supportedMimeTypes = listOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    )

    override suspend fun convert(context: Context, uri: Uri, fileName: String): ConversionOutcome {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ConversionOutcome.Failure("Couldn't open \"$fileName\"")
            val doc = XWPFDocument(inputStream)

            val title = try {
                doc.properties?.coreProperties?.title?.ifBlank { null }
                    ?: fileName.substringBeforeLast('.')
            } catch (e: Exception) {
                fileName.substringBeforeLast('.')
            }

            val htmlBuilder = StringBuilder()
            val plainBuilder = StringBuilder()

            for (body in doc.bodyElements) {
                when (body) {
                    is org.apache.poi.xwpf.usermodel.XWPFParagraph -> {
                        val text = body.text.trim()
                        if (text.isNotEmpty()) {
                            val tag = headingTag(body.style)
                            if (tag != null) {
                                htmlBuilder.appendLine("<$tag>$text</$tag>")
                            } else {
                                htmlBuilder.appendLine("<p>$text</p>")
                            }
                            plainBuilder.appendLine(text)
                        }
                    }
                    is org.apache.poi.xwpf.usermodel.XWPFTable -> {
                        htmlBuilder.appendLine(renderTable(body))
                        plainBuilder.appendLine(renderTablePlainText(body))
                    }
                }
            }

            doc.close()

            val html = htmlBuilder.toString().trim()
            val plain = plainBuilder.toString().trim()

            if (html.isEmpty()) return ConversionOutcome.Failure("No text content found in \"$fileName\"")

            ConversionOutcome.Success(
                ConversionResult(
                    title = title,
                    htmlContent = html,
                    plainText = plain
                )
            )
        } catch (e: Exception) {
            ConversionOutcome.Failure("Couldn't import \"$fileName\": ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun headingTag(style: String?): String? {
        return when {
            style == null -> null
            style.contains("Heading1", ignoreCase = true) -> "h1"
            style.contains("Heading2", ignoreCase = true) -> "h2"
            style.contains("Heading3", ignoreCase = true) -> "h3"
            style.contains("Heading", ignoreCase = true) -> "h2"
            style.contains("Title", ignoreCase = true) -> "h1"
            else -> null
        }
    }

    private fun renderTable(table: org.apache.poi.xwpf.usermodel.XWPFTable): String {
        val sb = StringBuilder("<table>")
        for (row in table.rows) {
            sb.append("<tr>")
            for (cell in row.tableCells) {
                val text = cell.text.trim()
                sb.append("<td>$text</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
        return sb.toString()
    }

    private fun renderTablePlainText(table: org.apache.poi.xwpf.usermodel.XWPFTable): String {
        val sb = StringBuilder()
        for (row in table.rows) {
            val cells = row.tableCells.map { it.text.trim() }
            sb.appendLine(cells.joinToString(" | "))
        }
        return sb.toString()
    }
}
