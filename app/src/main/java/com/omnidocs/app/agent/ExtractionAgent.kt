package com.omnidocs.app.agent

import android.content.Context
import android.net.Uri
import com.omnidocs.app.docimport.ConversionOutcome
import com.omnidocs.app.docimport.DocumentConverterFactory
import com.omnidocs.app.ocr.OcrEngineFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent responsible for extracting structured text and metadata from
 * source documents (PDF, DOCX, DOC, XLSX, PPTX, MD, HTML, TXT) and camera/scanned images.
 *
 * Implements [Agent] contract and delegates conversion to [DocumentConverterFactory]
 * and [OcrEngineFactory] with visual bounding-box provenance.
 */
@Singleton
class ExtractionAgent @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val converterFactory: DocumentConverterFactory,
    private val ocrEngineFactory: OcrEngineFactory
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
        val language = input.payload["language"] ?: "en"

        val uri = try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            return AgentResult.PermanentFailure("Invalid URI format: $uriString", e)
        }

        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Extraction cancelled before processing")
        }

        // Handle image OCR extraction with bounding boxes
        if (mimeType.startsWith("image/") || mimeType == "image/jpeg" || mimeType == "image/png") {
            val ocrEngine = ocrEngineFactory.getEngine()
            val ocrResult = ocrEngine.recognizeText(uri, language)
            if (ocrResult != null && ocrResult.text.isNotBlank()) {
                val blocksJsonArray = JSONArray()
                for ((idx, block) in ocrResult.blocks.withIndex()) {
                    val bboxObj = JSONObject().apply {
                        put("left", block.boundingBox.left.toDouble())
                        put("top", block.boundingBox.top.toDouble())
                        put("right", block.boundingBox.right.toDouble())
                        put("bottom", block.boundingBox.bottom.toDouble())
                    }
                    val blockObj = JSONObject().apply {
                        put("blockIndex", idx)
                        put("blockType", "ocr_block")
                        put("content", block.text)
                        put("confidence", block.confidence.toDouble())
                        put("boundingBoxJson", bboxObj.toString())
                    }
                    blocksJsonArray.put(blockObj)
                }

                return AgentResult.Success(
                    payload = mapOf(
                        "title" to fileName.substringBeforeLast("."),
                        "htmlContent" to ocrResult.html,
                        "plainText" to ocrResult.text,
                        "characterCount" to ocrResult.text.length,
                        "mimeType" to mimeType,
                        "fileName" to fileName,
                        "blocksJson" to blocksJsonArray.toString()
                    )
                )
            }
        }

        // Standard document extraction via converter factory
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
