package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "entities",
    indices = [
        Index(value = ["entityType"]),
        Index(value = ["canonicalName"])
    ]
)
data class EntityEntity(
    @PrimaryKey val id: String,
    val entityType: String, // person, organization, project, place, concept, product, event
    val canonicalName: String,
    val aliasesJson: String = "[]",
    val metadataJson: String = "{}",
    val createdAt: Long,
    val updatedAt: Long
)