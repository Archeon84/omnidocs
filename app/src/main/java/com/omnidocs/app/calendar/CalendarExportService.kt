package com.omnidocs.app.calendar

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.omnidocs.app.data.local.entity.ActionItemEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for serializing action items, tasks, and deadlines into standard
 * RFC 5545 iCalendar (.ics) format and sharing with external calendar applications.
 */
@Singleton
class CalendarExportService @Inject constructor() {

    private val icsDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Serializes a list of ActionItemEntity objects into a valid RFC 5545 iCalendar string.
     */
    fun exportToIcs(
        actionItems: List<ActionItemEntity>,
        calendarName: String = "OmniDocs Tasks & Deadlines"
    ): String {
        val nowFormatted = icsDateFormat.format(Date())

        return buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:-//OmniDocs//OmniDocs Knowledge Workspace//EN")
            appendLine("CALSCALE:GREGORIAN")
            appendLine("METHOD:PUBLISH")
            appendLine("X-WR-CALNAME:${escapeIcsText(calendarName)}")

            for (item in actionItems) {
                // If the item has a due date, create a VEVENT or VTODO
                val dueTimeMs = item.dueAt ?: (item.createdAt + 24 * 60 * 60 * 1000L)
                val dtStartFormatted = icsDateFormat.format(Date(dueTimeMs))
                val dtEndFormatted = icsDateFormat.format(Date(dueTimeMs + 60 * 60 * 1000L)) // 1 hour duration default

                val priorityVal = when (item.priority.lowercase()) {
                    "urgent" -> "1"
                    "high" -> "3"
                    "medium" -> "5"
                    "low" -> "9"
                    else -> "5"
                }

                val statusVal = when (item.status.lowercase()) {
                    "completed" -> "COMPLETED"
                    "cancelled" -> "CANCELLED"
                    "in_progress" -> "IN-PROCESS"
                    else -> "NEEDS-ACTION"
                }

                appendLine("BEGIN:VEVENT")
                appendLine("UID:${item.id}@omnidocs.app")
                appendLine("DTSTAMP:$nowFormatted")
                appendLine("DTSTART:$dtStartFormatted")
                appendLine("DTEND:$dtEndFormatted")
                appendLine("SUMMARY:${escapeIcsText(item.title)}")

                val desc = buildString {
                    if (item.description.isNotBlank()) {
                        append(item.description)
                    }
                    if (!item.owner.isNullOrBlank()) {
                        if (isNotEmpty()) append("\\n")
                        append("Owner: ${item.owner}")
                    }
                    if (isNotEmpty()) append("\\n")
                    append("Priority: ${item.priority.uppercase()}")
                }
                appendLine("DESCRIPTION:${escapeIcsText(desc)}")
                appendLine("PRIORITY:$priorityVal")
                appendLine("STATUS:$statusVal")

                // 15-minute advance reminder alarm
                appendLine("BEGIN:VALARM")
                appendLine("TRIGGER:-PT15M")
                appendLine("ACTION:DISPLAY")
                appendLine("DESCRIPTION:Reminder: ${escapeIcsText(item.title)}")
                appendLine("END:VALARM")

                appendLine("END:VEVENT")
            }

            appendLine("END:VCALENDAR")
        }
    }

    /**
     * Saves the .ics calendar string to cache and launches the Android share sheet.
     */
    fun shareIcsCalendar(
        context: Context,
        icsContent: String,
        baseFilename: String = "omnidocs_tasks"
    ): Boolean {
        return try {
            val exportDir = File(context.cacheDir, "exports")
            exportDir.mkdirs()
            val fileName = "${baseFilename}_${System.currentTimeMillis()}.ics"
            val file = File(exportDir, fileName)
            file.writeText(icsContent, Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/calendar"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "OmniDocs Calendar Export (.ics)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Share Calendar (.ics)").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            android.util.Log.e("CalendarExportService", "Failed to share calendar export", e)
            false
        }
    }

    /**
     * Escapes special characters per RFC 5545: backslashes, semicolons, and commas.
     */
    private fun escapeIcsText(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
    }
}
