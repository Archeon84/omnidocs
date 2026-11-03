package com.omnidocs.app.ui.screens.localchat

import android.content.Context
import com.omnidocs.app.docimport.DocumentConverterFactory
import com.omnidocs.app.ocr.OcrEngineFactory
import com.omnidocs.app.stt.SttEngineFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class ChatAttachmentProcessorTest {

    private lateinit var context: Context
    private lateinit var docConverterFactory: DocumentConverterFactory
    private lateinit var ocrEngineFactory: OcrEngineFactory
    private lateinit var sttEngineFactory: SttEngineFactory
    private lateinit var processor: ChatAttachmentProcessor

    @Before
    fun setup() {
        context = mock(Context::class.java)
        docConverterFactory = mock(DocumentConverterFactory::class.java)
        ocrEngineFactory = mock(OcrEngineFactory::class.java)
        sttEngineFactory = mock(SttEngineFactory::class.java)
        processor = ChatAttachmentProcessor(
            context = context,
            documentConverterFactory = docConverterFactory,
            ocrEngineFactory = ocrEngineFactory,
            sttEngineFactory = sttEngineFactory
        )
    }

    @Test
    fun `determineAttachmentType identifies image files by MIME and extension`() {
        assertEquals("image", processor.determineAttachmentType("image/jpeg", "photo.jpg"))
        assertEquals("image", processor.determineAttachmentType("image/png", "screenshot.png"))
        assertEquals("image", processor.determineAttachmentType("application/octet-stream", "diagram.webp"))
        assertEquals("image", processor.determineAttachmentType("image/heic", "camera.heic"))
    }

    @Test
    fun `determineAttachmentType identifies audio files by MIME and extension`() {
        assertEquals("audio", processor.determineAttachmentType("audio/wav", "voice.wav"))
        assertEquals("audio", processor.determineAttachmentType("audio/mpeg", "interview.mp3"))
        assertEquals("audio", processor.determineAttachmentType("audio/mp4", "meeting.m4a"))
        assertEquals("audio", processor.determineAttachmentType("application/octet-stream", "sound.ogg"))
        assertEquals("audio", processor.determineAttachmentType("audio/flac", "lecture.flac"))
    }

    @Test
    fun `determineAttachmentType identifies document files by default and extension`() {
        assertEquals("document", processor.determineAttachmentType("application/pdf", "contract.pdf"))
        assertEquals("document", processor.determineAttachmentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "spec.docx"))
        assertEquals("document", processor.determineAttachmentType("text/plain", "notes.txt"))
        assertEquals("document", processor.determineAttachmentType("text/markdown", "README.md"))
        assertEquals("document", processor.determineAttachmentType("text/csv", "data.csv"))
    }
}
