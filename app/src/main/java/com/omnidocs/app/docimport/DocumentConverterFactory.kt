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
     * Get the appropriate converter for a MIME type.
     * Falls back to [PlainTextViewConverter] for unknown types.
     */
    fun getConverter(mimeType: String): DocumentConverter {
        return mimeTypeToConverter[mimeType] ?: PlainTextViewConverter()
    }

    /**
     * All supported MIME types across all converters.
     */
    fun supportedMimeTypes(): List<String> {
        return mimeTypeToConverter.keys.toList()
    }
}
