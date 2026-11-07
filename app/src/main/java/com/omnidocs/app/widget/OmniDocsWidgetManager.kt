package com.omnidocs.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.omnidocs.app.R

/**
 * Central manager for broadcasting updates to OmniDocs App Widgets.
 * Keeps Home Screen widgets synchronized in real-time when notes are saved,
 * edited, or deleted.
 */
object OmniDocsWidgetManager {

    private const val TAG = "OmniDocsWidgetManager"

    /**
     * Broadcast an update to all active OmniDocs widgets and refresh collection data.
     */
    fun notifyDataChanged(context: Context) {
        try {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val componentName = ComponentName(context, OmniDocsQuickCaptureWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            if (appWidgetIds.isEmpty()) return

            // Refresh collection data
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_notes_list)

            // Re-render widget frames
            for (appWidgetId in appWidgetIds) {
                OmniDocsQuickCaptureWidget.updateWidget(context, appWidgetManager, appWidgetId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh OmniDocs widgets", e)
        }
    }
}
