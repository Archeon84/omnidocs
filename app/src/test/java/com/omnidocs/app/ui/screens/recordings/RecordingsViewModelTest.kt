package com.omnidocs.app.ui.screens.recordings

import app.cash.turbine.test
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.RecordingDao
import com.omnidocs.app.data.local.TranscriptSegmentDao
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.data.local.entity.RecordingEntity
import com.omnidocs.app.data.local.entity.TranscriptSegmentEntity
import com.omnidocs.app.voice.AudioPlaybackController
import com.omnidocs.app.voice.RecordingStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import java.io.File
import java.nio.file.Files

class RecordingsViewModelTest {

    private lateinit var recordingDao: RecordingDao
    private lateinit var noteDao: NoteDao
    private lateinit var segmentDao: TranscriptSegmentDao
    private lateinit var storage: RecordingStorage
    private lateinit var playback: AudioPlaybackController
    private lateinit var viewModel: RecordingsViewModel

    private fun recording(
        id: String = "r1",
        noteId: String = "n1",
        durationMs: Long = 60_000L,
        createdAt: Long = 2000L,
        filename: String = "voice.pcm"
    ) = RecordingEntity(
        id = id,
        noteId = noteId,
        filename = filename,
        storageKey = "$id.pcm",
        durationMs = durationMs,
        language = "en",
        processingMode = "local",
        processingStatus = "completed",
        createdAt = createdAt
    )

    private fun note(id: String = "n1", title: String = "Meeting") = NoteEntity(
        id = id,
        title = title,
        content = "",
        plainText = "",
        isPinned = false,
        language = "en",
        createdAt = 1000L,
        updatedAt = 1000L,
        imageUrl = null
    )

    private fun segment(recordingId: String, text: String) = TranscriptSegmentEntity(
        id = "s-$recordingId-${text.hashCode()}",
        recordingId = recordingId,
        noteId = "n1",
        startMs = 0L,
        endMs = 1000L,
        rawText = text,
        language = "en",
        confidence = 0.9f,
        createdAt = 1000L,
        updatedAt = 1000L
    )

    @Before
    fun setUp() {
        recordingDao = mock(RecordingDao::class.java)
        noteDao = mock(NoteDao::class.java)
        segmentDao = mock(TranscriptSegmentDao::class.java)
        storage = mock(RecordingStorage::class.java)
        playback = mock(AudioPlaybackController::class.java)
    }

    /**
     * Mockito matcher that returns the real value instead of null, so Kotlin's
     * non-null parameter checks don't throw (plain `eq()` returns null).
     */
    private fun <T> matchEq(value: T): T {
        eq(value)
        return value
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.buildVm(
        recordings: List<RecordingEntity> = listOf(recording()),
        notes: List<NoteEntity> = listOf(note())
    ): RecordingsViewModel {
        // Pin Main to this test's scheduler so viewModelScope is virtual-time controlled
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        `when`(recordingDao.getAllRecordings()).thenReturn(flowOf(recordings))
        `when`(noteDao.getAllNotes()).thenReturn(flowOf(notes))
        return RecordingsViewModel(recordingDao, noteDao, segmentDao, storage, playback)
    }

    @Test
    fun items_joinsNoteTitleAndTranscript() = runTest {
        `when`(segmentDao.getSegmentsByRecordingIds(listOf("r1")))
            .thenReturn(listOf(segment("r1", "hello world")))
        viewModel = buildVm()

        viewModel.items.test {
            assertEquals(emptyList<RecordingListItem>(), awaitItem()) // initial
            val latest = awaitItem() // post-debounce computed list
            assertEquals(1, latest.size)
            assertEquals("Meeting", latest[0].noteTitle)
            assertEquals("hello world", latest[0].transcriptPreview)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun items_dropsRecordingsWithDeletedNotes() = runTest {
        viewModel = buildVm(
            recordings = listOf(recording(id = "r1", noteId = "gone")),
            notes = listOf(note(id = "n1"))
        )

        viewModel.items.test {
            awaitItem() // initial empty
            // Computed list is also empty (r1 dropped) so StateFlow dedupes:
            // assert r1 never appears instead of awaiting a second emission.
            advanceTimeBy(1000)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun items_searchFiltersByFilenameAndTitle() = runTest {
        viewModel = buildVm(
            recordings = listOf(
                recording(id = "r1", filename = "standup.pcm"),
                recording(id = "r2", filename = "ideas.pcm")
            )
        )
        `when`(segmentDao.getSegmentsByRecordingIds(listOf("r1")))
            .thenReturn(emptyList())
        viewModel.setSearchQuery("standup")

        viewModel.items.test {
            awaitItem() // initial
            val latest = awaitItem() // post-debounce filtered list
            assertEquals(listOf("r1"), latest.map { it.recording.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun items_sortLongestFirst() = runTest {
        viewModel = buildVm(
            recordings = listOf(
                recording(id = "short", durationMs = 10_000L),
                recording(id = "long", durationMs = 120_000L)
            )
        )
        `when`(segmentDao.getSegmentsByRecordingIds(listOf("long", "short")))
            .thenReturn(emptyList())
        viewModel.setSortOrder(RecordingSortOrder.LONGEST)

        viewModel.items.test {
            awaitItem() // initial
            val latest = awaitItem()
            assertEquals(listOf("long", "short"), latest.map { it.recording.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun delete_confirmThenUndoRestoresRowAndFile() = runTest {
        val rec = recording()
        viewModel = buildVm(recordings = listOf(rec))

        // Real temp audio file so trash rename/restore actually executes
        val dir = Files.createTempDirectory("rec-test").toFile()
        try {
            val audio = File(dir, "r1.pcm").apply { writeText("pcm-bytes") }
            `when`(storage.getRecordingFile("r1.pcm")).thenReturn(audio)
            `when`(storage.getRecordingFile("r1.pcm.deleted")).thenAnswer {
                File(dir, "r1.pcm.deleted").takeIf { it.exists() }
            }
            `when`(recordingDao.getRecordingById("r1")).thenReturn(rec.copy(deletedAt = 5000L))

            viewModel.requestDelete(rec)
            assertEquals(rec, viewModel.pendingDelete.value)
            viewModel.confirmDelete()
            runCurrent()
            verify(recordingDao).softDeleteRecording(matchEq("r1"), anyLong())
            assertTrue(File(dir, "r1.pcm.deleted").exists())

            viewModel.undoDelete()
            runCurrent()
            verify(recordingDao).updateRecording(rec)
            assertTrue(File(dir, "r1.pcm").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun togglePause_missingFileEmitsError() = runTest {
        val rec = recording()
        viewModel = buildVm()
        `when`(playback.isPlaying("r1.pcm")).thenReturn(false)
        `when`(playback.togglePause("r1.pcm")).thenReturn(false)

        viewModel.playbackError.test {
            viewModel.togglePause(rec)
            assertEquals("Audio file is missing for this recording.", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
