package com.omnidocs.app.data.remote

import com.omnidocs.app.data.local.entity.NoteEntity
import org.junit.Assert.*
import org.junit.Test

class NoteCloudCodecTest {

    private fun note(
        id: String = "note_1",
        title: String = "Title",
        content: String = "Content",
        isDeleted: Boolean = false,
        deletedAt: Long? = null,
        updatedAt: Long = 2000L,
        tags: String = "[\"tag1\"]",
        relatedNotes: String = "[]"
    ): NoteEntity {
        return NoteEntity(
            id = id,
            title = title,
            content = content,
            plainText = content,
            isPinned = false,
            language = "en",
            createdAt = 1000L,
            updatedAt = updatedAt,
            imageUrl = null,
            isDeleted = isDeleted,
            deletedAt = deletedAt,
            tags = tags,
            relatedNotes = relatedNotes
        )
    }

    @Test
    fun encodeDecode_roundTrip_preservesAllFields() {
        val notes = listOf(
            note(id = "n1", title = "T1", content = "C1", tags = "[\"a\"]", relatedNotes = "[]"),
            note(id = "n2", title = "T2", content = "C2", isDeleted = true, deletedAt = 5000L, tags = "[]", relatedNotes = "[\"n1\"]")
        )
        val json = NoteCloudCodec.encode(notes)
        val decoded = NoteCloudCodec.decode(json)
        assertEquals(2, decoded.size)
        assertEquals("n1", decoded[0].id)
        assertEquals("T1", decoded[0].title)
        assertEquals("C1", decoded[0].content)
        assertEquals("[\"a\"]", decoded[0].tags)
        assertEquals("[]", decoded[0].relatedNotes)
        assertEquals(false, decoded[0].isDeleted)
        assertNull(decoded[0].deletedAt)

        assertEquals("n2", decoded[1].id)
        assertEquals(true, decoded[1].isDeleted)
        assertEquals(5000L, decoded[1].deletedAt)
        assertEquals("[]", decoded[1].tags)
        assertEquals("[\"n1\"]", decoded[1].relatedNotes)
    }

    @Test
    fun decode_emptyArray_returnsEmptyList() {
        val decoded = NoteCloudCodec.decode("[]")
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun decode_skipsMalformedRowKeepsGoodRows() {
        val json = """
            [{
                "id":"good",
                "title":"T",
                "content":"C",
                "plainText":"C",
                "isPinned":false,
                "language":"en",
                "createdAt":1,
                "updatedAt":2,
                "isDeleted":false
            },
            {"id":"", "title":12345},
            {"title":"no id at all"}]
        """.trimIndent()
        val decoded = NoteCloudCodec.decode(json)
        assertEquals(1, decoded.size)
        assertEquals("good", decoded[0].id)
    }

    @Test
    fun decode_missingOptionalFields_usesDefaults() {
        val json = """
            [{
                "id":"x",
                "title":"T",
                "content":"C",
                "plainText":"C",
                "isPinned":false,
                "language":"en",
                "createdAt":1,
                "updatedAt":2,
                "isDeleted":false
            }]
        """.trimIndent()
        val decoded = NoteCloudCodec.decode(json)
        assertEquals(1, decoded.size)
        assertEquals("[]", decoded[0].tags)
        assertEquals("[]", decoded[0].relatedNotes)
        assertNull(decoded[0].imageUrl)
        assertEquals("[]", decoded[0].attachments)
    }
}
