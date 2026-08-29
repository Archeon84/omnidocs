package com.omnidocs.app.agent

import android.content.Context
import android.net.Uri
import com.omnidocs.app.docimport.ConversionOutcome
import com.omnidocs.app.docimport.DocumentConverterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent responsible for extracting structured text and metadata from
 * source documents (PDF, DOCX, DOC, XLSX, PPTX, MD, HTML, TXT).
 *
 * Implements [Agent] contract and delegates conversion to [DocumentConverterFactory].
 */
@Singleton
class ExtractionAgent @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val converterFactory: DocumentConverterFactory
) : Agent {

    override val id: String = "agent_extraction"

    override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Extraction cancelled by user")
        }

        val uriString = input.payload["uri"]
            ?: return AgentResult.PermanentFailure("Missing 'uri' in extraction input payload")
        val mimeType = input.payload["mimeType"] ?: "application/octet-stream"
        val fileName = input.payload["fileName"] ?: "document"

        val uri = try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            return AgentResult.PermanentFailure("Invalid URI format: $uriString", e)
        }

        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Extraction cancelled before processing")
        }

        val converter = converterFactory.getConverter(mimeType, fileName)
        return when (val outcome = converter.convert(appContext, uri, fileName)) {
            is ConversionOutcome.Success -> {
                AgentResult.Success(
                    payload = mapOf(
                        "title" to outcome.result.title,
                        "htmlContent" to outcome.result.htmlContent,
                        "plainText" to outcome.result.plainText,
                        "characterCount" to outcome.result.plainText.length,
                        "mimeType" to mimeType,
                        "fileName" to fileName
                    )
                )
            }
            is ConversionOutcome.Failure -> {
                AgentResult.PermanentFailure("Extraction failed: ${outcome.message}")
            }
        }
    }
}
