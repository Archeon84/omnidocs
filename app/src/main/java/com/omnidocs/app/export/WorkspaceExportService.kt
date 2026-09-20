package com.omnidocs.app.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.omnidocs.app.calendar.CalendarExportService
import com.omnidocs.app.data.local.ActionItemDao
import com.omnidocs.app.data.local.ClaimDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.graph.GraphEngine
import com.omnidocs.app.graph.GraphExportService
import com.omnidocs.app.study.Flashcard
import com.omnidocs.app.study.StudyCardType
import com.omnidocs.app.study.StudyDeck
import com.omnidocs.app.study.StudyExportService
import com.omnidocs.app.util.MarkdownCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for packaging the entire OmniDocs evidence-first workspace into a
 * standalone, human-readable, and machine-interoperable ZIP archive containing:
 * - notes/ (Markdown with YAML frontmatter and HTML)
 * - graph/ (GraphML XML and W3C JSON-LD)
 * - calendar/ (RFC 5545 .ics events and deadlines)
 * - study/ (Markdown flashcards and Anki TSV)
 * - evidence/ (Claims, action items, and manifest)
 */
@Singleton
class WorkspaceExportService @Inject constructor(
    private val noteDao: NoteDao,
    private val claimDao: ClaimDao,
    private val actionItemDao: ActionItemDao,
    private val graphEngine: GraphEngine,
    private val graphExportService: GraphExportService,
    private val calendarExportService: CalendarExportService,
    private val studyExportService: StudyExportService
) {

    /**
     * Builds the complete workspace ZIP file in the specified destination.
     */
    suspend fun buildWorkspaceZip(zipFile: File): Long = withContext(Dispatchers.IO) {
        val notes = noteDao.getAllNotes().first()
        val allActionItems = actionItemDao.getAllActionItems().first()
        val graphData = graphEngine.buildGraph()

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // 1. Manifest
            val manifest = JSONObject().apply {
                put("app", "OmniDocs Knowledge Workspace")
                put("formatVersion", "1.0")
                put("exportDate", System.currentTimeMillis())
                put("noteCount", notes.size)
                put("actionItemCount", allActionItems.size)
                put("graphNodeCount", graphData.nodes.size)
                put("graphEdgeCount", graphData.edges.size)
            }
            writeZipEntry(zos, "manifest.json", manifest.toString(2))

            // 2. Notes (Markdown + HTML). Titles are not unique: two notes can
            //    sanitize to the same filename, and ZipOutputStream throws on a
            //    duplicate entry, aborting the entire export. Deduplicate with a
            //    numeric suffix when a collision occurs.
            val usedMdNames = mutableSetOf<String>()
            val usedHtmlNames = mutableSetOf<String>()
            for (note in notes) {
                val base = sanitizeFilename(note.title.ifBlank { note.id })
                val safeTitle = uniqueName(base, usedMdNames)
                val safeHtmlTitle = uniqueName(base, usedHtmlNames)
                val mdContent = buildMarkdownWithFrontmatter(note)
                writeZipEntry(zos, "notes/$safeTitle.md", mdContent)
                writeZipEntry(zos, "notes/html/$safeHtmlTitle.html", note.content)
            }

            // 3. Calendar (.ics)
            if (allActionItems.isNotEmpty()) {
                val ics = calendarExportService.exportToIcs(allActionItems, "OmniDocs Workspace Calendar")
                writeZipEntry(zos, "calendar/tasks_and_deadlines.ics", ics)
            }

            // 4. Graph (GraphML + JSON-LD)
            val graphml = graphExportService.exportToGraphML(graphData)
            writeZipEntry(zos, "graph/knowledge_graph.graphml", graphml)
            val jsonLd = graphExportService.exportToJsonLd(graphData)
            writeZipEntry(zos, "graph/knowledge_graph.jsonld", jsonLd)

            // 5. Evidence & Action Items
            val actionsArray = JSONArray()
            for (item in allActionItems) {
                actionsArray.put(JSONObject().apply {
                    put("id", item.id)
                    put("noteId", item.noteId)
                    put("title", item.title)
                    put("description", item.description)
                    put("owner", item.owner ?: "")
                    put("dueAt", item.dueAt ?: -1)
                    put("status", item.status)
                    put("priority", item.priority)
                })
            }
            writeZipEntry(zos, "evidence/action_items.json", actionsArray.toString(2))

            // 6. Study Decks
            val allCards = mutableListOf<Flashcard>()
            for (note in notes.take(20)) {
                if (note.title.isNotBlank() && note.plainText.length > 30) {
                    allCards.add(
                        Flashcard(
                            id = note.id,
                            noteId = note.id,
                            type = StudyCardType.QA,
                            prompt = "What is discussed in \"${note.title}\"?",
                            answer = note.plainText.take(250),
                            sourceSnippet = note.plainText.take(150),
                            sourceTitle = note.title,
                            tags = listOf("note_review")
                        )
                    )
                }
            }
            if (allCards.isNotEmpty()) {
                val deck = StudyDeck(title = "Workspace Comprehensive Deck", noteId = "workspace", cards = allCards)
                writeZipEntry(zos, "study/flashcards.md", studyExportService.exportToMarkdown(deck))
                writeZipEntry(zos, "study/anki_deck.tsv", studyExportService.exportToAnkiTsv(deck))
            }
        }

        zipFile.length()
    }

    /**
     * Creates the workspace ZIP file in app cache and launches the Android share sheet.
     */
    suspend fun exportAndShareWorkspace(
        context: Context,
        baseName: String = "omnidocs_workspace"
    ): Boolean {
        return try {
            val exportDir = File(context.cacheDir, "exports")
            exportDir.mkdirs()
            val fileName = "${baseName}_${System.currentTimeMillis()}.zip"
            val zipFile = File(exportDir, fileName)

            buildWorkspaceZip(zipFile)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zipFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "OmniDocs Full Workspace Backup (.zip)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Share Workspace Backup").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            android.util.Log.e("WorkspaceExportService", "Failed to export and share workspace", e)
            false
        }
    }

    private fun writeZipEntry(zos: ZipOutputStream, entryName: String, content: String) {
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        val bytes = content.toByteArray(Charsets.UTF_8)
        zos.write(bytes, 0, bytes.size)
        zos.closeEntry()
    }

    private fun buildMarkdownWithFrontmatter(note: NoteEntity): String {
        return buildString {
            appendLine("---")
            appendLine("id: \"${note.id}\"")
            appendLine("title: \"${escapeYaml(note.title)}\"")
            appendLine("tags: ${note.tags}")
            appendLine("language: \"${note.language}\"")
            appendLine("createdAt: ${note.createdAt}")
            appendLine("updatedAt: ${note.updatedAt}")
            appendLine("---")
            appendLine()
            val md = MarkdownCodec.htmlToMarkdown(note.content)
            appendLine(md)
        }
    }

    private fun sanitizeFilename(name: String): String {
        val sanitized = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(60)
        return sanitized.ifBlank { "untitled" }
    }

    /** Returns base, or base_2 / base_3 ... when base was already used in this archive. */
    private fun uniqueName(base: String, used: MutableSet<String>): String {
        if (used.add(base)) return base
        var counter = 2
        while (!used.add("${base}_$counter")) counter++
        return "${base}_$counter"
    }

    private fun escapeYaml(text: String): String {
        return text.replace("\"", "\\\"")
    }
}
