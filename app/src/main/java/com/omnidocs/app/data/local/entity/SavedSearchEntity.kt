package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved search query that the user can re-run.
 */
@Entity(tableName = "saved_searches")
data class SavedSearchEntity(
    @PrimaryKey val id: String,
    val name: String,
    val query: String,
    val filtersJson: String = "{}",
    val runOnOpen: Boolean = false,
    val createdAt: Long,
    val lastUsedAt: Long? = null
)
