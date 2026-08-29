package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_events",
    indices = [
        Index(value = ["jobId"]),
        Index(value = ["createdAt"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = AgentJobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AgentEventEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val agentId: String,
    val eventType: String,
    val safeMetadata: String = "{}",
    val durationMs: Long? = null,
    val createdAt: Long
)
