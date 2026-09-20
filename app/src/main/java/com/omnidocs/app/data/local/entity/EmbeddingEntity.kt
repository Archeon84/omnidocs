package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "embeddings",
    indices = [
        Index(value = ["sourceType", "sourceId"]),
        Index(value = ["sourceType", "modelName"]),
        Index(value = ["sourceId"]),
        Index(value = ["modelName"]),
        Index(value = ["chunkHash"])
    ]
)
data class EmbeddingEntity(
    @PrimaryKey val id: String,
    val sourceType: String, // note, transcript_segment, claim
    val sourceId: String,
    val chunkHash: String,
    val modelName: String,
    val embeddingVector: ByteArray,
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingEntity) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}