package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "speakers")
data class SpeakerEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val profileReference: String? = null,
    val createdAt: Long
)