package com.omnidocs.app.agent

import com.omnidocs.app.search.Tokenizer
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class VerificationReport(
    val isVerified: Boolean,
    val finalConfidence: String,
    val citationCoverage: Float,
    val unsupportedClaims: List<String>,
    val correctedAnswer: String? = null
)

/**
 * Agent responsible for verifying factual claims, citation precision,
 * and ensuring answers are strictly supported by retrieved evidence.
 */
@Singleton
class VerificationAgent @Inject constructor() : Agent {

    override val id: String = "agent_verification"

    override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Verification cancelled by user")
        }

        val answer = input.payload["answer"] ?: ""
        val citationsJson = input.payload["citationsJson"] ?: "[]"
        val rawConfidence = input.payload["confidence"] ?: "MEDIUM"

        val citationsArray = try {
            JSONArray(citationsJson)
        } catch (e: Exception) {
            JSONArray()
        }

        val citations = mutableListOf<Citation>()
        for (i in 0 until citationsArray.length()) {
            val obj = try {
                citationsArray.getJSONObject(i)
            } catch (e: Exception) {
                android.util.Log.w("VerificationAgent", "Skipping malformed citation at index $i", e)
                continue
            }
            citations.add(
                Citation(
                    noteId = obj.optString("noteId"),
                    noteTitle = obj.optString("noteTitle"),
                    blockId = obj.optString("blockId").takeIf { it.isNotBlank() },
                    startOffset = obj.optIntLenient("startOffset"),
                    endOffset = obj.optIntLenient("endOffset"),
                    quoteSnippet = obj.optString("quoteSnippet"),
                    quoteHash = obj.optString("quoteHash"),
                    score = obj.optDouble("score", 0.0),
                    sourceIndex = obj.optInt("sourceIndex", 0)
                )
            )
        }

        val report = verify(answer, citations, rawConfidence)

        val verifiedCitationsArray = JSONArray()
        for (c in citations) {
            val obj = JSONObject()
            obj.put("noteId", c.noteId)
            obj.put("noteTitle", c.noteTitle)
            obj.put("blockId", c.blockId ?: "")
            obj.put("startOffset", c.startOffset ?: -1)
            obj.put("endOffset", c.endOffset ?: -1)
            obj.put("quoteSnippet", c.quoteSnippet)
            obj.put("quoteHash", c.quoteHash)
            obj.put("score", c.score)
            obj.put("sourceIndex", c.sourceIndex)
            verifiedCitationsArray.put(obj)
        }

        return AgentResult.Success(
            payload = mapOf(
                "isVerified" to report.isVerified,
                "finalConfidence" to report.finalConfidence,
                "citationCoverage" to report.citationCoverage.toDouble(),
                "unsupportedClaimsCount" to report.unsupportedClaims.size,
                "verifiedCitationsJson" to verifiedCitationsArray.toString(),
                "correctedAnswer" to (report.correctedAnswer ?: "")
            )
        )
    }

    fun verify(
        answer: String,
        citations: List<Citation>,
        initialConfidence: String
    ): VerificationReport {
        if (answer.isBlank()) {
            return VerificationReport(
                isVerified = false,
                finalConfidence = "LOW",
                citationCoverage = 0f,
                unsupportedClaims = listOf("Answer text is empty")
            )
        }

        if (isAbstentionOnly(answer)) {
            return VerificationReport(
                isVerified = true,
                finalConfidence = "LOW",
                citationCoverage = 1.0f,
                unsupportedClaims = emptyList()
            )
        }

        if (ABSTENTION_REGEX.containsMatchIn(answer)) {
            val remainder = ABSTENTION_REGEX.replace(answer, " ").trim()
                .trim('.', ' ', '\n', '"', ':')
            val remainderWords = remainder.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (remainderWords.size > 12 || citations.isEmpty()) {
                return VerificationReport(
                    isVerified = false,
                    finalConfidence = "LOW",
                    citationCoverage = 0f,
                    unsupportedClaims = listOf("Abstention accompanied by unsupported claims")
                )
            }
        }

        if (citations.isEmpty()) {
            return VerificationReport(
                isVerified = false,
                finalConfidence = "LOW",
                citationCoverage = 0f,
                unsupportedClaims = listOf("Answer lacks supporting source citations")
            )
        }

        // maxSources = Int.MAX_VALUE: the verifier must SEE out-of-range refs
        // (e.g. a hallucinated [Source 9]) to flag them as dangling. The
        // AnswerAgent side pre-filters those to real citations instead.
        val referenced = parseReferencedSources(answer, Int.MAX_VALUE)
        if (referenced.isEmpty()) {
            return VerificationReport(
                isVerified = false,
                finalConfidence = "LOW",
                citationCoverage = 0f,
                unsupportedClaims = listOf("Answer references no [Source N] label")
            )
        }
        val byIndex = citations.associateBy { it.sourceIndex }
        val unsupported = mutableListOf<String>()
        var supported = 0

        // Sentence-Level Entailment: Every individual sentence citing [Source N] must
        // be verified against the cited span (entailment heuristic).
        val sentences = answer.split(Regex("(?<=[.!?。！？])\\s*")).filter { it.isNotBlank() }
        for (n in referenced) {
            val citation = byIndex[n]
            if (citation == null) {
                unsupported.add("Reference [Source $n] has no matching emitted citation")
                continue
            }
            val carrierSentences = sentences.filter { parseReferencedSources(it, Int.MAX_VALUE).contains(n) }
            val unsupportedCarrierSentences = carrierSentences.filter { !isSupportedBy(it, n, citation) }
            if (unsupportedCarrierSentences.isNotEmpty()) {
                unsupported.add("${citation.noteTitle}: ${unsupportedCarrierSentences.size} claim(s) citing [Source $n] share no supporting evidence")
            } else if (carrierSentences.isNotEmpty()) {
                supported++
            } else {
                // Citation referenced via range [Sources 1-3] or list
                if (isSupportedBy(answer, n, citation)) {
                    supported++
                } else {
                    unsupported.add("${citation.noteTitle}: cited span shares no content with the answer")
                }
            }
        }
        // Every emitted citation should be referenced by the answer. (Only
        // in-range refs count: [Source 9] with one citation is dangling,
        // not a reference to citation 1.)
        val referencedInRange = referenced.intersect(byIndex.keys)
        var unreferenced = 0
        for (citation in citations) {
            if (citation.sourceIndex != 0 && citation.sourceIndex !in referencedInRange) {
                unreferenced++
                unsupported.add("${citation.noteTitle}: emitted but never referenced as [Source ${citation.sourceIndex}]")
            }
        }

        val total = (referenced.size + unreferenced).coerceAtLeast(1)
        val coverage = supported.toFloat() / total
        // Strict: the answer agent emits only referenced citations, so any
        // structural problem (dangling label, unreferenced emission, failed
        // entailment) means the provenance cannot be trusted as shown.
        val isVerified = unsupported.isEmpty()
        val finalConfidence = when {
            isVerified && initialConfidence == "HIGH" -> "HIGH"
            isVerified -> "MEDIUM"
            else -> "LOW"
        }

        // Surgical Self-Correction Loop:
        // If some claims are supported but others are unsupported, extract only the supported
        // sentences and verify if a clean, fully-supported answer can be recovered.
        var correctedAnswer: String? = null
        if (!isVerified && unsupported.isNotEmpty()) {
            val supportedSentences = sentences.filter { s ->
                val refs = parseReferencedSources(s, Int.MAX_VALUE)
                refs.isNotEmpty() && refs.all { refIndex ->
                    val citation = byIndex[refIndex]
                    citation != null && isSupportedBy(s, refIndex, citation)
                }
            }
            if (supportedSentences.isNotEmpty() && supportedSentences.size < sentences.size) {
                val candidateText = supportedSentences.joinToString(" ")
                val candidateRefs = parseReferencedSources(candidateText, Int.MAX_VALUE)
                val candidateCitations = citations.filter { it.sourceIndex in candidateRefs }
                if (candidateCitations.isNotEmpty() && candidateRefs.all { byIndex[it] != null }) {
                    val retest = verify(candidateText, candidateCitations, "MEDIUM")
                    if (retest.isVerified) {
                        correctedAnswer = candidateText
                    }
                }
            }
        }

        return VerificationReport(
            isVerified = isVerified,
            finalConfidence = finalConfidence,
            citationCoverage = coverage,
            unsupportedClaims = if (isVerified) emptyList() else unsupported,
            correctedAnswer = correctedAnswer
        )
    }

    /**
     * Entailment heuristic: the answer sentence(s) carrying the [Source N]
     * label must share at least two content words with the cited span (or at least
     * one content word when combined with a note title mention). Paraphrased-but-correct
     * answers pass via shared content words; injected or hallucinated labels fail.
     * Note title alone never passes without supporting content evidence.
     * Supports CJK fullwidth sentence terminators and ideographic n-gram matching.
     */
    private val citationMarkerRegex = Regex(
        """\[(?:Sources?\s+\d+[^\]]*|\d+(?:\s*,\s*\d+)*)\]|\bSources?\s*:?\s*\d+[\d,\s\-–and]*""",
        RegexOption.IGNORE_CASE
    )

    private fun isSupportedBy(answer: String, sourceIndex: Int, citation: Citation): Boolean {
        if (citation.quoteSnippet.isBlank()) return false
        val carrierSentences = answer.split(Regex("(?<=[.!?。！？])\\s*"))
            .filter { parseReferencedSources(it, Int.MAX_VALUE).contains(sourceIndex) }
        val carrierText = if (carrierSentences.isNotEmpty()) {
            carrierSentences.joinToString(" ")
        } else {
            answer
        }
        val cleanCarrierText = citationMarkerRegex.replace(carrierText, " ")

        val snippetTerms = Tokenizer.tokenize(citation.quoteSnippet).toSet()
        val carrierTerms = Tokenizer.tokenize(cleanCarrierText).toSet()
        if (snippetTerms.isNotEmpty() && carrierTerms.isNotEmpty()) {
            val sharedTerms = snippetTerms.intersect(carrierTerms)
            if (sharedTerms.size >= 2) return true

            val titleTerms = Tokenizer.tokenize(citation.noteTitle).toSet()
            val hasTitleMatch = titleTerms.isNotEmpty() && titleTerms.intersect(carrierTerms).isNotEmpty()
            if (sharedTerms.size >= 1 && hasTitleMatch) return true
        }

        // CJK character n-gram entailment fallback for unspaced ideographic text
        if (Tokenizer.isCjk(citation.quoteSnippet) || Tokenizer.isCjk(cleanCarrierText)) {
            val cleanCarrier = carrierText.filter { Tokenizer.isCjk(it.toString()) }
            val cleanSnippet = citation.quoteSnippet.filter { Tokenizer.isCjk(it.toString()) }
            if (cleanCarrier.length >= 2 && cleanSnippet.length >= 2) {
                var sharedBigrams = 0
                for (i in 0 until cleanCarrier.length - 1) {
                    val bigram = cleanCarrier.substring(i, i + 2)
                    if (cleanSnippet.contains(bigram)) {
                        sharedBigrams++
                    }
                }
                if (sharedBigrams >= 2 || (cleanCarrier.length >= 4 && cleanSnippet.contains(cleanCarrier.take(4)))) {
                    return true
                }
            }
        }

        return false
    }
}
