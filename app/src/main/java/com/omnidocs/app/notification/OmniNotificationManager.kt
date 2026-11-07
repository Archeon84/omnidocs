package com.omnidocs.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.omnidocs.app.MainActivity
import com.omnidocs.app.R

/**
 * Central orchestrator for the OmniDocs notification architecture.
 * Enforces Android 13/14/15 standards with proper channel importance,
 * granular user control, high-contrast vector assets, and actionable deep links.
 */
object OmniNotificationManager {

    // Notification Channel IDs
    const val CHANNEL_REMINDERS = "omnidocs_reminders"
    const val CHANNEL_VOICE_RECORDING = "omnidocs_voice_recording"
    const val CHANNEL_AGENT_WORKFLOW = "omnidocs_agent_workflow"
    const val CHANNEL_AGENT_COMPLETED = "omnidocs_agent_completed"
    const val CHANNEL_MODEL_DOWNLOADS = "omnidocs_model_downloads"

    // Base Notification IDs
    const val NOTIFICATION_ID_VOICE_RECORDING = 8001
    const val NOTIFICATION_ID_AGENT_WORKFLOW = 8002
    const val NOTIFICATION_ID_MODEL_DOWNLOAD = 9001
    const val NOTIFICATION_ID_AGENT_BASE = 8100
    const val NOTIFICATION_ID_REMINDER_BASE = 8200

    /**
     * Initializes all notification channels at application startup.
     * Safe to call repeatedly; channels are idempotent.
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // 1. Task & Study Reminders (High Priority, Sound, Vibration, Lights)
        val remindersChannel = NotificationChannel(
            CHANNEL_REMINDERS,
            "Task & Study Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Time-sensitive alerts for action item due dates and daily flashcard reviews."
            enableLights(true)
            enableVibration(true)
            setShowBadge(true)
        }

        // 2. Voice Recording (Ongoing Foreground Service, Low Priority, Silent)
        val recordingChannel = NotificationChannel(
            CHANNEL_VOICE_RECORDING,
            "Voice Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active voice note recording status and playback/stop controls."
            setShowBadge(false)
        }

        // 3. AI Agent Ingestion Progress (Low Priority, Silent Progress)
        val agentWorkflowChannel = NotificationChannel(
            CHANNEL_AGENT_WORKFLOW,
            "Background Processing",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress indicator for background document imports, transcription, and health scans."
            setShowBadge(false)
        }

        // 4. AI Agent Completed (Default Priority, Actionable)
        val agentCompletedChannel = NotificationChannel(
            CHANNEL_AGENT_COMPLETED,
            "Processing Complete",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts when document imports or meeting transcriptions finish and are ready to view."
            setShowBadge(true)
        }

        // 5. Model Downloads (Low Priority, Progress)
        val downloadsChannel = NotificationChannel(
            CHANNEL_MODEL_DOWNLOADS,
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Download progress for on-device AI models."
            setShowBadge(false)
        }

        manager.createNotificationChannels(
            listOf(
                remindersChannel,
                recordingChannel,
                agentWorkflowChannel,
                agentCompletedChannel,
                downloadsChannel
            )
        )
    }

    /**
     * Post an actionable completion notification when a background agent pipeline finishes.
     */
    fun postAgentCompletedNotification(
        context: Context,
        notificationId: Int,
        title: String,
        content: String,
        noteId: String? = null
    ) {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            if (!noteId.isNullOrBlank()) {
                data = Uri.parse("omnidocs://note/$noteId")
            } else {
                data = Uri.parse("omnidocs://quick_action/home")
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_AGENT_COMPLETED)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(R.drawable.ic_widget_ai)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.notify(notificationId, notification)
    }

    /**
     * Post a task reminder notification with interactive "Mark as Done" action directly in the shade.
     */
    fun postTaskReminderNotification(
        context: Context,
        taskId: String,
        taskTitle: String,
        noteId: String?,
        noteTitle: String?
    ) {
        val notificationId = NOTIFICATION_ID_REMINDER_BASE + (taskId.hashCode() and 0x7FFF)

        // Tap content intent -> Open note
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            if (!noteId.isNullOrBlank()) {
                data = Uri.parse("omnidocs://note/$noteId")
            } else {
                data = Uri.parse("omnidocs://quick_action/tasks")
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Mark as Done without opening app
        val doneIntent = Intent(context, TaskActionReceiver::class.java).apply {
            action = TaskActionReceiver.ACTION_MARK_DONE
            putExtra(TaskActionReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val donePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bodyText = if (!noteTitle.isNullOrBlank()) "From note: $noteTitle" else "OmniDocs Action Item"

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setContentTitle("Task Due: $taskTitle")
            .setContentText(bodyText)
            .setSmallIcon(R.drawable.ic_notification_check)
            .setAutoCancel(true)
            .setContentIntent(tapPendingIntent)
            .addAction(
                R.drawable.ic_notification_check,
                "Mark as Done",
                donePendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.notify(notificationId, notification)
    }

    /**
     * Cancel an active notification by ID.
     */
    fun cancelNotification(context: Context, notificationId: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.cancel(notificationId)
    }
}
