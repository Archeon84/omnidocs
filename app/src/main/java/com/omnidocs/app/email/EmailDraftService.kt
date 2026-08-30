package com.omnidocs.app.email

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.omnidocs.app.data.local.entity.ActionItemEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class EmailDraft(
    val subject: String,
    val bodyText: String,
    val bodyHtml: String,
    val recipients: List<String> = emptyList()
)

/**
 * Service for generating structured email drafts from meeting notes,
 * extracted decisions, and action items, and launching the system email client.
 */
@Singleton
class EmailDraftService @Inject constructor() {

    private val dateFormatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private val dateTimeFormatter = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

    /**
     * Composes a professional meeting summary email draft.
     */
    fun generateMeetingSummaryDraft(
        noteTitle: String,
        summary: String,
        decisions: List<String> = emptyList(),
        actionItems: List<ActionItemEntity> = emptyList(),
        openQuestions: List<String> = emptyList(),
        meetingDateMs: Long = System.currentTimeMillis()
    ): EmailDraft {
        val formattedDate = dateFormatter.format(Date(meetingDateMs))
        val subject = "[Meeting Summary] $noteTitle ($formattedDate)"

        // Plain text version
        val textBuilder = StringBuilder()
        textBuilder.appendLine("Hi team,\n")
        textBuilder.appendLine("Here is the summary and action items from our meeting on $formattedDate: $noteTitle.\n")

        if (summary.isNotBlank()) {
            textBuilder.appendLine("--- EXECUTIVE SUMMARY ---")
            textBuilder.appendLine(summary.trim())
            textBuilder.appendLine()
        }

        if (decisions.isNotEmpty()) {
            textBuilder.appendLine("--- KEY DECISIONS ---")
            decisions.forEachIndexed { idx, d ->
                textBuilder.appendLine("• $d")
            }
            textBuilder.appendLine()
        }

        if (actionItems.isNotEmpty()) {
            textBuilder.appendLine("--- ACTION ITEMS & NEXT STEPS ---")
            actionItems.forEachIndexed { idx, item ->
                val ownerStr = if (!item.owner.isNullOrBlank()) " [Owner: ${item.owner}]" else ""
                val dueStr = item.dueAt?.let { " (Due: ${dateFormatter.format(Date(it))})" } ?: ""
                val priorityStr = if (item.priority.lowercase() in listOf("high", "urgent")) " [${item.priority.uppercase()}]" else ""
                textBuilder.appendLine("${idx + 1}. ${item.title}$priorityStr$ownerStr$dueStr")
                if (item.description.isNotBlank()) {
                    textBuilder.appendLine("   Details: ${item.description}")
                }
            }
            textBuilder.appendLine()
        }

        if (openQuestions.isNotEmpty()) {
            textBuilder.appendLine("--- OPEN QUESTIONS ---")
            openQuestions.forEach { q ->
                textBuilder.appendLine("? $q")
            }
            textBuilder.appendLine()
        }

        textBuilder.appendLine("Generated with OmniDocs Evidence-First Knowledge Workspace.")

        // HTML version
        val htmlBuilder = StringBuilder()
        htmlBuilder.append("<h3>Meeting Summary: $noteTitle</h3>")
        htmlBuilder.append("<p><strong>Date:</strong> $formattedDate</p>")

        if (summary.isNotBlank()) {
            htmlBuilder.append("<h4>Executive Summary</h4>")
            htmlBuilder.append("<p>${summary.trim().replace("\n", "<br/>")}</p>")
        }

        if (decisions.isNotEmpty()) {
            htmlBuilder.append("<h4>Key Decisions</h4><ul>")
            decisions.forEach { d ->
                htmlBuilder.append("<li>$d</li>")
            }
            htmlBuilder.append("</ul>")
        }

        if (actionItems.isNotEmpty()) {
            htmlBuilder.append("<h4>Action Items</h4><ol>")
            actionItems.forEach { item ->
                val ownerStr = if (!item.owner.isNullOrBlank()) " <strong>(Owner: ${item.owner})</strong>" else ""
                val dueStr = item.dueAt?.let { " - <em>Due: ${dateFormatter.format(Date(it))}</em>" } ?: ""
                val priorityBadge = if (item.priority.lowercase() in listOf("high", "urgent")) " <span style='color:red;'>[${item.priority.uppercase()}]</span>" else ""
                htmlBuilder.append("<li><strong>${item.title}</strong>$priorityBadge$ownerStr$dueStr")
                if (item.description.isNotBlank()) {
                    htmlBuilder.append("<br/><small>${item.description}</small>")
                }
                htmlBuilder.append("</li>")
            }
            htmlBuilder.append("</ol>")
        }

        if (openQuestions.isNotEmpty()) {
            htmlBuilder.append("<h4>Open Questions</h4><ul>")
            openQuestions.forEach { q ->
                htmlBuilder.append("<li>$q</li>")
            }
            htmlBuilder.append("</ul>")
        }

        htmlBuilder.append("<hr/><p><small>Generated with OmniDocs Evidence-First Knowledge Workspace.</small></p>")

        return EmailDraft(
            subject = subject,
            bodyText = textBuilder.toString().trim(),
            bodyHtml = htmlBuilder.toString().trim()
        )
    }

    /**
     * Launches the default system email client with the prefilled draft.
     */
    fun launchEmailClient(context: Context, draft: EmailDraft): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                if (draft.recipients.isNotEmpty()) {
                    putExtra(Intent.EXTRA_EMAIL, draft.recipients.toTypedArray())
                }
                putExtra(Intent.EXTRA_SUBJECT, draft.subject)
                putExtra(Intent.EXTRA_TEXT, draft.bodyText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Send Email Draft").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            android.util.Log.e("EmailDraftService", "Failed to launch email client", e)
            false
        }
    }
}
