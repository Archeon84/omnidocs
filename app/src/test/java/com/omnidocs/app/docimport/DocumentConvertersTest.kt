package com.omnidocs.app.docimport

import org.junit.Assert.*
import org.junit.Test

class DocumentConvertersTest {

    private val factory = DocumentConverterFactory()

    @Test
    fun testFactoryResolvesMarkdownByExtension() {
        val converter = factory.getConverter("application/octet-stream", "meeting_notes.md")
        assertTrue(converter is MarkdownDocumentConverter)
    }

    @Test
    fun testFactoryResolvesPdfByMimeAndExtension() {
        val converterByExt = factory.getConverter("application/octet-stream", "document.pdf")
        assertTrue(converterByExt is PdfDocumentConverter)

        val converterByMime = factory.getConverter("application/pdf", "unknown_file")
        assertTrue(converterByMime is PdfDocumentConverter)
    }

    @Test
    fun testFactoryResolvesOfficeFormats() {
        val docxConverter = factory.getConverter("application/octet-stream", "report.docx")
        assertTrue(docxConverter is DocxDocumentConverter)

        val xlsxConverter = factory.getConverter("application/octet-stream", "data.xlsx")
        assertTrue(xlsxConverter is XlsxDocumentConverter)

        val pptxConverter = factory.getConverter("application/octet-stream", "presentation.pptx")
        assertTrue(pptxConverter is PptxDocumentConverter)
    }

    @Test
    fun testFactoryFallbackToPlainTextView() {
        val fallback = factory.getConverter("unknown/mime-type", "file.custom_ext")
        assertTrue(fallback is PlainTextViewConverter)
    }

    @Test
    fun testMarkdownTitleExtraction() {
        val md = """
            # Executive Summary Q3

            This is the introduction paragraph.
            - Point 1
            - Point 2
        """.trimIndent()

        val firstHeading = md.lines()
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            ?.trim()

        assertEquals("Executive Summary Q3", firstHeading)
    }
}
