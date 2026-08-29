package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audit_events",
    indices = [
        Index(value = ["entityType", "entityId"]),
        Index(value = ["action"]),
        Index(value = ["createdAt"])
    ]
)
data class AuditEventEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val action: String, // create, update, delete, export, ai_run, etc.
    val userId: String? = null,
    val details: String = "{}",
    val createdAt: Long
)