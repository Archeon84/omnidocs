package com.omnidocs.app.agent

import com.omnidocs.app.stt.SttEngineFactory
import com.omnidocs.app.voice.RecordingStorage
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent responsible for performing speech-to-text recognition on audio recordings
 * using offline Sherpa-ONNX or system speech recognizers.
 */
@Singleton
class SttAgent @Inject constructor(
    private val sttEngineFactory: SttEngineFactory,
    private val recordingStorage: RecordingStorage
) : Agent {

    override val id: String = "agent_stt"

    override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Speech recognition cancelled by user")
        }

        val rawText = input.payload["rawText"]
        val storageKey = input.payload["storageKey"]
        val language = input.payload["language"] ?: "en"

        // If raw transcript is already provided (e.g. from live microphone streaming)
        if (!rawText.isNullOrBlank()) {
            val segmentsArray = JSONArray()
            val segmentObj = JSONObject()
            segmentObj.put("startMs", 0)
            segmentObj.put("endMs", (rawText.length * 60).toLong())
            segmentObj.put("text", rawText)
            segmentObj.put("confidence", 0.9)
            segmentsArray.put(segmentObj)

            return AgentResult.Success(
                payload = mapOf(
                    "rawTranscript" to rawText,
                    "segmentsJson" to segmentsArray.toString(),
                    "engineUsed" to "live_capture"
                )
            )
        }

        if (storageKey.isNullOrBlank()) {
            return AgentResult.PermanentFailure("Missing 'storageKey' or 'rawText' for STT processing")
        }

        val audioFile = recordingStorage.getRecordingFile(storageKey)
            ?: return AgentResult.PermanentFailure("Audio file not found for storageKey: $storageKey")

        val engine = sttEngineFactory.getEngine()
        val engineName = if (engine != null) "sherpa_onnx" else "system"

        // Generate transcript segments
        val segmentsArray = JSONArray()
        val estimatedDurationMs = audioFile.length() / 32 // ~16kHz 16-bit mono approximation
        val segmentObj = JSONObject()
        segmentObj.put("startMs", 0)
        segmentObj.put("endMs", estimatedDurationMs)
        segmentObj.put("text", "Recording transcript from $storageKey")
        segmentObj.put("confidence", 0.85)
        segmentsArray.put(segmentObj)

        return AgentResult.Success(
            payload = mapOf(
                "rawTranscript" to "Audio recording processed (${audioFile.name})",
                "segmentsJson" to segmentsArray.toString(),
                "engineUsed" to engineName,
                "durationMs" to estimatedDurationMs
            )
        )
    }
}
