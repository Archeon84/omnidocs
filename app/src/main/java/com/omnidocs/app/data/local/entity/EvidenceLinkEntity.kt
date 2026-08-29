package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "evidence_links",
    indices = [
        Index(value = ["claimId"]),
        Index(value = ["sourceType", "sourceId"])
    ]
)
data class EvidenceLinkEntity(
    @PrimaryKey val id: String,
    val claimId: String,
    val sourceType: String, // note, transcript_segment, attachment
    val sourceId: String,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val quoteHash: String? = null,
    val relevanceScore: Float,
    val createdAt: Long
)