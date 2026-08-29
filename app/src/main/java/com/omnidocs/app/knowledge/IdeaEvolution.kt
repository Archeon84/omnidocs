package com.omnidocs.app.knowledge

import com.omnidocs.app.data.local.ClaimDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.NoteLinkDao
import com.omnidocs.app.data.local.entity.ClaimEntity
import com.omnidocs.app.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks how ideas evolve across notes over time.
 * Finds related notes, supporting/contradicting evidence, and decision chains.
 */
@Singleton
class IdeaEvolution @Inject constructor(
    private val noteDao: NoteDao,
    private val claimDao: ClaimDao,
    private val noteLinkDao: NoteLinkDao
) {
    /**
     * Trace the evolution of an idea across notes.
     */
    data class IdeaTimeline(
        val concept: String,
        val firstMention: NoteEntity?,
        val relatedNotes: List<NoteEntity>,
        val supportingEvidence: List<ClaimEntity>,
        val contradictingEvidence: List<ClaimEntity>,
        val decisions: List<ClaimEntity>,
        val currentStatus: String
    )

    /**
     * Build an idea evolution timeline for a given concept.
     */
    suspend fun traceEvolution(concept: String): IdeaTimeline {
        // Find notes mentioning this concept
        val allNotes = noteDao.getAllNotesSync()
        val relatedNotes = allNotes.filter { note ->
            note.plainText.contains(concept, ignoreCase = true) ||
            note.title.contains(concept, ignoreCase = true)
        }.sortedBy { it.createdAt }

        val firstMention = relatedNotes.firstOrNull()

        // Find related notes via links. Room single-query Flows never complete,
        // so collect{} would hang forever; first() takes the current result set
        // and returns — exactly the wanted one-shot semantics.
        val linkedNotes = mutableListOf<NoteEntity>()
        for (note in relatedNotes) {
            val links = noteLinkDao.getLinksForNote(note.id).first()
            for (link in links) {
                val targetId = if (link.sourceNoteId == note.id) link.targetNoteId else link.sourceNoteId
                val target = noteDao.getNoteById(targetId)
                if (target != null && target.id !in linkedNotes.map { it.id }) {
                    linkedNotes.add(target)
                }
            }
        }

        // Find claims from related notes
        val allRelatedClaims = mutableListOf<ClaimEntity>()
        for (note in relatedNotes) {
            allRelatedClaims.addAll(claimDao.getClaimsByNoteId(note.id).first())
        }

        val supporting = allRelatedClaims.filter {
            it.claimType == "fact" && it.status == "user_approved"
        }
        val decisions = allRelatedClaims.filter {
            it.claimType == "decision"
        }
        val contradicting = allRelatedClaims.filter {
            it.claimType == "interpretation" && it.confidence < 0.5f
        }

        val status = when {
            decisions.isNotEmpty() -> "Decided (${decisions.size} decisions made)"
            contradicting.isNotEmpty() -> "Under discussion (${contradicting.size} conflicting views)"
            relatedNotes.size > 3 -> "Active (${relatedNotes.size} related notes)"
            relatedNotes.isNotEmpty() -> "Emerging (${relatedNotes.size} mentions)"
            else -> "New"
        }

        return IdeaTimeline(
            concept = concept,
            firstMention = firstMention,
            relatedNotes = relatedNotes + linkedNotes.distinctBy { it.id },
            supportingEvidence = supporting,
            contradictingEvidence = contradicting,
            decisions = decisions,
            currentStatus = status
        )
    }
}
