package com.omnidocs.app.voice

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.omnidocs.app.MainActivity
import com.omnidocs.app.R
import com.omnidocs.app.notification.OmniNotificationManager

/**
 * Foreground Service guaranteeing uninterrupted microphone audio capture
 * across Android 10, 11, 12, 13, 14, and 15 lifecycle transitions.
 * Displays an ongoing notification with live duration chronometer and interactive
 * controls to Stop & Save or Discard the recording from any screen.
 */
class VoiceRecordingService : Service() {

    private var startTimeMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        OmniNotificationManager.createNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_SERVICE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startTimeMs = intent?.getLongExtra(EXTRA_START_TIME, System.currentTimeMillis())
            ?: System.currentTimeMillis()

        val notification = buildRecordingNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            startForeground(OmniNotificationManager.NOTIFICATION_ID_VOICE_RECORDING, notification, type)
        } else {
            startForeground(OmniNotificationManager.NOTIFICATION_ID_VOICE_RECORDING, notification)
        }

        return START_NOT_STICKY
    }

    private fun buildRecordingNotification(): Notification {
        // Tap notification -> returns directly to the voice recording overlay
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("omnidocs://quick_action/voice_capture")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this,
            201,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Stop & Save
        val stopIntent = Intent(ACTION_VOICE_STOP).setPackage(packageName)
        val stopPendingIntent = PendingIntent.getBroadcast(
            this,
            202,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Discard
        val discardIntent = Intent(ACTION_VOICE_DISCARD).setPackage(packageName)
        val discardPendingIntent = PendingIntent.getBroadcast(
            this,
            203,
            discardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, OmniNotificationManager.CHANNEL_VOICE_RECORDING)
            .setContentTitle("Recording Voice Note")
            .setContentText("Tap to view live waveform • Speech-to-Text active")
            .setSmallIcon(R.drawable.ic_widget_mic)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(startTimeMs)
            .setContentIntent(tapPendingIntent)
            .addAction(R.drawable.ic_notification_stop, "Stop & Save", stopPendingIntent)
            .addAction(R.drawable.ic_notification_cancel, "Discard", discardPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_SERVICE = "com.omnidocs.app.voice.START"
        const val ACTION_STOP_SERVICE = "com.omnidocs.app.voice.STOP"
        const val ACTION_VOICE_STOP = "com.omnidocs.app.voice.ACTION_STOP"
        const val ACTION_VOICE_DISCARD = "com.omnidocs.app.voice.ACTION_DISCARD"
        const val EXTRA_START_TIME = "extra_start_time"

        fun start(context: Context, startTime: Long = System.currentTimeMillis()) {
            val intent = Intent(context, VoiceRecordingService::class.java).apply {
                action = ACTION_START_SERVICE
                putExtra(EXTRA_START_TIME, startTime)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceRecordingService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
}
