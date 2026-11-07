package com.omnidocs.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import com.omnidocs.app.MainActivity
import com.omnidocs.app.R

/**
 * Industry Gold-Standard Home Screen Widget for OmniDocs:
 * - Multi-Size Responsive Architecture: Automatically switches between a sleek 1-row Quick Action bar
 *   and an expanded Knowledge Hub displaying recent and pinned notes.
 * - 1-Tap Direct Deep Links for instant capture:
 *     - New Note (`omnidocs://quick_action/new_note`)
 *     - Voice Memo (`omnidocs://quick_action/voice_capture`)
 *     - Camera OCR Scan (`omnidocs://quick_action/ocr_scan`)
 *     - Ask Your Notes AI (`omnidocs://quick_action/ask`)
 *     - Spaced Repetition Study (`omnidocs://quick_action/study`)
 *     - Task Management (`omnidocs://quick_action/tasks`)
 *     - Direct Note Opening (`omnidocs://note/{noteId}`)
 * - Dynamic collection updates powered by [OmniDocsWidgetService].
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

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    companion object {
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val remoteViews = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ Responsive Layouts
                val compactViews = buildCompactRemoteViews(context)
                val expandedViews = buildExpandedRemoteViews(context, appWidgetId)
                RemoteViews(
                    mapOf(
                        SizeF(140f, 50f) to compactViews,
                        SizeF(220f, 110f) to expandedViews
                    )
                )
            } else {
                // Android 8.0 - 11: Switch based on current widget options
                val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
                val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
                if (minHeight >= 100) {
                    buildExpandedRemoteViews(context, appWidgetId)
                } else {
                    buildCompactRemoteViews(context)
                }
            }

            appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
        }

        private fun buildCompactRemoteViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_capture_compact)

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

            return views
        }

        private fun buildExpandedRemoteViews(context: Context, appWidgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_capture_expanded)

            // Top Action buttons
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

            // Bottom action buttons
            views.setOnClickPendingIntent(
                R.id.btn_widget_tasks,
                buildQuickActionPendingIntent(context, "tasks", 1006)
            )
            views.setOnClickPendingIntent(
                R.id.btn_widget_study,
                buildQuickActionPendingIntent(context, "study", 1005)
            )

            // Bind ListView to OmniDocsWidgetService
            val serviceIntent = Intent(context, OmniDocsWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_notes_list, serviceIntent)
            views.setEmptyView(R.id.widget_notes_list, R.id.widget_empty_view)

            // Empty state click launches new note
            views.setOnClickPendingIntent(
                R.id.widget_empty_view,
                buildQuickActionPendingIntent(context, "new_note", 1007)
            )

            // ListView item click template (delegates to Note item fill-in intent)
            val itemClickIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val itemClickPendingIntent = PendingIntent.getActivity(
                context,
                1100,
                itemClickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_notes_list, itemClickPendingIntent)

            return views
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
