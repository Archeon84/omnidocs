package com.omnidocs.app.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.omnidocs.app.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val CHANNEL_ID = "model_download_channel"
private const val NOTIFICATION_ID = 9001
private const val EXTRA_MODEL_NAME = "extra_model_name"

@AndroidEntryPoint
class ModelDownloadService : Service() {

    @Inject
    lateinit var modelDownloadManager: ModelDownloadManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentModelName: String = "AI Model"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_MODEL_NAME)?.let {
            currentModelName = it
        }

        val initialNotification = buildNotification(
            title = "Downloading $currentModelName",
            text = "Preparing download...",
            progress = 0,
            indeterminate = true
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFICATION_ID, initialNotification, type)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        observeDownloadStates()

        return START_NOT_STICKY
    }

    private fun observeDownloadStates() {
        scope.launch {
            combine(
                modelDownloadManager.downloadState,
                modelDownloadManager.embeddingDownloadState,
                modelDownloadManager.sttDownloadState
            ) { llmState, embState, sttState ->
                Triple(llmState, embState, sttState)
            }.collect { (llmState, embState, sttState) ->
                val activeState = when {
                    llmState is DownloadState.Downloading -> llmState
                    embState is DownloadState.Downloading -> embState
                    sttState is DownloadState.Downloading -> sttState
                    else -> null
                }

                if (activeState != null) {
                    val progressInt = activeState.progress.toInt().coerceIn(0, 100)
                    updateNotification(
                        title = "Downloading $currentModelName",
                        text = "$progressInt% completed",
                        progress = progressInt,
                        indeterminate = false
                    )
                } else {
                    val anyActive = llmState is DownloadState.Downloading ||
                            embState is DownloadState.Downloading ||
                            sttState is DownloadState.Downloading
                    if (!anyActive) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean
    ): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun updateNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean
    ) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(title, text, progress, indeterminate))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of on-device AI model downloads"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context, modelName: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                putExtra(EXTRA_MODEL_NAME, modelName)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                android.util.Log.e("ModelDownloadService", "Failed to start foreground service", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java)
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                android.util.Log.e("ModelDownloadService", "Failed to stop service", e)
            }
        }
    }
}
