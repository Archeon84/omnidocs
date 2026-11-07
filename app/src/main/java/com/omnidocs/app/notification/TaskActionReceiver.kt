package com.omnidocs.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.omnidocs.app.data.local.ActionItemDao
import com.omnidocs.app.widget.OmniDocsWidgetManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TaskActionEntryPoint {
    fun actionItemDao(): ActionItemDao
}

/**
 * Handles interactive actions from notifications, such as marking an action item
 * complete directly from the notification shade without launching the main activity.
 */
class TaskActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == ACTION_MARK_DONE) {
            val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
            val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

            if (notificationId != -1) {
                OmniNotificationManager.cancelNotification(context, notificationId)
            }

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val entryPoint = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        TaskActionEntryPoint::class.java
                    )
                    val actionItemDao = entryPoint.actionItemDao()
                    val now = System.currentTimeMillis()
                    actionItemDao.completeActionItem(
                        id = taskId,
                        status = "completed",
                        completedAt = now,
                        updatedAt = now
                    )
                    OmniDocsWidgetManager.notifyDataChanged(context.applicationContext)
                } catch (e: Exception) {
                    Log.e("TaskActionReceiver", "Failed to complete task $taskId from notification", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_MARK_DONE = "com.omnidocs.app.ACTION_TASK_MARK_DONE"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}
