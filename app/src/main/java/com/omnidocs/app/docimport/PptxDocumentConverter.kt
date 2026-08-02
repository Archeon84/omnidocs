package com.omnidocs.app.docimport

import android.content.Context
import android.net.Uri
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xslf.usermodel.XMLSlideShow

/**
 * Converts PowerPoint PPTX files to HTML using Apache POI.
 * Extracts text from each slide.
 */
class PptxDocumentConverter : DocumentConverter {

    override val supportedMimeTypes = listOf(
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.ms-powerpoint"
    )

    override suspend fun convert(context: Context, uri: Uri, fileName: String): ConversionResult? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val slideshow = XMLSlideShow(inputStream)

            val title = fileName.substringBeforeLast('.')
            val htmlBuilder = StringBuilder()
            val plainBuilder = StringBuilder()

            for ((idx, slide) in slideshow.slides.withIndex()) {
                htmlBuilder.appendLine("<h2>Slide ${idx + 1}</h2>")
                plainBuilder.appendLine("=== Slide ${idx + 1} ===")

                extractSlideText(slide, htmlBuilder, plainBuilder)
                htmlBuilder.appendLine()
                plainBuilder.appendLine()
            }

            slideshow.close()

            val html = htmlBuilder.toString().trim()
            val plain = plainBuilder.toString().trim()

            if (html.isEmpty()) return null

            ConversionResult(
                title = title,
                htmlContent = html,
                plainText = plain
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun extractSlideText(
        slide: XSLFSlide,
        htmlBuilder: StringBuilder,
        plainBuilder: StringBuilder
    ) {
        for (shape in slide.shapes) {
            if (shape is org.apache.poi.xslf.usermodel.XSLFTextShape) {
                val text = shape.text?.trim()
                if (!text.isNullOrEmpty()) {
                    htmlBuilder.appendLine("<p>${escapeHtml(text)}</p>")
                    plainBuilder.appendLine(text)
                }
            }
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
