package com.omnidocs.app.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AgentWorkerEntryPoint {
    fun importPipelineCoordinator(): ImportPipelineCoordinator
    fun meetingPipelineCoordinator(): MeetingPipelineCoordinator
    fun healthCoordinator(): HealthCoordinator
}

/**
 * Durable background worker executing agent workflows via Android WorkManager.
 * Ensures long-running imports, speech transcription, and indexing survive
 * app backgrounding, configuration changes, and process restarts.
 */
class AgentJobWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        com.omnidocs.app.notification.OmniNotificationManager.createNotificationChannels(applicationContext)
        val notification = androidx.core.app.NotificationCompat.Builder(
            applicationContext,
            com.omnidocs.app.notification.OmniNotificationManager.CHANNEL_AGENT_WORKFLOW
        )
            .setContentTitle("OmniDocs Intelligence")
            .setContentText("Processing document knowledge in background...")
            .setSmallIcon(com.omnidocs.app.R.drawable.ic_widget_ai)
            .setOngoing(true)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return androidx.work.ForegroundInfo(com.omnidocs.app.notification.OmniNotificationManager.NOTIFICATION_ID_AGENT_WORKFLOW, notification)
    }

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            AgentWorkerEntryPoint::class.java
        )

        val jobType = inputData.getString(KEY_JOB_TYPE) ?: return Result.failure()

        return try {
            when (jobType) {
                "WORKFLOW_IMPORT_TO_KNOWLEDGE" -> {
                    val uri = inputData.getString(KEY_URI) ?: return Result.failure()
                    val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "application/octet-stream"
                    val fileName = inputData.getString(KEY_FILE_NAME) ?: "document"

                    val coordinator = entryPoint.importPipelineCoordinator()
                    val result = coordinator.runImportPipeline(uri, mimeType, fileName)
                    if (result.isSuccess) {
                        com.omnidocs.app.notification.OmniNotificationManager.postAgentCompletedNotification(
                            context = applicationContext,
                            notificationId = com.omnidocs.app.notification.OmniNotificationManager.NOTIFICATION_ID_AGENT_BASE + (result.jobId.hashCode() and 0x7FFF),
                            title = "Document Imported",
                            content = "Processed '$fileName' into '${result.noteTitle ?: "new note"}' (${result.taskCount} tasks extracted).",
                            noteId = result.noteId
                        )
                        com.omnidocs.app.widget.OmniDocsWidgetManager.notifyDataChanged(applicationContext)
                        Result.success()
                    } else {
                        Result.failure()
                    }
                }
                "WORKFLOW_MEETING_RECORDING" -> {
                    val storageKey = inputData.getString(KEY_STORAGE_KEY) ?: return Result.failure()
                    val title = inputData.getString(KEY_TITLE) ?: "Meeting Recording"

                    val coordinator = entryPoint.meetingPipelineCoordinator()
                    val result = coordinator.runMeetingPipeline(storageKey = storageKey, meetingTitle = title)
                    if (result.isSuccess) {
                        com.omnidocs.app.notification.OmniNotificationManager.postAgentCompletedNotification(
                            context = applicationContext,
                            notificationId = com.omnidocs.app.notification.OmniNotificationManager.NOTIFICATION_ID_AGENT_BASE + (result.jobId.hashCode() and 0x7FFF),
                            title = "Meeting Notes Ready",
                            content = "Generated notes for '${result.noteTitle ?: title}' (${result.taskCount} tasks extracted).",
                            noteId = result.noteId
                        )
                        com.omnidocs.app.widget.OmniDocsWidgetManager.notifyDataChanged(applicationContext)
                        Result.success()
                    } else {
                        Result.failure()
                    }
                }
                "WORKFLOW_WORKSPACE_HEALTH_SCAN" -> {
                    val coordinator = entryPoint.healthCoordinator()
                    coordinator.runHealthScan()
                    Result.success()
                }
                else -> Result.failure()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_JOB_TYPE = "key_job_type"
        const val KEY_URI = "key_uri"
        const val KEY_MIME_TYPE = "key_mime_type"
        const val KEY_FILE_NAME = "key_file_name"
        const val KEY_STORAGE_KEY = "key_storage_key"
        const val KEY_TITLE = "key_title"
    }
}
