package com.omnidocs.app.sync

import com.omnidocs.app.data.local.entity.NoteEntity
import javax.inject.Inject
import javax.inject.Singleton

enum class ConflictField {
    TITLE,
    CONTENT,
    TAGS
}

enum class ConflictResolutionChoice {
    KEEP_LOCAL,
    KEEP_REMOTE,
    CONCATENATE_BOTH,
    USE_CUSTOM
}

data class FieldConflict(
    val field: ConflictField,
    val baseValue: String?,
    val localValue: String?,
    val remoteValue: String?,
    val isConflicted: Boolean
)

data class NoteConflictReport(
    val noteId: String,
    val fieldConflicts: List<FieldConflict>,
    val hasConflicts: Boolean,
    val canAutoMerge: Boolean
)

data class NoteMergeResult(
    val noteId: String,
    val title: String,
    val content: String,
    val tags: String,
    val isResolved: Boolean,
    val resolvedAt: Long = System.currentTimeMillis()
)

/**
 * Service for detecting and resolving 3-way synchronization conflicts between
 * local offline edits and remote/external versions of notes.
 */
@Singleton
class NoteConflictResolver @Inject constructor() {

    /**
     * Performs a 3-way comparison between Base (common ancestor), Local, and Remote note states.
     */
    fun detectConflicts(
        baseNote: NoteEntity?,
        localNote: NoteEntity,
        remoteNote: NoteEntity
    ): NoteConflictReport {
        val conflicts = mutableListOf<FieldConflict>()

        // 1. Title
        conflicts.add(
            checkFieldConflict(
                field = ConflictField.TITLE,
                base = baseNote?.title,
                local = localNote.title,
                remote = remoteNote.title
            )
        )

        // 2. Content
        conflicts.add(
            checkFieldConflict(
                field = ConflictField.CONTENT,
                base = baseNote?.content,
                local = localNote.content,
                remote = remoteNote.content
            )
        )

        // 3. Tags
        conflicts.add(
            checkFieldConflict(
                field = ConflictField.TAGS,
                base = baseNote?.tags,
                local = localNote.tags,
                remote = remoteNote.tags
            )
        )

        val hasConflicts = conflicts.any { it.isConflicted }
        val canAutoMerge = !hasConflicts

        return NoteConflictReport(
            noteId = localNote.id,
            fieldConflicts = conflicts,
            hasConflicts = hasConflicts,
            canAutoMerge = canAutoMerge
        )
    }

    /**
     * Resolves conflicts by applying explicit user choices or custom merged strings.
     */
    fun resolveConflicts(
        report: NoteConflictReport,
        localNote: NoteEntity,
        remoteNote: NoteEntity,
        choices: Map<ConflictField, ConflictResolutionChoice> = emptyMap(),
        customValues: Map<ConflictField, String> = emptyMap()
    ): NoteMergeResult {
        var mergedTitle = localNote.title
        var mergedContent = localNote.content
        var mergedTags = localNote.tags

        for (fc in report.fieldConflicts) {
            val choice = choices[fc.field] ?: if (!fc.isConflicted) {
                // Auto-resolve non-conflicting fields: if local equals base, take remote; else keep local
                if (fc.localValue == fc.baseValue) ConflictResolutionChoice.KEEP_REMOTE else ConflictResolutionChoice.KEEP_LOCAL
            } else {
                ConflictResolutionChoice.KEEP_LOCAL
            }

            val resolvedVal = when (choice) {
                ConflictResolutionChoice.KEEP_LOCAL -> fc.localValue ?: ""
                ConflictResolutionChoice.KEEP_REMOTE -> fc.remoteValue ?: ""
                ConflictResolutionChoice.USE_CUSTOM -> customValues[fc.field] ?: fc.localValue ?: ""
                ConflictResolutionChoice.CONCATENATE_BOTH -> {
                    val l = fc.localValue ?: ""
                    val r = fc.remoteValue ?: ""
                    if (l == r) l else "$l\n\n--- MERGED VERSION ---\n\n$r"
                }
            }

            when (fc.field) {
                ConflictField.TITLE -> mergedTitle = resolvedVal
                ConflictField.CONTENT -> mergedContent = resolvedVal
                ConflictField.TAGS -> mergedTags = resolvedVal
            }
        }

        return NoteMergeResult(
            noteId = localNote.id,
            title = mergedTitle,
            content = mergedContent,
            tags = mergedTags,
            isResolved = true
        )
    }

    /**
     * Formats conflicting text blocks using standard Git-style conflict markers.
     */
    fun formatConflictMarkers(localText: String, remoteText: String): String {
        if (localText == remoteText) return localText
        return buildString {
            appendLine("<<<<<<< LOCAL CHANGES")
            appendLine(localText.trim())
            appendLine("=======")
            appendLine(remoteText.trim())
            appendLine(">>>>>>> REMOTE CHANGES")
        }.trim()
    }

    private fun checkFieldConflict(
        field: ConflictField,
        base: String?,
        local: String?,
        remote: String?
    ): FieldConflict {
        // If local and remote are identical, no conflict
        if (local == remote) {
            return FieldConflict(field, base, local, remote, isConflicted = false)
        }

        // If only remote changed from base, auto-resolves cleanly
        if (local == base && remote != base) {
            return FieldConflict(field, base, local, remote, isConflicted = false)
        }

        // If only local changed from base, auto-resolves cleanly
        if (remote == base && local != base) {
            return FieldConflict(field, base, local, remote, isConflicted = false)
        }

        // Both local and remote changed from base, and they differ: genuine conflict!
        return FieldConflict(field, base, local, remote, isConflicted = true)
    }
}
