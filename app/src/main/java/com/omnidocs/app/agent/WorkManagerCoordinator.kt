package com.omnidocs.app.agent

import android.content.Context
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level scheduler that enqueues durable background agent jobs to Android WorkManager.
 */
@Singleton
class WorkManagerCoordinator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager = WorkManager.getInstance(context)

    /**
     * Enqueues an asynchronous document import and knowledge ingestion background worker.
     */
    fun enqueueImport(uri: String, mimeType: String, fileName: String): UUID {
        val inputData = Data.Builder()
            .putString(AgentJobWorker.KEY_JOB_TYPE, "WORKFLOW_IMPORT_TO_KNOWLEDGE")
            .putString(AgentJobWorker.KEY_URI, uri)
            .putString(AgentJobWorker.KEY_MIME_TYPE, mimeType)
            .putString(AgentJobWorker.KEY_FILE_NAME, fileName)
            .build()

        val request = OneTimeWorkRequestBuilder<AgentJobWorker>()
            .setInputData(inputData)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        workManager.enqueueUniqueWork(
            "import_${UUID.randomUUID()}",
            ExistingWorkPolicy.REPLACE,
            request
        )
        return request.id
    }

    /**
     * Enqueues an asynchronous meeting speech recognition and summarization background worker.
     */
    fun enqueueMeetingTranscription(storageKey: String, meetingTitle: String): UUID {
        val inputData = Data.Builder()
            .putString(AgentJobWorker.KEY_JOB_TYPE, "WORKFLOW_MEETING_RECORDING")
            .putString(AgentJobWorker.KEY_STORAGE_KEY, storageKey)
            .putString(AgentJobWorker.KEY_TITLE, meetingTitle)
            .build()

        val request = OneTimeWorkRequestBuilder<AgentJobWorker>()
            .setInputData(inputData)
            .build()

        workManager.enqueueUniqueWork(
            "meeting_${UUID.randomUUID()}",
            ExistingWorkPolicy.REPLACE,
            request
        )
        return request.id
    }

    /**
     * Enqueues an asynchronous workspace health scan worker.
     */
    fun enqueueWorkspaceHealthScan(): UUID {
        val inputData = Data.Builder()
            .putString(AgentJobWorker.KEY_JOB_TYPE, "WORKFLOW_WORKSPACE_HEALTH_SCAN")
            .build()

        val request = OneTimeWorkRequestBuilder<AgentJobWorker>()
            .setInputData(inputData)
            .build()

        workManager.enqueueUniqueWork(
            "health_scan",
            ExistingWorkPolicy.KEEP,
            request
        )
        return request.id
    }

    /**
     * Cancels an enqueued background work request.
     */
    fun cancelWork(workId: UUID) {
        workManager.cancelWorkById(workId)
    }

    /**
     * Observes live WorkInfo for a given workId.
     */
    fun getWorkInfoFlow(workId: UUID): Flow<WorkInfo?> {
        return workManager.getWorkInfoByIdFlow(workId)
    }
}
