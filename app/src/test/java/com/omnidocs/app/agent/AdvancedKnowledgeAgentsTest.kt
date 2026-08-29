package com.omnidocs.app.agent

import com.omnidocs.app.data.local.AiArtifactDao
import com.omnidocs.app.data.local.ClaimDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.NoteLinkDao
import com.omnidocs.app.data.local.entity.ClaimEntity
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.knowledge.IdeaEvolution
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class AdvancedKnowledgeAgentsTest {

    private lateinit var noteDao: NoteDao
    private lateinit var claimDao: ClaimDao
    private lateinit var noteLinkDao: NoteLinkDao
    private lateinit var aiArtifactDao: AiArtifactDao
    private lateinit var ideaEvolution: IdeaEvolution

    @Before
    fun setup() {
        noteDao = mock(NoteDao::class.java)
        claimDao = mock(ClaimDao::class.java)
        noteLinkDao = mock(NoteLinkDao::class.java)
        aiArtifactDao = mock(AiArtifactDao::class.java)
        ideaEvolution = IdeaEvolution(noteDao, claimDao, noteLinkDao)
    }

    @Test
    fun `idea evolution agent traces concept history, decision chain, and linked notes`() = runTest {
        val now = System.currentTimeMillis()
        val note1 = NoteEntity(
            id = "note_1",
            title = "RAG Architecture Plan",
            content = "Discussion on offline vector search and BM25 scoring.",
            plainText = "Discussion on offline vector search and BM25 scoring.",
            isPinned = false,
            language = "en",
            createdAt = now - 100000L,
            updatedAt = now - 100000L,
            imageUrl = null
        )

        `when`(noteDao.getAllNotesSync()).thenReturn(listOf(note1))
        `when`(noteLinkDao.getLinksForNote("note_1")).thenReturn(flowOf(emptyList()))
        `when`(claimDao.getClaimsByNoteId("note_1")).thenReturn(
            flowOf(
                listOf(
                    ClaimEntity(
                        id = "claim_1",
                        noteId = "note_1",
                        text = "Decided to use multilingual-e5-small model",
                        claimType = "decision",
                        confidence = 0.95f,
                        status = "user_approved",
                        createdAt = now,
                        updatedAt = now
                    )
                )
            )
        )

        val agent = IdeaEvolutionAgent(ideaEvolution)
        val result = agent.execute(
            input = AgentInput(type = "EVOLUTION_INPUT", payload = mapOf("concept" to "vector search")),
            context = AgentContext(jobId = "evo_job")
        )

        assertTrue(result is AgentResult.Success)
        val payload = (result as AgentResult.Success).payload
        assertEquals("vector search", payload["concept"])
        assertEquals("note_1", payload["firstMentionNoteId"])
        assertEquals(1, payload["decisionsCount"])
        assertTrue((payload["status"] as String).contains("Decided"))
    }
}
