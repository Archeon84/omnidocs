package com.omnidocs.app.ai

import android.util.Log
import androidx.room.withTransaction
import com.omnidocs.app.data.local.ActionItemDao
import com.omnidocs.app.data.local.ClaimDao
import com.omnidocs.app.data.local.EntityDao
import com.omnidocs.app.data.local.EntityMentionDao
import com.omnidocs.app.data.local.EvidenceLinkDao
import com.omnidocs.app.data.local.NotesDatabase
import com.omnidocs.app.data.local.entity.ActionItemEntity
import com.omnidocs.app.data.local.entity.ClaimEntity
import com.omnidocs.app.data.local.entity.EntityEntity
import com.omnidocs.app.data.local.entity.EntityMentionEntity
import com.omnidocs.app.data.local.entity.EvidenceLinkEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "EvidenceExtractor"

/**
 * Extracts evidence-first knowledge from notes: decisions, tasks, questions,
 * entities, and evidence links. Every AI-generated claim links back to its source.
 *
 * Runs on every note save, so it replaces the note's previous AI-extracted rows
 * (claims/action-items/mentions/evidence) inside a single transaction instead of
 * inserting duplicates. User-approved claims and user-acted action items are
 * preserved (they are not in the deleted statuses).
 */
@Singleton
class EvidenceExtractor @Inject constructor(
    private val llamaCppService: LlamaCppService,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val claimDao: ClaimDao,
    private val evidenceLinkDao: EvidenceLinkDao,
    private val actionItemDao: ActionItemDao,
    private val entityDao: EntityDao,
    private val entityMentionDao: EntityMentionDao,
    private val notesDatabase: NotesDatabase
) {
    // Fire-and-forget scope for post-save evidence extraction. SupervisorJob so a
    // failed extraction never kills later extractions; outlives the ViewModel so
    // navigation doesn't cancel the slow offline-LLM call.
    private val extractionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Prevents concurrent GGML invocations. compareAndSet(false, true) succeeds only
    // for one caller at a time; the flag is cleared in the finally block.
    private val isExtracting = java.util.concurrent.atomic.AtomicBoolean(false)

    // Per-note hash of the last submitted text. The old single global hash
    // suppressed legitimate work across different notes on hash match.
    private val lastExtractedHash = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // Single coalescing slot: while an extraction runs, newer requests replace
    // (not pile behind) the pending one — extraction is expensive, so only the
    // latest state per burst is worth processing. Never silently dropped.
    private data class PendingExtraction(
        val noteId: String,
        val text: String,
        val language: String,
        val hash: Int
    )
    private val pending = java.util.concurrent.atomic.AtomicReference<PendingExtraction?>(null)

    /**
     * Fire-and-forget variant of [extractFromNote]. Runs off the ViewModel scope so
     * a back-navigation (which cancels viewModelScope) never aborts extraction, and
     * the slow offline-LLM call never blocks save/navigation.
     *
     * Skips only when this note's content hash is unchanged since its last run;
     * otherwise the request is coalesced into the pending slot and processed
     * next. Successive GGML invocations stay serialized via [isExtracting].
     */
    fun extractFromNoteAsync(noteId: String, text: String, language: String = "en") {
        val contentHash = text.hashCode()
        if (lastExtractedHash[noteId] == contentHash) {
            Log.d(TAG, "Content unchanged since last extraction, skipping")
            return
        }
        pending.set(PendingExtraction(noteId, text, language, contentHash))
        if (!isExtracting.compareAndSet(false, true)) {
            Log.d(TAG, "Extraction in progress; coalesced pending request for $noteId")
            return
        }
        extractionScope.launch {
            try {
                drainPending()
            } finally {
                isExtracting.set(false)
                // Handoff race: work may have landed after the final drain but
                // before the flag cleared. Re-check and restart if so.
                if (pending.get() != null && isExtracting.compareAndSet(false, true)) {
                    extractionScope.launch {
                        try {
                            drainPending()
                        } finally {
                            isExtracting.set(false)
                        }
                    }
                }
            }
        }
    }

    /** Process the coalesced pending request, then any that arrived meanwhile. */
    private suspend fun drainPending() {
        while (true) {
            val next = pending.getAndSet(null) ?: break
            // A newer save may have made this request stale; re-check cheaply.
            if (lastExtractedHash[next.noteId] == next.hash) continue
            lastExtractedHash[next.noteId] = next.hash
            extractFromNote(next.noteId, next.text, next.language)
        }
    }

    private suspend fun getActiveModel(): ModelInfo? {
        return resolveActiveModel(modelPreferences, modelDownloadManager)
    }

    /**
     * Result of evidence extraction for a single note.
     */
    data class ExtractionResult(
        val claims: List<ClaimEntity>,
        val actionItems: List<ActionItemEntity>,
        val entities: List<EntityEntity>,
        val mentions: List<EntityMentionEntity>
    )

    /**
     * Extract evidence from a note's text content.
     * Creates ClaimEntity, ActionItemEntity, EntityEntity, and EvidenceLinkEntity records.
     */
    suspend fun extractFromNote(
        noteId: String,
        text: String,
        language: String = "en"
    ): ExtractionResult? {
        val model = getActiveModel() ?: return null

        val systemPrompt = """You are an evidence-first knowledge extraction system. Analyze the text and extract structured knowledge.

Return a JSON object with these arrays:
{
  "claims": [
    {"text": "extracted claim", "type": "decision|fact|question|interpretation", "confidence": 0.0-1.0, "evidence_quote": "exact quote from text"}
  ],
  "tasks": [
    {"title": "task title", "description": "task description", "owner": "person name or null", "deadline": "date string or null", "priority": "low|medium|high|urgent"}
  ],
  "entities": [
    {"name": "entity name", "type": "person|organization|project|place|concept|product|event"}
  ]
}

Rules:
- Do NOT invent facts, decisions, or deadlines not stated in the text
- Every claim MUST have a direct evidence quote from the text
- Set confidence based on how explicitly the text states the claim
- Extract ALL action items, decisions, questions, and named entities
- For tasks, only extract items that clearly require action
- Return ONLY valid JSON, no other text"""

        val userPrompt = "Extract evidence from this text:\n\n${truncateText(text, 3000)}"

        return try {
            val prompt = PromptBuilder.buildPrompt(model.promptFormat, systemPrompt, userPrompt)
            val result = llamaCppService.generate(prompt, maxTokens = 2000) ?: return null
            val cleaned = AiOutputProcessor.process(result)
            parseAndStore(noteId, cleaned, text)
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            null
        }
    }

    /**
     * Parse LLM JSON output and store in database. Replaces the note's previous
     * AI-extracted rows atomically so repeated saves don't accumulate duplicates.
     */
    private suspend fun parseAndStore(noteId: String, jsonOutput: String, text: String): ExtractionResult? {
        return try {
            val jsonStr = jsonOutput
                .replace(Regex("```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()
            val obj = JSONObject(jsonStr)
            val now = System.currentTimeMillis()

            val result = notesDatabase.withTransaction {
                // Delete this note's previous auto-extracted rows (user-approved
                // claims and user-acted action items are preserved), then insert
                // the fresh set. Atomic so a crash can't leave a half state.
                // Capture the replaced claim IDs BEFORE the delete so evidence links
                // are scoped to exactly those claims -- a blanket per-source delete
                // would wipe the source quotes that approved claims still reference.
                val replacedClaimIds = claimDao.getAiSuggestedClaimIdsByNoteId(noteId)
                claimDao.deleteAiSuggestedClaimsByNoteId(noteId)
                actionItemDao.deletePendingActionItemsByNoteId(noteId)
                entityMentionDao.deleteMentionsByNoteId(noteId)
                if (replacedClaimIds.isNotEmpty()) {
                    evidenceLinkDao.deleteEvidenceByClaimIds(replacedClaimIds)
                }

                // Parse claims
                val claims = mutableListOf<ClaimEntity>()
                val claimsArr = obj.optJSONArray("claims") ?: JSONArray()
                for (i in 0 until claimsArr.length()) {
                    val c = claimsArr.getJSONObject(i)
                    val claimId = UUID.randomUUID().toString()
                    val claim = ClaimEntity(
                        id = claimId,
                        noteId = noteId,
                        text = c.optString("text", ""),
                        claimType = c.optString("type", "fact"),
                        confidence = c.optDouble("confidence", 0.5).toFloat(),
                        status = "ai_suggested",
                        createdAt = now,
                        updatedAt = now
                    )
                    claims.add(claim)

                    // Create evidence link from quote
                    val quote = c.optString("evidence_quote", "")
                    if (quote.isNotBlank()) {
                        val (qStart, qEnd) = findOffsets(text, quote)
                        val link = EvidenceLinkEntity(
                            id = UUID.randomUUID().toString(),
                            claimId = claimId,
                            sourceType = "note",
                            sourceId = noteId,
                            startOffset = qStart,
                            endOffset = qEnd,
                            startMs = null,
                            endMs = null,
                            quoteHash = quote.hashCode().toString(),
                            relevanceScore = claim.confidence,
                            createdAt = now
                        )
                        evidenceLinkDao.insertEvidenceLink(link)
                    }
                }
                claimDao.insertClaims(claims)

                // Parse action items
                val actionItems = mutableListOf<ActionItemEntity>()
                val tasksArr = obj.optJSONArray("tasks") ?: JSONArray()
                for (i in 0 until tasksArr.length()) {
                    val t = tasksArr.getJSONObject(i)
                    val item = ActionItemEntity(
                        id = UUID.randomUUID().toString(),
                        noteId = noteId,
                        claimId = null,
                        title = t.optString("title", ""),
                        description = t.optString("description", ""),
                        owner = t.optString("owner", null),
                        dueAt = null, // TODO: parse deadline string to timestamp
                        status = "pending",
                        priority = t.optString("priority", "medium"),
                        createdAt = now,
                        updatedAt = now
                    )
                    actionItems.add(item)
                }
                actionItemDao.insertActionItems(actionItems)

                // Parse entities
                val entities = mutableListOf<EntityEntity>()
                val mentions = mutableListOf<EntityMentionEntity>()
                val entitiesArr = obj.optJSONArray("entities") ?: JSONArray()
                for (i in 0 until entitiesArr.length()) {
                    val e = entitiesArr.getJSONObject(i)
                    val name = e.optString("name", "")
                    if (name.isBlank()) continue

                    // Check if entity already exists
                    val existing = entityDao.getEntityByName(name)
                    val entityId = existing?.id ?: UUID.randomUUID().toString()

                    if (existing == null) {
                        val entity = EntityEntity(
                            id = entityId,
                            entityType = e.optString("type", "concept"),
                            canonicalName = name,
                            createdAt = now,
                            updatedAt = now
                        )
                        entities.add(entity)
                    }

                    // Locate the entity name in the note text for a real offset
                    // (placeholder 0/name.length pointed nowhere).
                    val (mStart, mEnd) = findOffsets(text, name)
                    val mention = EntityMentionEntity(
                        id = UUID.randomUUID().toString(),
                        entityId = entityId,
                        noteId = noteId,
                        transcriptSegmentId = null,
                        startOffset = mStart,
                        endOffset = mEnd,
                        confidence = 0.8f
                    )
                    mentions.add(mention)
                }
                entityDao.insertEntities(entities)
                entityMentionDao.insertMentions(mentions)

                ExtractionResult(claims, actionItems, entities, mentions)
            }

            Log.d(TAG, "Extracted: ${result.claims.size} claims, ${result.actionItems.size} tasks, ${result.entities.size} entities")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse extraction JSON: ${jsonOutput.take(200)}", e)
            null
        }
    }

    /**
     * Find the character offsets of [needle] in [text] (case-insensitive, first
     * occurrence). Falls back to (0, length) when not found so the row still
     * has sane non-null values.
     */
    private fun findOffsets(text: String, needle: String): Pair<Int, Int> {
        if (needle.isBlank()) return 0 to 0
        val idx = text.indexOf(needle, ignoreCase = true)
        return if (idx >= 0) idx to (idx + needle.length) else 0 to needle.length
    }

    private fun truncateText(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "\n\n[Text truncated — showing first $maxChars characters]"
    }
}
