package com.omnidocs.app.agent

import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class NormalizedSegment(
    val startMs: Long,
    val endMs: Long,
    val speakerId: String? = null,
    val rawText: String,
    val correctedText: String,
    val confidence: Float
)

/**
 * Agent responsible for cleaning, punctuating, and structuring raw speech-to-text
 * transcripts into conversational segments with timing provenance.
 */
@Singleton
class TranscriptNormalizerAgent @Inject constructor() : Agent {

    override val id: String = "agent_transcript_normalizer"

    override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Transcript normalization cancelled by user")
        }

        val rawTranscript = input.payload["rawTranscript"] ?: ""
        val segmentsJson = input.payload["segmentsJson"] ?: "[]"

        val segments = mutableListOf<NormalizedSegment>()
        try {
            val array = JSONArray(segmentsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val raw = obj.optString("text", "")
                val clean = cleanText(raw)
                if (clean.isNotBlank()) {
                    segments.add(
                        NormalizedSegment(
                            startMs = obj.optLong("startMs", 0L),
                            endMs = obj.optLong("endMs", 0L),
                            speakerId = obj.optString("speakerId").takeIf { it.isNotBlank() },
                            rawText = raw,
                            correctedText = clean,
                            confidence = obj.optDouble("confidence", 0.9).toFloat()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Fallback: segment raw text by line
            if (rawTranscript.isNotBlank()) {
                var currentMs = 0L
                for (line in rawTranscript.lines()) {
                    val clean = cleanText(line)
                    if (clean.isNotBlank()) {
                        val duration = (clean.length * 50L).coerceAtLeast(1000L)
                        segments.add(
                            NormalizedSegment(
                                startMs = currentMs,
                                endMs = currentMs + duration,
                                speakerId = null,
                                rawText = line,
                                correctedText = clean,
                                confidence = 0.85f
                            )
                        )
                        currentMs += duration
                    }
                }
            }
        }

        val jsonArray = JSONArray()
        val fullTextBuilder = StringBuilder()
        for (seg in segments) {
            val obj = JSONObject()
            obj.put("startMs", seg.startMs)
            obj.put("endMs", seg.endMs)
            obj.put("speakerId", seg.speakerId ?: "")
            obj.put("rawText", seg.rawText)
            obj.put("correctedText", seg.correctedText)
            obj.put("confidence", seg.confidence.toDouble())
            jsonArray.put(obj)

            fullTextBuilder.append(seg.correctedText).append("\n\n")
        }

        return AgentResult.Success(
            payload = mapOf(
                "normalizedSegmentsJson" to jsonArray.toString(),
                "fullNormalizedText" to fullTextBuilder.toString().trim(),
                "segmentCount" to segments.size
            )
        )
    }

    private fun cleanText(text: String): String {
        return text
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("(\\b\\w+\\b)( \\1)+", RegexOption.IGNORE_CASE), "$1") // Remove stutter duplicates
            .trim()
    }
}
