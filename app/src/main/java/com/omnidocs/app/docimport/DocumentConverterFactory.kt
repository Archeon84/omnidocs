package com.omnidocs.app.docimport

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry that maps MIME types to [DocumentConverter] implementations.
 * Injected via Hilt — converters are lazily instantiated.
 */
@Singleton
class DocumentConverterFactory @Inject constructor() {

    private val converters: List<DocumentConverter> by lazy {
        listOf(
            PlainTextViewConverter(),
            HtmlDocumentConverter(),
            DocxDocumentConverter(),
            DocDocumentConverter(),
            PdfDocumentConverter(),
            XlsxDocumentConverter(),
            PptxDocumentConverter(),
            MarkdownDocumentConverter()
        )
    }

    private val mimeTypeToConverter: Map<String, DocumentConverter> by lazy {
        mutableMapOf<String, DocumentConverter>().apply {
            for (converter in converters) {
                for (mimeType in converter.supportedMimeTypes) {
                    put(mimeType, converter)
                }
            }
        }
    }

    /**
     * File extension → MIME key, used when the content resolver reports a
     * generic or wrong MIME type (e.g. `.md` as `application/octet-stream`).
     */
    private val extensionToMimeType: Map<String, String> = mapOf(
        "txt" to "text/plain",
        "csv" to "text/csv",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "mdown" to "text/markdown",
        "html" to "text/html",
        "htm" to "text/html",
        "pdf" to "application/pdf",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "doc" to "application/msword",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "xls" to "application/vnd.ms-excel",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "ppt" to "application/vnd.ms-powerpoint"
    )

    /**
     * Get the appropriate converter, preferring the file's extension when it
     * identifies a known document type (handles misreported MIME for `.md`/`.html`).
     * Falls back to [PlainTextViewConverter] for unknown types.
     */
    fun getConverter(mimeType: String, fileName: String = ""): DocumentConverter {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val byExtension = extensionToMimeType[ext]?.let { mimeTypeToConverter[it] }
        if (byExtension != null) return byExtension
        return mimeTypeToConverter[mimeType] ?: PlainTextViewConverter()
    }

    /**
     * All supported MIME types across all converters.
     */
    fun supportedMimeTypes(): List<String> {
        return mimeTypeToConverter.keys.toList()
    }
}
