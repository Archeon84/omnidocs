package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_artifacts",
    indices = [
        Index(value = ["noteId"]),
        Index(value = ["artifactType"]),
        Index(value = ["approvalState"]),
        Index(value = ["createdAt"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AiArtifactEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val artifactType: String,
    val content: String,
    val sourceRevision: Int = 1,
    val promptVersion: String = "1.0",
    val modelId: String = "on_device_llm",
    val verificationStatus: String = STATUS_UNVERIFIED,
    val approvalState: String = APPROVAL_PENDING,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        const val TYPE_TITLE = "TITLE"
        const val TYPE_TAGS = "TAGS"
        const val TYPE_SUMMARY = "SUMMARY"
        const val TYPE_TASK = "TASK"
        const val TYPE_CLAIM = "CLAIM"
        const val TYPE_CONTRADICTION = "CONTRADICTION"

        const val STATUS_UNVERIFIED = "UNVERIFIED"
        const val STATUS_VERIFIED = "VERIFIED"
        const val STATUS_REJECTED = "REJECTED"

        const val APPROVAL_PENDING = "PENDING"
        const val APPROVAL_ACCEPTED = "ACCEPTED"
        const val APPROVAL_REJECTED = "REJECTED"
    }
}
