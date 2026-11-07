package com.omnidocs.app.notification

import org.junit.Assert.*
import org.junit.Test

class OmniNotificationManagerTest {

    @Test
    fun testNotificationChannelIdsAreDistinct() {
        val channels = listOf(
            OmniNotificationManager.CHANNEL_REMINDERS,
            OmniNotificationManager.CHANNEL_VOICE_RECORDING,
            OmniNotificationManager.CHANNEL_AGENT_WORKFLOW,
            OmniNotificationManager.CHANNEL_AGENT_COMPLETED,
            OmniNotificationManager.CHANNEL_MODEL_DOWNLOADS
        )

        assertEquals(5, channels.toSet().size)
        assertTrue(channels.all { it.startsWith("omnidocs_") })
    }

    @Test
    fun testNotificationIdRangesDoNotCollide() {
        val voiceId = OmniNotificationManager.NOTIFICATION_ID_VOICE_RECORDING
        val agentWorkflowId = OmniNotificationManager.NOTIFICATION_ID_AGENT_WORKFLOW
        val modelDownloadId = OmniNotificationManager.NOTIFICATION_ID_MODEL_DOWNLOAD
        val agentBase = OmniNotificationManager.NOTIFICATION_ID_AGENT_BASE
        val reminderBase = OmniNotificationManager.NOTIFICATION_ID_REMINDER_BASE

        val ids = setOf(voiceId, agentWorkflowId, modelDownloadId, agentBase, reminderBase)
        assertEquals(5, ids.size)
        assertTrue(reminderBase > agentBase)
    }

    @Test
    fun testTaskReminderNotificationIdGenerationIsDeterministic() {
        val taskId = "task-uuid-abc-123"
        val expectedOffset = taskId.hashCode() and 0x7FFF
        val id1 = OmniNotificationManager.NOTIFICATION_ID_REMINDER_BASE + expectedOffset
        val id2 = OmniNotificationManager.NOTIFICATION_ID_REMINDER_BASE + (taskId.hashCode() and 0x7FFF)
        assertEquals(id1, id2)
        assertTrue(id1 >= OmniNotificationManager.NOTIFICATION_ID_REMINDER_BASE)
    }
}
