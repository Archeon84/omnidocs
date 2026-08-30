package com.omnidocs.app.sync

import com.omnidocs.app.data.local.entity.NoteEntity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NoteConflictResolverTest {

    private lateinit var resolver: NoteConflictResolver

    @Before
    fun setUp() {
        resolver = NoteConflictResolver()
    }

    private fun createDummyNote(
        id: String = "note_100",
        title: String = "Project Roadmap",
        content: String = "# Roadmap\n- Phase 1\n- Phase 2",
        tags: String = "[\"roadmap\", \"planning\"]"
    ): NoteEntity {
        return NoteEntity(
            id = id,
            title = title,
            content = content,
            plainText = "",
            isPinned = false,
            language = "en",
            createdAt = 1000L,
            updatedAt = 2000L,
            imageUrl = null,
            tags = tags
        )
    }

    @Test
    fun testDetectConflicts_cleanAutoMergeWhenOnlyRemoteChanged() {
        val base = createDummyNote(title = "Original Title", content = "Original Content")
        val local = createDummyNote(title = "Original Title", content = "Original Content")
        val remote = createDummyNote(title = "Updated Remote Title", content = "Original Content")

        val report = resolver.detectConflicts(base, local, remote)

        assertFalse(report.hasConflicts)
        assertTrue(report.canAutoMerge)

        val titleConflict = report.fieldConflicts.first { it.field == ConflictField.TITLE }
        assertFalse(titleConflict.isConflicted)
        assertEquals("Updated Remote Title", titleConflict.remoteValue)

        val mergeResult = resolver.resolveConflicts(report, local, remote)
        assertEquals("Updated Remote Title", mergeResult.title)
        assertEquals("Original Content", mergeResult.content)
    }

    @Test
    fun testDetectConflicts_flagsConflictWhenBothSidesModifiedFieldDifferently() {
        val base = createDummyNote(
            title = "Q3 Budget",
            content = "Allocated budget: 50k"
        )
        val local = createDummyNote(
            title = "Q3 Budget (Local Revised)",
            content = "Allocated budget: 60k for engineering"
        )
        val remote = createDummyNote(
            title = "Q3 Budget (Remote Revised)",
            content = "Allocated budget: 75k for marketing"
        )

        val report = resolver.detectConflicts(base, local, remote)

        assertTrue(report.hasConflicts)
        assertFalse(report.canAutoMerge)

        val titleField = report.fieldConflicts.first { it.field == ConflictField.TITLE }
        assertTrue(titleField.isConflicted)

        val contentField = report.fieldConflicts.first { it.field == ConflictField.CONTENT }
        assertTrue(contentField.isConflicted)

        // Resolve title with KEEP_LOCAL, content with CONCATENATE_BOTH
        val choices = mapOf(
            ConflictField.TITLE to ConflictResolutionChoice.KEEP_LOCAL,
            ConflictField.CONTENT to ConflictResolutionChoice.CONCATENATE_BOTH
        )

        val resolved = resolver.resolveConflicts(report, local, remote, choices)

        assertEquals("Q3 Budget (Local Revised)", resolved.title)
        assertTrue(resolved.content.contains("Allocated budget: 60k for engineering"))
        assertTrue(resolved.content.contains("--- MERGED VERSION ---"))
        assertTrue(resolved.content.contains("Allocated budget: 75k for marketing"))
    }

    @Test
    fun testFormatConflictMarkers_generatesGitStyleMarkers() {
        val local = "Local paragraph text A."
        val remote = "Remote paragraph text B."

        val formatted = resolver.formatConflictMarkers(local, remote)

        assertTrue(formatted.contains("<<<<<<< LOCAL CHANGES"))
        assertTrue(formatted.contains("Local paragraph text A."))
        assertTrue(formatted.contains("======="))
        assertTrue(formatted.contains("Remote paragraph text B."))
        assertTrue(formatted.contains(">>>>>>> REMOTE CHANGES"))
    }
}
