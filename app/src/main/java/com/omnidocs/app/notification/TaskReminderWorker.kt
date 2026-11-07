package com.omnidocs.app.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.omnidocs.app.data.local.ActionItemDao
import com.omnidocs.app.data.local.FlashcardDao
import com.omnidocs.app.data.local.NoteDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderWorkerEntryPoint {
    fun actionItemDao(): ActionItemDao
    fun flashcardDao(): FlashcardDao
    fun noteDao(): NoteDao
}

/**
 * Background worker executing periodic audits for due action items and spaced
 * repetition review intervals, posting actionable notifications when items need attention.
 */
class TaskReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            ReminderWorkerEntryPoint::class.java
        )
        val actionItemDao = entryPoint.actionItemDao()
        val flashcardDao = entryPoint.flashcardDao()
        val noteDao = entryPoint.noteDao()

        val now = System.currentTimeMillis()

        try {
            // 1. Check for overdue/due active action items
            val activeTasks = actionItemDao.getActiveActionItems().first()
            val dueTasks = activeTasks.filter { task ->
                task.dueAt != null && task.dueAt <= now
            }

            // Post notification for the most urgent due task
            dueTasks.firstOrNull()?.let { task ->
                val note = noteDao.getNoteById(task.noteId)
                OmniNotificationManager.postTaskReminderNotification(
                    context = applicationContext,
                    taskId = task.id,
                    taskTitle = task.title,
                    noteId = task.noteId,
                    noteTitle = note?.title
                )
            }

            // 2. Check for due flashcards (spaced repetition review)
            val dueCardsCount = flashcardDao.countDueFlashcards(now)
            if (dueCardsCount > 0) {
                OmniNotificationManager.postAgentCompletedNotification(
                    context = applicationContext,
                    notificationId = OmniNotificationManager.NOTIFICATION_ID_REMINDER_BASE + 99,
                    title = "Flashcard Review Due",
                    content = "$dueCardsCount cards are ready for spaced repetition study.",
                    noteId = null
                )
            }

            return Result.success()
        } catch (_: Exception) {
            return Result.retry()
        }
    }
}
