package com.omnidocs.app.agent

import com.omnidocs.app.data.local.NoteDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*

class ImportPipelineCancelTest {

    private lateinit var agentCoordinator: AgentCoordinator
    private lateinit var pipeline: ImportPipelineCoordinator

    @Before
    fun setUp() {
        agentCoordinator = mock(AgentCoordinator::class.java)
        pipeline = ImportPipelineCoordinator(
            agentCoordinator = agentCoordinator,
            captureAgent = mock(CaptureAgent::class.java),
            extractionAgent = mock(ExtractionAgent::class.java),
            normalizationAgent = mock(NormalizationAgent::class.java),
            organizationAgent = mock(OrganizationAgent::class.java),
            indexingAgent = mock(IndexingAgent::class.java),
            noteDao = mock(NoteDao::class.java)
        )
    }

    @Test
    fun cancelActiveImport_whenIdle_doesNotTouchCoordinator() = runBlocking {
        pipeline.cancelActiveImport()

        verify(agentCoordinator, never()).cancelJob(anyString())
    }

    @Test
    fun takeCreatedNoteId_whenNothingCreated_returnsNull() {
        assertNull(pipeline.takeCreatedNoteId())
    }
}
