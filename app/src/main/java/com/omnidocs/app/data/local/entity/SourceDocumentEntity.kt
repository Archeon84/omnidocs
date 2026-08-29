package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "source_documents",
    indices = [
        Index(value = ["checksum"]),
        Index(value = ["mimeType"]),
        Index(value = ["importedAt"])
    ]
)
data class SourceDocumentEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val mimeType: String,
    val fileName: String,
    val checksum: String,
    val fileSizeBytes: Long = 0L,
    val pageCount: Int? = null,
    val language: String? = null,
    val isEncrypted: Boolean = false,
    val importedAt: Long
)
