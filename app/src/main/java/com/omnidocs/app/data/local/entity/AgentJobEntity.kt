package com.omnidocs.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_jobs",
    indices = [
        Index(value = ["status"]),
        Index(value = ["jobType"]),
        Index(value = ["createdAt"])
    ]
)
data class AgentJobEntity(
    @PrimaryKey val id: String,
    val jobType: String,
    val inputRefsJson: String = "{}",
    val status: String,
    val progress: Int = 0,
    val isLocalExecution: Boolean = true,
    val modelId: String? = null,
    val createdAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val retryCount: Int = 0,
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_WAITING_FOR_MODEL = "WAITING_FOR_MODEL"
        const val STATUS_WAITING_FOR_USER = "WAITING_FOR_USER"
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_FAILED_RETRYABLE = "FAILED_RETRYABLE"
        const val STATUS_FAILED_PERMANENT = "FAILED_PERMANENT"
        const val STATUS_PARTIAL_SUCCESS = "PARTIAL_SUCCESS"
    }
}
