package com.omnidocs.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.omnidocs.app.MainActivity
import com.omnidocs.app.R

/**
 * 1-tap Home Screen Quick-Capture Widget providing instant shortcuts to:
 * - New Note (`omnidocs://quick_action/new_note`)
 * - Voice Memo (`omnidocs://quick_action/voice_capture`)
 * - Camera OCR Scan (`omnidocs://quick_action/ocr_scan`)
 * - Ask Your Notes AI (`omnidocs://quick_action/ask`)
 * - Spaced Repetition Study (`omnidocs://quick_action/study`)
 */
class OmniDocsQuickCaptureWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_capture)

            views.setOnClickPendingIntent(
                R.id.btn_widget_new_note,
                buildQuickActionPendingIntent(context, "new_note", 1001)
            )
            views.setOnClickPendingIntent(
                R.id.btn_widget_voice,
                buildQuickActionPendingIntent(context, "voice_capture", 1002)
            )
            views.setOnClickPendingIntent(
                R.id.btn_widget_ocr,
                buildQuickActionPendingIntent(context, "ocr_scan", 1003)
            )
            views.setOnClickPendingIntent(
                R.id.btn_widget_ask,
                buildQuickActionPendingIntent(context, "ask", 1004)
            )
            views.setOnClickPendingIntent(
                R.id.btn_widget_study,
                buildQuickActionPendingIntent(context, "study", 1005)
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun buildQuickActionPendingIntent(
            context: Context,
            actionPath: String,
            requestCode: Int
        ): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("omnidocs://quick_action/$actionPath")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
