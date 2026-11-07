package com.omnidocs.app.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.omnidocs.app.R
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.entity.NoteEntity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun noteDao(): NoteDao
}

/**
 * RemoteViewsService providing collection data to the OmniDocs Home Screen Widget.
 * Feeds recent and pinned notes directly into the scrollable list with sub-second responsiveness.
 */
class OmniDocsWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return OmniDocsWidgetViewsFactory(applicationContext)
    }
}

class OmniDocsWidgetViewsFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var notes: List<NoteEntity> = emptyList()
    private var noteDao: NoteDao? = null

    override fun onCreate() {
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java
            )
            noteDao = entryPoint.noteDao()
        } catch (_: Exception) {
            // Best-effort initialization; will retry in onDataSetChanged
        }
    }

    override fun onDataSetChanged() {
        if (noteDao == null) {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    WidgetEntryPoint::class.java
                )
                noteDao = entryPoint.noteDao()
            } catch (_: Exception) {
                return
            }
        }

        try {
            // Load non-deleted notes ordered by pinned first, then most recently updated.
            runBlocking {
                notes = noteDao?.getAllNotesSync()?.take(30) ?: emptyList()
            }
        } catch (_: Exception) {
            notes = emptyList()
        }
    }

    override fun onDestroy() {
        notes = emptyList()
    }

    override fun getCount(): Int = notes.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (position !in notes.indices) return null
        val note = notes[position]

        val views = RemoteViews(context.packageName, R.layout.widget_note_item)

        // Title
        val displayTitle = if (note.title.isNotBlank()) note.title else "Untitled Note"
        views.setTextViewText(R.id.widget_note_title, displayTitle)

        // Snippet (first 140 chars of clean plain text)
        val snippet = note.plainText.trim().replace("\\s+".toRegex(), " ")
        if (snippet.isNotBlank()) {
            views.setViewVisibility(R.id.widget_note_snippet, View.VISIBLE)
            views.setTextViewText(R.id.widget_note_snippet, snippet.take(140))
        } else {
            views.setViewVisibility(R.id.widget_note_snippet, View.GONE)
        }

        // Relative timestamp
        val relativeTime = DateUtils.getRelativeTimeSpanString(
            note.updatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
        views.setTextViewText(R.id.widget_note_time, relativeTime)

        // Pinned icon indicator
        views.setViewVisibility(
            R.id.widget_note_pin_icon,
            if (note.isPinned) View.VISIBLE else View.GONE
        )

        // Fill-in Intent for deep-linking directly into EditorScreen for this note
        val fillInIntent = Intent().apply {
            data = Uri.parse("omnidocs://note/${note.id}")
        }
        views.setOnClickFillInIntent(R.id.widget_note_item_root, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false
}
