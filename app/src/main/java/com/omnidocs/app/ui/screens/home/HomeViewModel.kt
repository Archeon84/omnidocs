package com.omnidocs.app.ui.screens.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.domain.model.Note
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.docimport.DocumentConverterFactory
import com.omnidocs.app.ui.theme.AppTheme
import com.omnidocs.app.ui.theme.ThemeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@ExperimentalCoroutinesApi
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: NotesRepository,
    private val converterFactory: DocumentConverterFactory,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val themeManager = ThemeManager(context)

    val currentTheme: StateFlow<AppTheme> = themeManager.currentTheme

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isGridView = MutableStateFlow(true)
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _selectedNoteIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedNoteIds: StateFlow<Set<String>> = _selectedNoteIds.asStateFlow()

    private val _importResult = MutableSharedFlow<Note>(extraBufferCapacity = 1)
    val importResult: SharedFlow<Note> = _importResult.asSharedFlow()

    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    val notes: StateFlow<List<Note>> = _searchQuery
        .flatMapLatest { query ->
            val flow = if (query.isEmpty()) {
                repository.getAllNotes()
            } else {
                repository.searchNotes(query)
            }
            flow.map { list ->
                // Sort: pinned first, then by updatedAt descending
                list.sortedWith(
                    compareByDescending<Note> { it.isPinned }
                        .thenByDescending { it.updatedAt }
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun setTheme(theme: AppTheme) {
        themeManager.setTheme(theme)
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleViewMode() {
        _isGridView.value = !_isGridView.value
    }

    fun toggleSelectionMode() {
        _isSelectionMode.value = !_isSelectionMode.value
        if (!_isSelectionMode.value) {
            _selectedNoteIds.value = emptySet()
        }
    }

    fun toggleNoteSelection(noteId: String) {
        val currentSelection = _selectedNoteIds.value.toMutableSet()
        if (currentSelection.contains(noteId)) {
            currentSelection.remove(noteId)
        } else {
            currentSelection.add(noteId)
        }
        _selectedNoteIds.value = currentSelection

        if (currentSelection.isEmpty()) {
            _isSelectionMode.value = false
        }
    }

    fun selectAllNotes() {
        val allNoteIds = notes.value.map { it.id }.toSet()
        _selectedNoteIds.value = allNoteIds
    }

    fun deselectAllNotes() {
        _selectedNoteIds.value = emptySet()
        _isSelectionMode.value = false
    }

    fun deleteSelectedNotes() {
        viewModelScope.launch {
            val selectedIds = _selectedNoteIds.value.toList()
            if (selectedIds.isNotEmpty()) {
                repository.deleteNotesByIds(selectedIds)
                _snackbarEvent.tryEmit("${selectedIds.size} note(s) deleted")
            }
            _selectedNoteIds.value = emptySet()
            _isSelectionMode.value = false
        }
    }

    fun deleteNote(note: Note) {
        viewModelScope.launch {
            repository.deleteNote(note)
            _snackbarEvent.tryEmit("Note deleted")
        }
    }

    /**
     * Imports a file by reading its content and creating a new note with the
     * extracted text. Uses [DocumentConverterFactory] to pick the right converter
     * for the MIME type. Emits the created note via [importResult] so the UI
     * can navigate to it.
     */
    fun importFile(uri: Uri, mimeType: String) {
        viewModelScope.launch {
            val note = withContext(Dispatchers.IO) {
                val fileName = getFileName(uri)
                val converter = converterFactory.getConverter(mimeType)
                val result = converter.convert(context, uri, fileName)

                if (result != null) {
                    repository.createNote(result.title, result.htmlContent, result.plainText, "en")
                } else {
                    null
                }
            }

            if (note != null) {
                _importResult.tryEmit(note)
                _snackbarEvent.tryEmit("Imported \"${note.title}\"")
            } else {
                _snackbarEvent.tryEmit("Could not import this file format")
            }
        }
    }

    /**
     * Resolves the display name (filename) of the document from its content URI.
     * Falls back to the last path segment if the content provider doesn't expose it.
     */
    private fun getFileName(uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    val name = it.getString(nameIndex)
                    // Strip extension for the note title
                    return name.substringBeforeLast(".")
                }
            }
        }
        // Fallback: extract from the URI path
        val pathSegment = uri.lastPathSegment ?: "Imported"
        return pathSegment.substringBeforeLast(".")
    }

    fun shareSelectedNotes(format: String) {
        viewModelScope.launch {
            val selectedIds = _selectedNoteIds.value
            val selectedNotes = notes.value.filter { it.id in selectedIds }

            if (selectedNotes.isEmpty()) return@launch

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val fileName = "OmniDocs_${dateFormat.format(Date())}"

            when (format) {
                "txt" -> shareAsTxt(selectedNotes, fileName)
                "html" -> shareAsHtml(selectedNotes, fileName)
                "pdf" -> shareAsPdf(selectedNotes, fileName)
                "doc" -> shareAsDoc(selectedNotes, fileName)
            }
        }
    }

    private fun shareAsTxt(notes: List<Note>, fileName: String) {
        val content = buildString {
            notes.forEachIndexed { index, note ->
                appendLine("=== ${note.title.ifEmpty { "Untitled" }} ===")
                appendLine("Date: ${SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(note.updatedAt))}")
                appendLine()
                appendLine(note.plainText)
                if (index < notes.size - 1) {
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
            }
        }

        val file = createTempFile(context, "$fileName.txt", content)
        shareFile(context, file, "text/plain")
    }

    private fun shareAsHtml(notes: List<Note>, fileName: String) {
        val content = buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html>")
            appendLine("<head>")
            appendLine("<meta charset='UTF-8'>")
            appendLine("<title>OmniDocs Export</title>")
            appendLine("<style>")
            appendLine("body { font-family: Arial, sans-serif; max-width: 800px; margin: 0 auto; padding: 20px; }")
            appendLine("h1 { color: #1976D2; border-bottom: 2px solid #1976D2; padding-bottom: 10px; }")
            appendLine(".note { margin-bottom: 30px; padding: 15px; border: 1px solid #ddd; border-radius: 8px; }")
            appendLine(".note-title { font-size: 1.2em; font-weight: bold; color: #333; }")
            appendLine(".note-date { color: #666; font-size: 0.9em; margin-bottom: 10px; }")
            appendLine(".note-content { line-height: 1.6; }")
            appendLine("</style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("<h1>OmniDocs Export</h1>")

            notes.forEach { note ->
                appendLine("<div class='note'>")
                appendLine("<div class='note-title'>${note.title.ifEmpty { "Untitled" }}</div>")
                appendLine("<div class='note-date'>${SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(note.updatedAt))}</div>")
                appendLine("<div class='note-content'>${note.content}</div>")
                appendLine("</div>")
            }

            appendLine("</body>")
            appendLine("</html>")
        }

        val file = createTempFile(context, "$fileName.html", content)
        shareFile(context, file, "text/html")
    }

    private fun shareAsPdf(notes: List<Note>, fileName: String) {
        try {
            val cacheDir = File(context.cacheDir, "shared_notes")
            cacheDir.mkdirs()
            val file = File(cacheDir, "$fileName.pdf")

            val pdfWriter = com.itextpdf.kernel.pdf.PdfWriter(file)
            val pdfDocument = com.itextpdf.kernel.pdf.PdfDocument(pdfWriter)
            val document = com.itextpdf.layout.Document(pdfDocument)

            // Add title
            val titleFont = com.itextpdf.kernel.font.PdfFontFactory.createFont()
            document.add(com.itextpdf.layout.element.Paragraph("OmniDocs Export")
                .setFontSize(24f)
                .setFont(titleFont)
                .setBold())

            document.add(com.itextpdf.layout.element.Paragraph(""))

            // Add each note
            notes.forEach { note ->
                // Note title
                document.add(com.itextpdf.layout.element.Paragraph(note.title.ifEmpty { "Untitled" })
                    .setFontSize(16f)
                    .setBold())

                // Note date
                document.add(com.itextpdf.layout.element.Paragraph(
                    "Date: ${SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(note.updatedAt))}")
                    .setFontSize(10f)
                    .setFontColor(com.itextpdf.kernel.colors.ColorConstants.GRAY))

                // Note content
                document.add(com.itextpdf.layout.element.Paragraph(note.plainText)
                    .setFontSize(12f))

                // Add separator
                document.add(com.itextpdf.layout.element.Paragraph(""))
                document.add(com.itextpdf.layout.element.Paragraph("────────────────────────────────────"))
                document.add(com.itextpdf.layout.element.Paragraph(""))
            }

            document.close()
            shareFile(context, file, "application/pdf")
        } catch (e: Exception) {
            // Fallback to txt if PDF fails
            shareAsTxt(notes, fileName)
        }
    }

    private fun shareAsDoc(notes: List<Note>, fileName: String) {
        try {
            val cacheDir = File(context.cacheDir, "shared_notes")
            cacheDir.mkdirs()
            val file = File(cacheDir, "$fileName.doc")

            val document = org.apache.poi.xwpf.usermodel.XWPFDocument()

            // Add title
            val titleParagraph = document.createParagraph()
            titleParagraph.alignment = org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER
            val titleRun = titleParagraph.createRun()
            titleRun.setText("OmniDocs Export")
            titleRun.setBold(true)
            titleRun.setFontSize(24)

            // Add empty line
            document.createParagraph()

            // Add each note
            notes.forEach { note ->
                // Note title
                val noteTitleParagraph = document.createParagraph()
                val noteTitleRun = noteTitleParagraph.createRun()
                noteTitleRun.setText(note.title.ifEmpty { "Untitled" })
                noteTitleRun.setBold(true)
                noteTitleRun.setFontSize(14)

                // Note date
                val dateParagraph = document.createParagraph()
                val dateRun = dateParagraph.createRun()
                dateRun.setText("Date: ${SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(note.updatedAt))}")
                dateRun.setFontSize(10)
                dateRun.setItalic(true)

                // Note content
                val contentParagraph = document.createParagraph()
                val contentRun = contentParagraph.createRun()
                contentRun.setText(note.plainText)
                contentRun.setFontSize(12)

                // Add empty line
                document.createParagraph()
            }

            val outputStream = java.io.FileOutputStream(file)
            document.write(outputStream)
            outputStream.close()
            document.close()

            shareFile(context, file, "application/msword")
        } catch (e: Exception) {
            // Fallback to txt if DOC fails
            shareAsTxt(notes, fileName)
        }
    }

    private fun createTempFile(context: Context, fileName: String, content: String): File {
        val cacheDir = File(context.cacheDir, "shared_notes")
        cacheDir.mkdirs()
        val file = File(cacheDir, fileName)
        file.writeText(content)
        return file
    }

    private fun shareFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "OmniDocs Notes Export")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(shareIntent, "Share Notes"))
    }
}
