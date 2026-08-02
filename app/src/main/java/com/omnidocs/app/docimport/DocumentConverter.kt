package com.omnidocs.app.docimport

import android.content.Context
import android.net.Uri

/**
 * Result of converting a document to a note.
 *
 * @property title Extracted or derived document title
 * @property htmlContent Rich HTML for the editor (paragraphs, tables, headings, etc.)
 * @property plainText Plain text for search indexing
 */
data class ConversionResult(
    val title: String,
    val htmlContent: String,
    val plainText: String
)

/**
 * Converts a document from a given MIME type into a [ConversionResult].
 */
interface DocumentConverter {
    /** MIME types this converter handles (e.g. "application/pdf") */
    val supportedMimeTypes: List<String>

    /**
     * Convert the document at [uri] to a [ConversionResult].
     *
     * @param context Application or activity context for content resolver access
     * @param uri Content URI of the document to convert
     * @param fileName Original file name (used for title fallback)
     * @return Conversion result, or null if conversion fails
     */
    suspend fun convert(context: Context, uri: Uri, fileName: String): ConversionResult?
}
