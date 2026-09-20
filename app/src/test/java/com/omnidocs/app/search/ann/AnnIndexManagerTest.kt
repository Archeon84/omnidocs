package com.omnidocs.app.search.ann

import com.omnidocs.app.data.local.entity.EmbeddingEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnIndexManagerTest {

    @Test
    fun computeKey_isDeterministic() {
        val id = "note:test1234:0:multilingual-e5-small_384"
        val key1 = AnnIndexManager.computeKey(id)
        val key2 = AnnIndexManager.computeKey(id)
        assertEquals(key1, key2)
        assertTrue(key1 != 0L)
    }

    @Test
    fun computeKey_differentIdsProduceDifferentKeys() {
        val key1 = AnnIndexManager.computeKey("note:a:0:model")
        val key2 = AnnIndexManager.computeKey("note:b:0:model")
        assertTrue(key1 != key2)
    }

    @Test
    fun kotlinMemoryIndex_addAndSearch() {
        val index = KotlinMemoryIndex(dimensions = 4)
        val v1 = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f) // orthogonal
        val v2 = floatArrayOf(0.9f, 0.1f, 0.0f, 0.0f) // close to v1
        val v3 = floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f) // perpendicular

        index.add(1L, v1)
        index.add(2L, v2)
        index.add(3L, v3)

        assertEquals(3, index.size())
        assertTrue(index.contains(1L))
        assertTrue(index.contains(2L))

        val query = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)
        val results = index.search(query, wanted = 2, cosineFloor = 0.5f)

        assertEquals(2, results.size)
        assertEquals(1L, results[0].key)
        assertTrue(results[0].similarity > 0.99f)
        assertEquals(2L, results[1].key)
        assertTrue(results[1].similarity > 0.85f)
    }

    @Test
    fun annIndexManager_indexesAndSearchesWithMetadata() {
        val manager = AnnIndexManager()

        val entity1 = EmbeddingEntity(
            id = "note:note1:0:test-model",
            sourceType = "note",
            sourceId = "note1",
            chunkHash = "hash1",
            modelName = "test-model",
            embeddingVector = ByteArray(4),
            createdAt = 1000L,
            chunkText = "First chunk text about AI",
            sectionHeader = "Introduction",
            startOffset = 0,
            endOffset = 25,
            chunkIndex = 0
        )

        val entity2 = EmbeddingEntity(
            id = "note:note2:0:test-model",
            sourceType = "note",
            sourceId = "note2",
            chunkHash = "hash2",
            modelName = "test-model",
            embeddingVector = ByteArray(4),
            createdAt = 1000L,
            chunkText = "Second chunk text about cooking",
            sectionHeader = "Recipes",
            startOffset = 0,
            endOffset = 30,
            chunkIndex = 0
        )

        val vec1 = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)
        val vec2 = floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f)

        manager.add(entity1, vec1)
        manager.add(entity2, vec2)

        val query = floatArrayOf(0.95f, 0.05f, 0.0f, 0.0f)
        val results = manager.search(query, wanted = 5, cosineFloor = 0.5f, modelName = "test-model")

        assertEquals(1, results.size)
        val hit = results[0]
        assertEquals("note1", hit.noteId)
        assertEquals(0, hit.chunkIndex)
        assertEquals(entity1.id, hit.embeddingId)
        assertTrue(hit.similarity > 0.9f)
    }

    @Test
    fun annIndexManager_syncCorpus_indexesAllEmbeddings() {
        val manager = AnnIndexManager()

        val entities = (0 until 5).map { i ->
            EmbeddingEntity(
                id = "note:doc$i:0:model",
                sourceType = "note",
                sourceId = "doc$i",
                chunkHash = "hash$i",
                modelName = "model",
                embeddingVector = ByteArray(4),
                createdAt = 1000L,
                chunkText = "Text $i",
                sectionHeader = "Header $i",
                startOffset = 0,
                endOffset = 10,
                chunkIndex = 0
            )
        }

        manager.syncCorpus(entities) { entity ->
            val idx = entity.sourceId.removePrefix("doc").toInt()
            FloatArray(4) { if (it == idx % 4) 1.0f else 0.0f }
        }

        assertEquals(5, manager.size())

        val query = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)
        val matches = manager.search(query, wanted = 10, cosineFloor = 0.5f, modelName = "model")

        assertTrue(matches.isNotEmpty())
        assertTrue(matches.any { it.noteId == "doc0" || it.noteId == "doc4" })
    }
}
