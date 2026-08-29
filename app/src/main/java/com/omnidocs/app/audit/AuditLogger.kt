package com.omnidocs.app.audit

import android.util.Log
import com.omnidocs.app.data.local.AuditEventDao
import com.omnidocs.app.data.local.entity.AuditEventEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AuditLogger"

/**
 * Logs audit events for data access, modifications, AI operations,
 * exports, and deletions. Provides transparency and accountability.
 */
@Singleton
class AuditLogger @Inject constructor(
    private val auditEventDao: AuditEventDao
) {
    /**
     * Log an audit event.
     */
    suspend fun log(
        entityType: String,
        entityId: String,
        action: String,
        userId: String? = null,
        details: String = "{}"
    ) {
        val event = AuditEventEntity(
            id = UUID.randomUUID().toString(),
            entityType = entityType,
            entityId = entityId,
            action = action,
            userId = userId,
            details = details,
            createdAt = System.currentTimeMillis()
        )
        auditEventDao.insertEvent(event)
        Log.d(TAG, "Audit: $action on $entityType/$entityId")
    }

    // Convenience methods for common events

    suspend fun logNoteCreated(noteId: String) = log("note", noteId, "create")
    suspend fun logNoteUpdated(noteId: String) = log("note", noteId, "update")
    suspend fun logNoteDeleted(noteId: String) = log("note", noteId, "delete")
    suspend fun logNoteExported(noteId: String, format: String) = log("note", noteId, "export", details = "{\"format\":\"$format\"}")
    suspend fun logAiOperation(operation: String, model: String, tokens: Int) = log("ai", operation, "run", details = "{\"model\":\"$model\",\"tokens\":$tokens}")
    suspend fun logSearch(query: String) = log("search", query.hashCode().toString(), "query", details = "{\"query\":\"$query\"}")
    suspend fun logSettingsChanged(setting: String, value: String) = log("settings", setting, "update", details = "{\"value\":\"$value\"}")
    suspend fun logDataExport(format: String) = log("workspace", "all", "export", details = "{\"format\":\"$format\"}")
    suspend fun logDataDeletion(scope: String) = log("workspace", "all", "delete", details = "{\"scope\":\"$scope\"}")
}
