package com.omnidocs.app.knowledge

import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.NoteLinkDao
import com.omnidocs.app.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects orphan notes -- notes with no incoming or outgoing links.
 * These are candidates for connection to other notes.
 */
@Singleton
class OrphanDetector @Inject constructor(
    private val noteDao: NoteDao,
    private val noteLinkDao: NoteLinkDao
) {
    data class OrphanNote(
        val note: NoteEntity,
        val linkCount: Int, // 0 = completely orphaned
        val potentialConnections: List<String> // note IDs that might be related
    )

    /**
     * Find all orphan notes (no links to/from other notes).
     */
    suspend fun findOrphans(): List<OrphanNote> {
        val allNotes = noteDao.getAllNotesSync()
        val orphans = mutableListOf<OrphanNote>()

        for (note in allNotes) {
            // .first() -- a DAO Flow is infinite, so collect {} here hung forever
            // and no orphans were ever reported.
            val linkCount = noteLinkDao.getLinksForNote(note.id).first().size

            if (linkCount == 0) {
                orphans.add(OrphanNote(
                    note = note,
                    linkCount = 0,
                    potentialConnections = emptyList()
                ))
            }
        }

        return orphans
    }
}
