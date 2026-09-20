package com.omnidocs.app.agent

import android.util.Log
import com.omnidocs.app.ai.AiOutputProcessor
import com.omnidocs.app.ai.LlamaCppService
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.ai.PromptBudget
import com.omnidocs.app.ai.PromptBuilder
import com.omnidocs.app.ai.StopStringFilter
import com.omnidocs.app.ai.ThinkingStreamFilter
import com.omnidocs.app.ai.resolveActiveModel
import com.omnidocs.app.search.VectorSearch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimum top-candidate score for the rule-based extract fallback (no model
 * downloaded). Matches the retrieval floor: anything RetrievalAgent emits
 * is quotable. Below this the evidence is too weak to quote, so abstain.
 */
private const val RULE_BASED_MIN_SCORE = VectorSearch.MIN_COMBINED_SCORE

/**
 * Cap on citations forwarded to generation: more sources = bigger prompt
 * (longer prefill) and the model only reliably cites the first handful.
 */
private const val MAX_CITATIONS = 8

/**
 * Native context window (llama_jni.cpp n_ctx). Generation may never request
 * more than the room left after the prompt: the native loop breaks at
 * max_ctx - 2, cutting the answer mid-sentence with no diagnostic.
 */
internal const val N_CTX_TOKENS = 8192

/** Default generation ceiling for a grounded answer (thinking disabled). */
private const val ANSWER_MAX_TOKENS = 1500

/** Safety margin between prompt estimate and the context ceiling. */
private const val CTX_SAFETY_MARGIN_TOKENS = 128

/** Floor so a pathological prompt estimate still yields a short answer. */
private const val ANSWER_MIN_TOKENS = 256

/**
 * A think block opening within this many visible chars means the model
 * defeated the no-think prefill (reasoning first). Later opens may be
 * quoted note content, which must not trigger a retry.
 */
private const val THINK_REOPEN_EARLY_CHARS = 200

/**
 * Requested generation tokens for a prompt of [promptChars] chars: the
 * default ceiling, shrunk to fit whatever room the prompt leaves in the
 * context window. Requesting more than fits is the classic truncation:
 * the native loop hits the ceiling mid-sentence.
 */
internal fun computeRequestedMaxTokens(promptChars: Int): Int {
    val promptTokens = PromptBudget.estimateTokens(promptChars)
    val availableRoom = (N_CTX_TOKENS - promptTokens - CTX_SAFETY_MARGIN_TOKENS).coerceAtLeast(0)
    return minOf(ANSWER_MAX_TOKENS, availableRoom)
        .coerceAtLeast(minOf(ANSWER_MIN_TOKENS, availableRoom).coerceAtLeast(128))
}

internal val ABSTENTION_REGEX = Regex(
    """(?i)(?:based on (?:the|your)?\s*(?:provided )?(?:notes|workspace)[^,.:\n]*[,:]?\s*)?""" +
    """(?:(?:there is|i have|i found)\s+)?(?:insufficient|not enough)\s+(?:data|evidence|information|context)|""" +
    """no\s+(?:relevant|sufficient)\s+(?:evidence|information|data|notes)|""" +
    """(?:the|your)?\s*workspace does not contain|""" +
    """could not find (?:any )?(?:relevant|sufficient)\s+(?:evidence|information|data|notes)"""
)

/**
 * True when [answer] IS an abstention, not merely mentions one: it matches
 * semantic abstention phrasing (insufficient data/evidence/information, no relevant notes)
 * and carries no substantive claims after the marker. Previously any phrasing other than
 * "Insufficient evidence..." was ignored, causing abstentions to latch onto unrelated citations.
 */
internal fun isAbstentionOnly(answer: String): Boolean {
    val t = answer.trim()
    val match = ABSTENTION_REGEX.find(t) ?: return false
    if (match.range.first > 80) return false
    val sentenceEnd = t.indexOfAny(charArrayOf('.', '\n', '!', '?'), startIndex = match.range.last)
    if (sentenceEnd == -1) {
        val remainder = t.substring(match.range.last + 1).trim()
        return remainder.length <= 40
    }
    val remainder = t.substring(sentenceEnd + 1).trim()
    return remainder.length <= 15
}

/**
 * Normalize every citation shape small models actually use into 1-based
 * source indices: "[Source 3]", "[Sources 2-4]", "Sources: 1, 3, 5",
 * "Sources 1-3", bare "[2]". Previously only the exact "[Source N]" form
 * matched, so a compact citation ("[Sources 1-6]") silently collapsed the
 * citation list to the top-1 fallback.
 *
 * Shared with VerificationAgent (same package) so the two can never
 * disagree on which sources an answer references.
 */
internal fun parseReferencedSources(answer: String, maxSources: Int): Set<Int> {
    val found = mutableSetOf<Int>()

    fun add(n: Int) {
        // 1..maxSources as an IntRange allocates an array — Int.MAX_VALUE
        // would overflow it. The comparison form never materializes.
        if (n >= 1 && n <= maxSources) found.add(n)
    }
    fun addRange(a: Int, b: Int) {
        if (a <= b && b - a <= maxSources) for (n in a..b) add(n)
    }

    // "[Source 3]" / "[Sources 2-4]" (single form also picks up the lower bound)
    Regex("""\[Sources?\s+(\d+)\s*[-–]\s*(\d+)""", RegexOption.IGNORE_CASE).findAll(answer)
        .forEach { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@forEach
            val b = m.groupValues[2].toIntOrNull() ?: return@forEach
            addRange(a, b)
        }
    Regex("""\[Sources?\s+(\d+)""", RegexOption.IGNORE_CASE).findAll(answer)
        .forEach { add(it.groupValues[1].toIntOrNull() ?: 0) }
    // "Sources: 1, 3, 5" / "Sources: 2-4" / "Sources 1 and 3"
    Regex("""\bSources?\s*:?\s*([0-9,\s\-–and]+)""", RegexOption.IGNORE_CASE).findAll(answer)
        .forEach { m ->
            m.groupValues[1].split(Regex("[,\\s]+")).forEach { tok ->
                val range = Regex("""^(\d+)\s*[-–]\s*(\d+)$""").find(tok)
                if (range != null) {
                    val a = range.groupValues[1].toIntOrNull() ?: return@forEach
                    val b = range.groupValues[2].toIntOrNull() ?: return@forEach
                    addRange(a, b)
                } else {
                    add(tok.toIntOrNull() ?: 0)
                }
            }
        }
    // Bare "[3]"
    Regex("""\[(\d{1,2})\]""").findAll(answer)
        .forEach { add(it.groupValues[1].toIntOrNull() ?: 0) }

    return found
}

data class Citation(
    val noteId: String,
    val noteTitle: String,
    val blockId: String? = null,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
    val quoteSnippet: String,
    val quoteHash: String,
    /** Retrieval score carried at build time (never re-indexed). */
    val score: Double = 0.0,
    /** 1-based [Source N] number as shown to the model. */
    val sourceIndex: Int = 0
)

/**
 * Agent responsible for generating grounded answers strictly constrained
 * to supplied evidence candidates, outputting structured citations.
 */
@Singleton
class AnswerAgent @Inject constructor(
    private val llamaCppService: LlamaCppService,
    private val modelPreferences: ModelPreferences,
    private val modelDownloadManager: ModelDownloadManager
) : Agent {

    override val id: String = "agent_answer"

    override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Answer generation cancelled by user")
        }

        val query = input.payload["query"] ?: ""
        val candidatesJson = input.payload["candidatesJson"] ?: "[]"
        val language = input.payload["language"] ?: "en"
        val conversationHistoryJson = input.payload["conversationHistoryJson"] ?: "[]"

        val conversationHistory = try {
            val arr = JSONArray(conversationHistoryJson)
            val list = mutableListOf<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val role = item.optString("role", "user")
                val text = item.optString("text", "")
                if (text.isNotBlank()) {
                    list.add(role to text)
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }

        val candidatesArray = try {
            JSONArray(candidatesJson)
        } catch (e: Exception) {
            Log.w("AnswerAgent", "Malformed candidatesJson, treating as no candidates", e)
            JSONArray()
        }

        if (candidatesArray.length() == 0) {
            return AgentResult.Success(
                payload = mapOf(
                    "answer" to "No relevant notes or documents found to answer your question.",
                    "citationsJson" to "[]",
                    "confidence" to "LOW",
                    "insufficientEvidence" to true,
                    "ruleBased" to false
                )
            )
        }

        // Build grounded evidence context. Each citation carries its own
        // retrieval score and 1-based source index so later stages never
        // re-index into candidatesArray (skipped malformed entries shift it).
        val sources = mutableListOf<PromptBudget.EvidenceSource>()
        val citations = mutableListOf<Citation>()
        var sourceNumber = 0

        for (i in 0 until candidatesArray.length()) {
            // More sources mean a bigger prompt (slower prefill) and small
            // models only reliably cite the first handful anyway.
            if (sourceNumber >= MAX_CITATIONS) break
            // One malformed candidate must not abort the whole answer step.
            val obj = try {
                candidatesArray.getJSONObject(i)
            } catch (e: Exception) {
                Log.w("AnswerAgent", "Skipping malformed candidate at index $i", e)
                continue
            }
            val noteId = obj.optString("noteId", "")
            val noteTitle = obj.optString("noteTitle", "Untitled")
            val text = obj.optString("text", "")
            val score = obj.optDouble("score", 0.0)
            val blockId = obj.optString("blockId").takeIf { it.isNotBlank() }
            val startOffset = obj.optIntLenient("startOffset")
            val endOffset = obj.optIntLenient("endOffset")

            sourceNumber++
            sources.add(PromptBudget.EvidenceSource(noteTitle, text, score, sourceNumber))

            val quoteSnippet = text.take(PromptBudget.MAX_SOURCE_CHARS)
            val quoteHash = MessageDigest.getInstance("SHA-256")
                .digest(quoteSnippet.toByteArray())
                .joinToString("") { "%02x".format(it) }

            citations.add(
                Citation(
                    noteId = noteId,
                    noteTitle = noteTitle,
                    blockId = blockId,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    quoteSnippet = quoteSnippet,
                    quoteHash = quoteHash,
                    score = score,
                    sourceIndex = sourceNumber
                )
            )
        }

        if (citations.isEmpty()) {
            return AgentResult.Success(
                payload = mapOf(
                    "answer" to "No relevant notes or documents found to answer your question.",
                    "citationsJson" to "[]",
                    "confidence" to "LOW",
                    "insufficientEvidence" to true,
                    "ruleBased" to false
                )
            )
        }

        val model = resolveActiveModel(modelPreferences, modelDownloadManager)
        val topScore = citations.maxOf { it.score }

        val answerText: String
        var ruleBased = false
        var insufficient = false
        var answerIncomplete = false
        var stopReasonName = "not_generated"
        if (model != null) {
            val langInstruction = if (language.lowercase() != "en") "\nEnsure your answer is in $language language." else ""
            val systemPrompt = """You are a grounded knowledge assistant.
Answer the user's question directly and concisely using ONLY the provided sources. If the sources do not contain enough information, state clearly: "Insufficient evidence in your workspace."
Always cite sources by their [Source X] labels.

Rules:
• Provide a direct, factual answer without meta-commentary or filler.
• Do NOT include thinking tags (<think>...</think>), internal chain of thought, reasoning steps, or internal monologue.
• Do NOT repeat the question or generate duplicate answer variations.
• Do NOT simulate conversation turns, role tags, or Q&A loops.$langInstruction"""

            // Budget the prompt so the trailing Question: always fits the
            // native context window; worst sources are dropped first.
            val budgeted = PromptBudget.buildGroundedUserPrompt(query, sources, systemPrompt, conversationHistory)
            if (budgeted.truncated) {
                Log.w(
                    "AnswerAgent",
                    "Prompt budgeted: included ${budgeted.includedSources}/${sources.size} sources"
                )
            }
            val buildPromptFor = { reinforced: Boolean ->
                val sys = if (reinforced) {
                    systemPrompt + "\nAnswer directly in a single response. " +
                        "Do not include any reasoning, thinking tags, or internal monologue."
                } else {
                    systemPrompt
                }
                PromptBuilder.buildPrompt(model.promptFormat, sys, budgeted.userPrompt, model)
            }
            val prompt = buildPromptFor(false)

            // Never request more than fits: the native loop breaks at the
            // context ceiling mid-sentence, so the request must leave room
            // for the prompt already in the window.
            val requestedMaxTokens = computeRequestedMaxTokens(prompt.length)

            // Filter thinking spans live so neither the streamed UI nor the
            // collected answer (and its [Source X] citation parse) ever sees
            // them. AiOutputProcessor below remains as a backstop.
            data class AttemptResult(
                val raw: String?,
                val thinkReopenedEarly: Boolean,
                val streamFailed: Boolean
            )
            suspend fun runAttempt(reinforced: Boolean): AttemptResult {
                val attemptPrompt = if (reinforced) buildPromptFor(true) else prompt
                val attemptMaxTokens = computeRequestedMaxTokens(attemptPrompt.length)
                val thinkFilter = ThinkingStreamFilter()
                val stopFilter = StopStringFilter()
                var streamFailed = false
                val rawResult = buildString {
                    val collected = StringBuilder()
                    try {
                        // Pin the model the prompt was built for: a Settings change
                        // between prompt-building and generation must not execute
                        // template-A on weights-B.
                        llamaCppService.generateFlow(
                            attemptPrompt,
                            maxTokens = attemptMaxTokens,
                            modelId = model.id
                        ).collect { token ->
                            val visible = thinkFilter.feed(token)
                            if (visible.isNotEmpty()) {
                                val releasable = stopFilter.feed(visible)
                                if (releasable.isNotEmpty()) {
                                    collected.append(releasable)
                                    context.onAnswerToken(releasable)
                                }
                                if (stopFilter.stopped) {
                                    // Turn-continuation detected: halt natively so
                                    // it doesn't burn maxTokens + thermal budget.
                                    llamaCppService.stopGeneration()
                                }
                            }
                            val openedAt = thinkFilter.thinkOpenedAtVisibleChars
                            if (openedAt != -1 && openedAt <= THINK_REOPEN_EARLY_CHARS &&
                                !reinforced && collected.isEmpty()
                            ) {
                                // Defeated no-think prefill: reasoning first, eating
                                // the token budget invisibly. Halt now; the caller
                                // retries once with a reinforced prompt.
                                Log.w(
                                    "AnswerAgent",
                                    "Think block reopened at visible offset $openedAt; " +
                                        "halting for reinforced retry"
                                )
                                llamaCppService.stopGeneration()
                                return@collect
                            }
                        }
                    } catch (e: Exception) {
                        // Watchdog/timeout: keep partial tokens instead of failing.
                        Log.w("AnswerAgent", "Stream ended early, keeping partial answer", e)
                        streamFailed = true
                    }
                    val thinkTail = thinkFilter.flush()
                    if (thinkTail.isNotEmpty() && !stopFilter.stopped) {
                        val releasable = stopFilter.feed(thinkTail)
                        if (releasable.isNotEmpty()) {
                            collected.append(releasable)
                            context.onAnswerToken(releasable)
                        }
                    }
                    if (!stopFilter.stopped) {
                        val stopTail = stopFilter.flush()
                        if (stopTail.isNotEmpty()) {
                            collected.append(stopTail)
                            context.onAnswerToken(stopTail)
                        }
                    }
                    append(collected)
                }.ifBlank { null }
                val openedAt = thinkFilter.thinkOpenedAtVisibleChars
                return AttemptResult(
                    raw = rawResult,
                    thinkReopenedEarly = openedAt != -1 && openedAt <= THINK_REOPEN_EARLY_CHARS,
                    streamFailed = streamFailed
                )
            }

            var attempt = runAttempt(reinforced = false)
            val isSubstantive = (attempt.raw?.length ?: 0) >= 80
            if (attempt.thinkReopenedEarly && !isSubstantive) {
                // Nothing usable survived the hidden reasoning: one retry with
                // an explicit answer-directly instruction. The transient
                // streaming text may briefly show both passes; the final
                // message below carries only the retry's answer.
                Log.w("AnswerAgent", "Retrying with reinforced no-think prompt")
                attempt = runAttempt(reinforced = true)
            }
            val stopReason = llamaCppService.lastStopReason()
            val thermallyCapped = llamaCppService.wasLastStreamCapped()
            stopReasonName = com.omnidocs.app.ai.StopReason.name(stopReason)
            val promptTokens = PromptBudget.estimateTokens(prompt.length)
            Log.i(
                "AnswerAgent",
                "Generation done: promptTokens~$promptTokens requested=$requestedMaxTokens " +
                    "capped=$thermallyCapped stop=$stopReasonName " +
                    "chars=${attempt.raw?.length ?: 0} thinkReopen=${attempt.thinkReopenedEarly}"
            )
            answerIncomplete = attempt.streamFailed || thermallyCapped ||
                com.omnidocs.app.ai.StopReason.isCutOff(stopReason)
            if (llamaCppService.wasPromptTruncated()) {
                Log.w("AnswerAgent", "Native layer truncated the prompt despite budgeting")
            }
            val processed = attempt.raw?.let {
                val cleaned = AiOutputProcessor.process(it)
                if (answerIncomplete) AiOutputProcessor.trimDanglingIncompleteText(cleaned) else cleaned
            }
            if (processed.isNullOrBlank()) {
                // The model abstained, emitted only thinking, or the stream
                // died with zero tokens: never fabricate a cited answer.
                answerText = "Insufficient evidence in your workspace to answer this question."
                insufficient = true
            } else {
                answerText = processed
            }
        } else if (topScore >= RULE_BASED_MIN_SCORE) {
            // No model downloaded: quote the top source verbatim, labeled.
            ruleBased = true
            answerText = generateRuleBasedAnswer(citations)
        } else {
            answerText = "No model is downloaded and the retrieved evidence is too weak to quote. Download a model in Settings to get grounded answers."
            insufficient = true
        }

        val isInsufficient = insufficient ||
            // "No relevant notes" from the empty-candidate branch counts too:
            // propagate it so the coordinator never claims HIGH confidence on
            // a stub answer that happens to contain a nearby phrase.
            answerText.startsWith("No relevant", ignoreCase = true) ||
            isAbstentionOnly(answerText)

        // Parse which sources the LLM actually referenced. Small models cite
        // in many shapes: "[Source 1]", "[Sources 2-4]", "Sources: 1, 3, 5",
        // bare "[1]". All are normalized to 1-based source indices.
        val referencedSourceIndices = if (model != null && !isInsufficient) {
            parseReferencedSources(answerText, citations.size)
        } else if (ruleBased) {
            setOfNotNull(citations.indices.firstOrNull()?.let { citations[it].sourceIndex })
        } else {
            emptySet()
        }

        // Emit only citations the LLM actually used. On abstention/insufficient evidence,
        // suppress citations completely so unrelated notes are never displayed as sources.
        val filteredCitations = if (isInsufficient) {
            emptyList()
        } else if (referencedSourceIndices.isNotEmpty()) {
            citations.filter { it.sourceIndex in referencedSourceIndices }
        } else if (ruleBased) {
            citations.take(1)
        } else {
            citations.take(1)
        }

        // Confidence from the carried retrieval score of the cited source.
        val citedScore = filteredCitations.maxOfOrNull { it.score } ?: 0.0
        val confidence = when {
            isInsufficient || ruleBased -> "LOW"
            citedScore >= 0.4 -> "HIGH"
            citedScore >= 0.2 -> "MEDIUM"
            else -> "LOW"
        }

        val citationsJsonArray = JSONArray()
        for (c in filteredCitations) {
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
            citationsJsonArray.put(obj)
        }

        return AgentResult.Success(
            payload = mapOf(
                "answer" to answerText,
                "citationsJson" to citationsJsonArray.toString(),
                "confidence" to confidence,
                "insufficientEvidence" to isInsufficient,
                "ruleBased" to ruleBased,
                "answerIncomplete" to answerIncomplete,
                "stopReason" to stopReasonName
            )
        )
    }

    private fun generateRuleBasedAnswer(citations: List<Citation>): String {
        val top = citations.firstOrNull() ?: return "No relevant notes found."
        return "Based on [Source ${top.sourceIndex}: ${top.noteTitle}]:\n\"${top.quoteSnippet}...\""
    }
}
