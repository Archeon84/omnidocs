# OmniDocs Thinking Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three interconnected AI features -- Knowledge Graph, Voice Capture, and Note Intelligence -- that transform OmniDocs from a note app into an AI-powered thinking tool.

**Architecture:** Three phases, each independently shippable. Phase 1 (Note Intelligence) modifies the existing editor with inline AI Q&A, smart explain, and concept extraction. Phase 2 (Voice Capture) adds speech-to-text with LLM-powered structuring. Phase 3 (Knowledge Graph) adds a D3.js force-directed visualization of note relationships. All AI features use the existing on-device LLM (llama.cpp) via `LlamaCppService.generate()`.

**Tech Stack:** Kotlin/Compose, Room/SQLCipher, Hilt, llama.cpp JNI, WebView (for graph), Android SpeechRecognizer API, D3.js (bundled in assets)

## Global Constraints

- Target: Xiaomi 13 Ultra, arm64-v8a only
- Min SDK: 26, Target SDK: 34
- Kotlin/Compose M3, Room/SQLCipher, Hilt DI
- On-device LLM: Qwen3 1.7B (ChatML) or Llama 3.2 3B (Llama3 format)
- `LlamaCppService.generate(prompt: String, maxTokens: Int = 128): String?` -- returns null on failure
- `PromptBuilder.buildPrompt(format: PromptFormat, systemPrompt: String, userPrompt: String): String`
- `AiOutputProcessor.process(output: String): String` -- strips thinking tags
- Database version is currently 5 (tags column). New migration will be 5->6.
- All new files go under `app/src/main/java/com/omnidocs/app/`
- HTML assets go under `app/src/main/assets/`
- No external Android dependencies beyond what's in build.gradle.kts

---

## Phase 1: Note Intelligence

### Task 1: NoteIntelligenceService

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/ai/NoteIntelligenceService.kt`

**Interfaces:**
- Consumes: `LlamaCppService.generate(prompt, maxTokens)`, `PromptBuilder.buildPrompt()`, `AiOutputProcessor.process()`, `NotesRepository` (for concept lookup)
- Produces: `NoteIntelligenceService.askAboutNote()`, `.explainText()`, `.extractConcepts()`

- [ ] **Step 1: Create NoteIntelligenceService.kt**

```kotlin
package com.omnidocs.app.ai

import android.util.Log
import com.omnidocs.app.data.repository.NotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NoteIntelligenceService"

data class ExtractedConcept(
    val name: String,
    val description: String,
    val existingNoteId: String?
)

@Singleton
class NoteIntelligenceService @Inject constructor(
    private val llamaCppService: LlamaCppService,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val repository: NotesRepository
) {
    private suspend fun getActiveModel(): ModelInfo? {
        val selectedId = modelPreferences.selectedModelId.first()
        val downloaded = modelDownloadManager.getDownloadedModels()
        return downloaded.find { it.id == selectedId && it.isDownloaded }
            ?: downloaded.firstOrNull { it.isDownloaded }
    }

    private fun truncateText(text: String, maxChars: Int = 4000): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "\n\n[Truncated — showing first $maxChars chars]"
    }

    suspend fun askAboutNote(
        noteContent: String,
        question: String,
        language: String
    ): String = withContext(Dispatchers.IO) {
        val model = getActiveModel() ?: return@withContext "No AI model downloaded."
        val langInstruction = if (language != "en") "\nRespond in $language language." else ""

        val systemPrompt = "You are a helpful assistant that answers questions about a note. " +
            "Use the note content as your source of truth. If the answer isn't in the note, say so. " +
            "Be concise and direct.$langInstruction"

        val userPrompt = "Note content:\n${truncateText(noteContent)}\n\nQuestion: $question"

        val prompt = PromptBuilder.buildPrompt(model.promptFormat, systemPrompt, userPrompt)
        val result = llamaCppService.generate(prompt, maxTokens = 1000)
        result?.let { AiOutputProcessor.process(it) } ?: "AI could not generate a response."
    }

    suspend fun explainText(
        fullNote: String,
        selectedText: String,
        language: String
    ): String = withContext(Dispatchers.IO) {
        val model = getActiveModel() ?: return@withContext "No AI model downloaded."
        val langInstruction = if (language != "en") "\nRespond in $language language." else ""

        val systemPrompt = "You are a helpful assistant that explains text in context. " +
            "The user selected a portion of their note and wants an explanation. " +
            "Provide a clear, concise explanation of the selected text, using the full note as context. " +
            "If it's a technical term, define it. If it's a concept, explain it.$langInstruction"

        val userPrompt = "Full note:\n${truncateText(fullNote)}\n\nSelected text: \"$selectedText\"\n\nExplain this selected text."

        val prompt = PromptBuilder.buildPrompt(model.promptFormat, systemPrompt, userPrompt)
        val result = llamaCppService.generate(prompt, maxTokens = 800)
        result?.let { AiOutputProcessor.process(it) } ?: "AI could not generate an explanation."
    }

    suspend fun extractConcepts(
        noteContent: String,
        language: String
    ): List<ExtractedConcept> = withContext(Dispatchers.IO) {
        val model = getActiveModel() ?: return@withContext emptyList()
        val langInstruction = if (language != "en") "\nRespond in $language language." else ""

        val systemPrompt = "You are a knowledge extraction assistant. Analyze the note and identify 3-5 key concepts. " +
            "For each concept, provide a short name and a one-sentence description. " +
            "Return ONLY a JSON array. No markdown, no explanation.$langInstruction"

        val userPrompt = "Extract key concepts from this note:\n${truncateText(noteContent)}\n\n" +
            "Return JSON: [{\"name\": \"Concept Name\", \"description\": \"One sentence description.\"}]"

        val prompt = PromptBuilder.buildPrompt(model.promptFormat, systemPrompt, userPrompt)
        val result = llamaCppService.generate(prompt, maxTokens = 800) ?: return@withContext emptyList()
        val cleaned = AiOutputProcessor.process(result)

        parseConcepts(cleaned)
    }

    private suspend fun parseConcepts(json: String): List<ExtractedConcept> {
        return try {
            // Extract JSON array from response (may be wrapped in markdown code blocks)
            val jsonStr = json.replace(Regex("```json\\s*"), "").replace(Regex("```\\s*"), "").trim()
            val arr = JSONArray(jsonStr)
            val concepts = mutableListOf<ExtractedConcept>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name", "")
                val description = obj.optString("description", "")
                if (name.isNotBlank()) {
                    // Check if a note with this title already exists
                    val existing = repository.searchNotes(name).first().find {
                        it.title.equals(name, ignoreCase = true)
                    }
                    concepts.add(ExtractedConcept(name, description, existing?.id))
                }
            }
            concepts
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse concepts: $json", e)
            emptyList()
        }
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ai/NoteIntelligenceService.kt
git commit -m "feat: add NoteIntelligenceService for AI-powered note Q&A, explain, and concept extraction"
```

---

### Task 2: IntelligencePanel UI

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/ui/screens/editor/IntelligencePanel.kt`

**Interfaces:**
- Consumes: Nothing (pure UI component)
- Produces: `IntelligencePanel` composable, `ConceptDialog` composable

- [ ] **Step 1: Create IntelligencePanel.kt**

```kotlin
package com.omnidocs.app.ui.screens.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import com.omnidocs.app.ai.ExtractedConcept

data class IntelligenceMessage(
    val role: String, // "user" or "assistant"
    val content: String
)

@Composable
fun IntelligencePanel(
    messages: List<IntelligenceMessage>,
    isLoading: Boolean,
    onAsk: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new message arrives
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Ask about this note",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Messages
            if (messages.isNotEmpty()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { msg ->
                        val isUser = msg.role == "user"
                        Surface(
                            shape = RoundedCornerShape(
                                topStart = 12.dp, topEnd = 12.dp,
                                bottomStart = if (isUser) 12.dp else 4.dp,
                                bottomEnd = if (isUser) 4.dp else 12.dp
                            ),
                            color = if (isUser)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(0.85f)
                        ) {
                            Text(
                                text = msg.content,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(10.dp),
                                color = if (isUser)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (isLoading) {
                        item {
                            Row(
                                modifier = Modifier.padding(start = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Thinking...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Input
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask a question...", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                FilledIconButton(
                    onClick = {
                        if (inputText.isNotBlank() && !isLoading) {
                            onAsk(inputText)
                            inputText = ""
                        }
                    },
                    enabled = inputText.isNotBlank() && !isLoading,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ConceptDialog(
    concepts: List<ExtractedConcept>,
    onCreateNote: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Extracted Concepts") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(concepts) { concept ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = concept.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                if (concept.existingNoteId != null) {
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.tertiaryContainer
                                    ) {
                                        Text(
                                            "Exists",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = concept.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (concept.existingNoteId == null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { onCreateNote(concept.name, concept.description) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Create Note")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/editor/IntelligencePanel.kt
git commit -m "feat: add IntelligencePanel and ConceptDialog composables"
```

---

### Task 3: EditorViewModel Intelligence Integration

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorViewModel.kt`

**Interfaces:**
- Consumes: `NoteIntelligenceService.askAboutNote()`, `.explainText()`, `.extractConcepts()`
- Produces: `EditorViewModel.askAboutNote()`, `.explainSelectedText()`, `.extractConcepts()`, `intelligenceMessages`, `isIntelligenceLoading`, `intelligenceConcepts`, `showConceptDialog`

- [ ] **Step 1: Add imports and state to EditorViewModel**

Add these imports at the top of EditorViewModel.kt:
```kotlin
import com.omnidocs.app.ai.NoteIntelligenceService
import com.omnidocs.app.ai.ExtractedConcept
import com.omnidocs.app.ui.screens.editor.IntelligenceMessage
```

Add these state properties inside the class (after the existing `_aiPreview` state):
```kotlin
// ── Note Intelligence ──────────────────────────────────────────────
private val _intelligenceMessages = MutableStateFlow<List<IntelligenceMessage>>(emptyList())
val intelligenceMessages: StateFlow<List<IntelligenceMessage>> = _intelligenceMessages.asStateFlow()

private val _isIntelligenceLoading = MutableStateFlow(false)
val isIntelligenceLoading: StateFlow<Boolean> = _isIntelligenceLoading.asStateFlow()

private val _intelligenceConcepts = MutableStateFlow<List<ExtractedConcept>>(emptyList())
val intelligenceConcepts: StateFlow<List<ExtractedConcept>> = _intelligenceConcepts.asStateFlow()

private val _showConceptDialog = MutableStateFlow(false)
val showConceptDialog: StateFlow<Boolean> = _showConceptDialog.asStateFlow()
```

Add `noteIntelligenceService: NoteIntelligenceService` to the constructor:
```kotlin
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val repository: NotesRepository,
    private val aiService: AiService,
    private val autoTagger: AutoTagger,
    private val noteIntelligenceService: NoteIntelligenceService
) : ViewModel() {
```

- [ ] **Step 2: Add intelligence methods**

Add these methods inside EditorViewModel (after the `dismissAiPreview()` method):
```kotlin
// ── Note Intelligence Methods ──────────────────────────────────────

fun askAboutNote(question: String) {
    val noteContent = _content.value
    if (noteContent.isBlank() || question.isBlank()) return

    _intelligenceMessages.value = _intelligenceMessages.value + IntelligenceMessage("user", question)
    _isIntelligenceLoading.value = true

    viewModelScope.launch {
        try {
            val answer = noteIntelligenceService.askAboutNote(
                noteContent = stripHtml(noteContent),
                question = question,
                language = _currentLanguage.value
            )
            _intelligenceMessages.value = _intelligenceMessages.value +
                IntelligenceMessage("assistant", answer)
        } catch (e: Exception) {
            _intelligenceMessages.value = _intelligenceMessages.value +
                IntelligenceMessage("assistant", "Error: ${e.message ?: "Unknown error"}")
        } finally {
            _isIntelligenceLoading.value = false
        }
    }
}

fun explainSelectedText(selectedText: String) {
    val noteContent = _content.value
    if (noteContent.isBlank() || selectedText.isBlank()) return

    _intelligenceMessages.value = _intelligenceMessages.value +
        IntelligenceMessage("user", "Explain: \"$selectedText\"")
    _isIntelligenceLoading.value = true

    viewModelScope.launch {
        try {
            val explanation = noteIntelligenceService.explainText(
                fullNote = stripHtml(noteContent),
                selectedText = selectedText,
                language = _currentLanguage.value
            )
            _intelligenceMessages.value = _intelligenceMessages.value +
                IntelligenceMessage("assistant", explanation)
        } catch (e: Exception) {
            _intelligenceMessages.value = _intelligenceMessages.value +
                IntelligenceMessage("assistant", "Error: ${e.message ?: "Unknown error"}")
        } finally {
            _isIntelligenceLoading.value = false
        }
    }
}

fun extractConcepts() {
    val noteContent = _content.value
    if (noteContent.isBlank()) return

    _isIntelligenceLoading.value = true
    viewModelScope.launch {
        try {
            val concepts = noteIntelligenceService.extractConcepts(
                noteContent = stripHtml(noteContent),
                language = _currentLanguage.value
            )
            _intelligenceConcepts.value = concepts
            _showConceptDialog.value = true
        } catch (e: Exception) {
            _snackbarEvent.tryEmit("Failed to extract concepts: ${e.message}")
        } finally {
            _isIntelligenceLoading.value = false
        }
    }
}

fun createNoteFromConcept(title: String, description: String) {
    viewModelScope.launch {
        try {
            val note = repository.createNote(
                title = title,
                content = "<p>$description</p>",
                plainText = description,
                language = _currentLanguage.value
            )
            _snackbarEvent.tryEmit("Created note: $title")
        } catch (e: Exception) {
            _snackbarEvent.tryEmit("Failed to create note: ${e.message}")
        }
    }
}

fun dismissConceptDialog() {
    _showConceptDialog.value = false
}

fun clearIntelligenceChat() {
    _intelligenceMessages.value = emptyList()
}
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorViewModel.kt
git commit -m "feat: add intelligence state and methods to EditorViewModel"
```

---

### Task 4: Editor.html Text Selection + Intelligence Button

**Files:**
- Modify: `app/src/main/assets/editor.html`
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt`

**Interfaces:**
- Consumes: `EditorViewModel.askAboutNote()`, `.explainSelectedText()`, `.extractConcepts()`, `intelligenceMessages`, `isIntelligenceLoading`, `intelligenceConcepts`, `showConceptDialog`
- Produces: JS `getSelectedText()` function, `onTextSelectionChanged()` JS interface, intelligence panel integration in EditorScreen

- [ ] **Step 1: Add JS functions to editor.html**

Add before the closing `</script>` tag in editor.html:
```javascript
// Get currently selected text
function getSelectedText() {
    var sel = window.getSelection();
    return sel ? sel.toString() : "";
}

// Notify Android when text selection changes
var lastSelectedText = "";
document.addEventListener('selectionchange', function() {
    var sel = window.getSelection();
    var text = sel ? sel.toString() : "";
    if (text !== lastSelectedText) {
        lastSelectedText = text;
        try {
            Android.onTextSelectionChanged(text);
        } catch(e) {}
    }
});
```

- [ ] **Step 2: Add onTextSelectionChanged to JS interface in EditorScreen.kt**

In the `RichTextEditor` composable's `addJavascriptInterface` block (inside the `AndroidView` factory), add a new method:

```kotlin
@JavascriptInterface
fun onTextSelectionChanged(selectedText: String) {
    onTextSelectionChanged(selectedText)
}
```

And update the `RichTextEditor` composable signature to include:
```kotlin
fun RichTextEditor(
    content: String,
    onContentChange: (String) -> Unit,
    onFormatStateChange: (FormatState) -> Unit = {},
    onWebViewCreated: (WebView) -> Unit = {},
    onEditorFocusChanged: (Boolean) -> Unit = {},
    onTextSelectionChanged: (String) -> Unit = {}, // NEW
    modifier: Modifier = Modifier
)
```

- [ ] **Step 3: Add intelligence panel + concept dialog to EditorScreen**

In the `EditorScreen` composable, add these state variables after the existing `isEditorFocused` state:
```kotlin
val intelligenceMessages by viewModel.intelligenceMessages.collectAsState()
val isIntelligenceLoading by viewModel.isIntelligenceLoading.collectAsState()
val intelligenceConcepts by viewModel.intelligenceConcepts.collectAsState()
val showConceptDialog by viewModel.showConceptDialog.collectAsState()
var showIntelligencePanel by remember { mutableStateOf(false) }
var selectedText by remember { mutableStateOf("") }
```

Inside the `Column` in the Scaffold content, after the `Box` containing `RichTextEditor`, add:
```kotlin
// Intelligence panel
AnimatedVisibility(
    visible = showIntelligencePanel,
    enter = slideInVertically(initialOffsetY = { it }),
    exit = slideOutVertically(targetOffsetY = { it })
) {
    IntelligencePanel(
        messages = intelligenceMessages,
        isLoading = isIntelligenceLoading,
        onAsk = { viewModel.askAboutNote(it) },
        onDismiss = { showIntelligencePanel = false }
    )
}
```

Update the `RichTextEditor` call to include the new callback:
```kotlin
RichTextEditor(
    content = content,
    onContentChange = { viewModel.updateContent(it) },
    onFormatStateChange = { formatState = it },
    onWebViewCreated = { webViewRef = it },
    onEditorFocusChanged = { isEditorFocused = it },
    onTextSelectionChanged = { text ->
        selectedText = text
    },
    modifier = Modifier.fillMaxSize()
)
```

Add the concept dialog after the existing dialogs:
```kotlin
// Concept extraction dialog
if (showConceptDialog) {
    ConceptDialog(
        concepts = intelligenceConcepts,
        onCreateNote = { name, desc -> viewModel.createNoteFromConcept(name, desc) },
        onDismiss = { viewModel.dismissConceptDialog() }
    )
}
```

- [ ] **Step 4: Add intelligence button to FormattingToolbar**

In `EditorScreen.kt`, update the `FormattingToolbar` signature to add:
```kotlin
fun FormattingToolbar(
    formatState: FormatState = FormatState(),
    onBoldClick: () -> Unit,
    onItalicClick: () -> Unit,
    onUnderlineClick: () -> Unit,
    onStrikeClick: () -> Unit = {},
    onListClick: () -> Unit,
    onNumberedListClick: () -> Unit,
    onHeadingClick: () -> Unit,
    onQuoteClick: () -> Unit,
    onCodeClick: () -> Unit,
    onImageClick: () -> Unit = {},
    onAudioClick: () -> Unit = {},
    onToggleSections: () -> Unit = {},
    onIntelligenceClick: () -> Unit = {}, // NEW
    onConceptExtract: () -> Unit = {}     // NEW
)
```

Add these buttons in the `FormattingToolbar` Row, after the last `ToolbarDivider()`:
```kotlin
// Group 6: AI Intelligence
FormatIconButton(
    icon = Icons.Default.AutoAwesome,
    contentDescription = "Ask AI",
    isActive = false,
    onClick = onIntelligenceClick
)
FormatIconButton(
    icon = Icons.Default.Lightbulb,
    contentDescription = "Extract Concepts",
    isActive = false,
    onClick = onConceptExtract
)
```

Update the `FormattingToolbar` call in `EditorScreen` to pass the new callbacks:
```kotlin
FormattingToolbar(
    formatState = formatState,
    onBoldClick = { webViewRef?.evaluateJavascript("formatText('bold')", null) },
    // ... existing callbacks ...
    onIntelligenceClick = { showIntelligencePanel = !showIntelligencePanel },
    onConceptExtract = { viewModel.extractConcepts() }
)
```

- [ ] **Step 5: Add explain chip when text is selected**

In `EditorScreen.kt`, after the `RichTextEditor` Box, add a conditional explain chip:
```kotlin
// Explain chip (appears when text is selected)
AnimatedVisibility(
    visible = selectedText.isNotBlank() && !showIntelligencePanel,
    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
    exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
) {
    Surface(
        onClick = {
            viewModel.explainSelectedText(selectedText)
            showIntelligencePanel = true
            selectedText = ""
        },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Explain selected text",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
```

Note: The `AnimatedVisibility` needs to be inside the `Box` that contains the `RichTextEditor` for proper alignment, or in a parent `Box` wrapping both. Adjust placement accordingly.

- [ ] **Step 6: Verify build compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/assets/editor.html app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt
git commit -m "feat: integrate Note Intelligence into editor with text selection, AI chat panel, and concept extraction"
```

---

## Phase 2: Voice Capture

### Task 5: VoiceCaptureManager

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/voice/VoiceCaptureManager.kt`

**Interfaces:**
- Consumes: `LlamaCppService.generate()`, `PromptBuilder.buildPrompt()`, `AiOutputProcessor.process()`, `NotesRepository.createNote()`
- Produces: `VoiceCaptureManager.startListening()`, `.stopListening()`, `.structureTranscript()`, `.transcript`, `.isListening`

- [ ] **Step 1: Create VoiceCaptureManager.kt**

```kotlin
package com.omnidocs.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.omnidocs.app.ai.AiOutputProcessor
import com.omnidocs.app.ai.LlamaCppService
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelInfo
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.ai.PromptBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VoiceCaptureManager"

@Singleton
class VoiceCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llamaCppService: LlamaCppService,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences
) {
    private var recognizer: SpeechRecognizer? = null

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private suspend fun getActiveModel(): ModelInfo? {
        val selectedId = modelPreferences.selectedModelId.first()
        val downloaded = modelDownloadManager.getDownloadedModels()
        return downloaded.find { it.id == selectedId && it.isDownloaded }
            ?: downloaded.firstOrNull { it.isDownloaded }
    }

    fun startListening(languageCode: String = "en") {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _error.value = "Speech recognition not available on this device"
            return
        }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                    _error.value = null
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    _error.value = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Try again."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout. Try again."
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
                        SpeechRecognizer.ERROR_CLIENT -> "Client error. Try again."
                        SpeechRecognizer.ERROR_NETWORK -> "Network error."
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout."
                        else -> "Recognition error ($error)"
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val fullText = matches?.firstOrNull() ?: ""
                    if (fullText.isNotBlank()) {
                        _transcript.value = if (_transcript.value.isEmpty()) {
                            fullText
                        } else {
                            "${_transcript.value} $fullText"
                        }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull() ?: ""
                    if (partial.isNotBlank()) {
                        _transcript.value = partial
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer?.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
        _isListening.value = false
    }

    fun clearTranscript() {
        _transcript.value = ""
        _error.value = null
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Send the raw transcript to the LLM for structuring into formatted HTML.
     * Returns structured HTML with headings, bullets, and action items.
     */
    suspend fun structureTranscript(rawText: String, language: String): String = withContext(Dispatchers.IO) {
        val model = getActiveModel() ?: return@withContext "<p>$rawText</p>"
        val langInstruction = if (language != "en") "\nRespond in $language language." else ""

        val systemPrompt = "You are a note structuring assistant. Convert raw speech transcript into " +
            "well-structured HTML notes. Add headings (h2), bullet lists (ul/li), action items with " +
            "checkboxes (input type=checkbox), and bold key terms (strong). Preserve all meaning. " +
            "Return only HTML, no markdown code blocks.$langInstruction"

        val userPrompt = "Structure this speech transcript into a well-organized HTML note:\n\n$rawText"

        val prompt = PromptBuilder.buildPrompt(model.promptFormat, systemPrompt, userPrompt)
        val result = llamaCppService.generate(prompt, maxTokens = 1500)
        val processed = result?.let { AiOutputProcessor.process(it) }

        // If LLM failed or returned empty, return the raw text as a paragraph
        if (processed.isNullOrBlank()) {
            "<p>${rawText.replace("\n", "<br/>")}</p>"
        } else {
            // Strip any markdown code block wrappers the LLM might have added
            processed.replace(Regex("```html\\s*"), "").replace(Regex("```\\s*"), "").trim()
        }
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/voice/VoiceCaptureManager.kt
git commit -m "feat: add VoiceCaptureManager with SpeechRecognizer and LLM structuring"
```

---

### Task 6: VoiceCaptureViewModel

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/ui/screens/voice/VoiceCaptureViewModel.kt`

**Interfaces:**
- Consumes: `VoiceCaptureManager`, `NotesRepository.createNote()`
- Produces: `VoiceCaptureViewModel.startListening()`, `.stopListening()`, `.confirmAndSave()`, `.dismiss()`, state flows

- [ ] **Step 1: Create VoiceCaptureViewModel.kt**

```kotlin
package com.omnidocs.app.ui.screens.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.voice.VoiceCaptureManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VoiceCaptureViewModel @Inject constructor(
    private val voiceCaptureManager: VoiceCaptureManager,
    private val repository: NotesRepository
) : ViewModel() {

    val transcript: StateFlow<String> = voiceCaptureManager.transcript
    val isListening: StateFlow<Boolean> = voiceCaptureManager.isListening
    val error: StateFlow<String?> = voiceCaptureManager.error

    private val _isStructuring = MutableStateFlow(false)
    val isStructuring: StateFlow<Boolean> = _isStructuring.asStateFlow()

    private val _structuredContent = MutableStateFlow<String?>(null)
    val structuredContent: StateFlow<String?> = _structuredContent.asStateFlow()

    private val _savedNoteId = MutableStateFlow<String?>(null)
    val savedNoteId: StateFlow<String?> = _savedNoteId.asStateFlow()

    fun startListening(languageCode: String = "en") {
        voiceCaptureManager.clearTranscript()
        voiceCaptureManager.startListening(languageCode)
    }

    fun stopListening() {
        voiceCaptureManager.stopListening()
    }

    fun confirmAndSave(language: String = "en") {
        val rawText = transcript.value
        if (rawText.isBlank()) return

        _isStructuring.value = true
        viewModelScope.launch {
            try {
                val structured = voiceCaptureManager.structureTranscript(rawText, language)
                _structuredContent.value = structured

                // Auto-generate title from first sentence
                val title = rawText.split(Regex("[.!?]"))
                    .firstOrNull { it.trim().isNotEmpty() }
                    ?.trim()
                    ?.take(80)
                    ?: "Voice Note"

                val note = repository.createNote(
                    title = title,
                    content = structured,
                    plainText = rawText,
                    language = language
                )
                _savedNoteId.value = note.id
            } catch (e: Exception) {
                // If structuring fails, save raw text
                val title = rawText.split(Regex("[.!?]"))
                    .firstOrNull { it.trim().isNotEmpty() }
                    ?.trim()
                    ?.take(80)
                    ?: "Voice Note"

                val note = repository.createNote(
                    title = title,
                    content = "<p>${rawText.replace("\n", "<br/>")}</p>",
                    plainText = rawText,
                    language = language
                )
                _savedNoteId.value = note.id
            } finally {
                _isStructuring.value = false
            }
        }
    }

    fun dismiss() {
        voiceCaptureManager.clearTranscript()
        _structuredContent.value = null
        _savedNoteId.value = null
    }

    override fun onCleared() {
        super.onCleared()
        voiceCaptureManager.destroy()
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/voice/VoiceCaptureViewModel.kt
git commit -m "feat: add VoiceCaptureViewModel with recording, structuring, and save flow"
```

---

### Task 7: VoiceCaptureOverlay UI

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/ui/screens/voice/VoiceCaptureOverlay.kt`

**Interfaces:**
- Consumes: `VoiceCaptureViewModel` state flows
- Produces: `VoiceCaptureOverlay` composable

- [ ] **Step 1: Create VoiceCaptureOverlay.kt**

```kotlin
package com.omnidocs.app.ui.screens.voice

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.omnidocs.app.ui.theme.isReducedMotionEnabled

@Composable
fun VoiceCaptureOverlay(
    onNoteCreated: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: VoiceCaptureViewModel = hiltViewModel()
) {
    val transcript by viewModel.transcript.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val error by viewModel.error.collectAsState()
    val isStructuring by viewModel.isStructuring.collectAsState()
    val savedNoteId by viewModel.savedNoteId.collectAsState()
    val reducedMotion = isReducedMotionEnabled()

    // Navigate when note is saved
    LaunchedEffect(savedNoteId) {
        savedNoteId?.let {
            onNoteCreated(it)
            viewModel.dismiss()
        }
    }

    // Pulsing animation for recording indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Close button (top-right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = {
                    viewModel.dismiss()
                    onDismiss()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Status text
            Text(
                text = when {
                    isStructuring -> "Structuring your note..."
                    isListening -> "Listening..."
                    transcript.isEmpty() -> "Tap to start recording"
                    else -> "Review your transcript"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Recording button
            Box(contentAlignment = Alignment.Center) {
                if (isListening && !reducedMotion) {
                    // Pulsing ring
                    Surface(
                        modifier = Modifier
                            .size(96.dp)
                            .scale(pulseScale),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                    ) {}
                }
                FilledIconButton(
                    onClick = {
                        if (isListening) {
                            viewModel.stopListening()
                        } else if (transcript.isEmpty()) {
                            viewModel.startListening()
                        }
                    },
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isListening)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    ),
                    enabled = !isStructuring
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop
                            else Icons.Default.Mic,
                        contentDescription = if (isListening) "Stop recording" else "Start recording",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Transcript area
            if (transcript.isNotBlank() || error != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (error != null) {
                        Text(
                            text = error!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = transcript,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(16.dp),
                            semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                }
            } else {
                // Empty state
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.MicOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Tap the microphone to start",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons
            if (transcript.isNotBlank() && !isListening && !isStructuring) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.startListening() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Record More")
                    }
                    Button(
                        onClick = { viewModel.confirmAndSave() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Note")
                    }
                }
            }

            if (isStructuring) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                )
            }
        }
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/voice/VoiceCaptureOverlay.kt
git commit -m "feat: add VoiceCaptureOverlay with pulsing mic, transcript display, and save flow"
```

---

### Task 8: HomeScreen Voice FAB + Navigation

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/omnidocs/app/ui/navigation/Navigation.kt`

**Interfaces:**
- Consumes: `VoiceCaptureOverlay` composable
- Produces: Voice FAB on home screen, Voice route in navigation

- [ ] **Step 1: Add voice FAB to HomeScreen**

Update the `HomeScreen` signature to add:
```kotlin
fun HomeScreen(
    onNoteClick: (String) -> Unit,
    onNewNote: () -> Unit,
    onSettingsClick: () -> Unit,
    onFeedClick: () -> Unit = {},
    onVoiceCapture: () -> Unit = {}, // NEW
    viewModel: HomeViewModel = hiltViewModel()
)
```

Replace the `floatingActionButton` block in the Scaffold with:
```kotlin
floatingActionButton = {
    if (!isSelectionMode) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Voice capture FAB (smaller, above main FAB)
            SmallFloatingActionButton(
                onClick = onVoiceCapture,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Voice capture"
                )
            }
            // New note FAB (main)
            FloatingActionButton(
                onClick = onNewNote,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New note"
                )
            }
        }
    }
}
```

- [ ] **Step 2: Add Voice route to Navigation**

Add to the `Screen` sealed class:
```kotlin
object Voice : Screen("voice")
```

Add the composable route in `NotesNavHost` (after the Home composable):
```kotlin
composable(
    route = Screen.Voice.route,
    enterTransition = { slideInVertically(initialOffsetY = { it }) + fadeIn() },
    exitTransition = { slideOutVertically(targetOffsetY = { it }) + fadeOut() },
    popEnterTransition = { slideInVertically(initialOffsetY = { it }) + fadeIn() },
    popExitTransition = { slideOutVertically(targetOffsetY = { it }) + fadeOut() }
) {
    VoiceCaptureOverlay(
        onNoteCreated = { noteId ->
            navController.popBackStack()
            navController.navigate(Screen.Editor.createRoute(noteId))
        },
        onDismiss = { navController.popBackStack() }
    )
}
```

Update the HomeScreen call to pass `onVoiceCapture`:
```kotlin
HomeScreen(
    onNoteClick = { noteId -> navController.navigate(Screen.Editor.createRoute(noteId)) },
    onNewNote = { navController.navigate(Screen.Editor.createRoute()) },
    onSettingsClick = { navController.navigate(Screen.Settings.route) },
    onFeedClick = { navController.navigate(Screen.Feed.route) },
    onVoiceCapture = { navController.navigate(Screen.Voice.route) }
)
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/home/HomeScreen.kt app/src/main/java/com/omnidocs/app/ui/navigation/Navigation.kt
git commit -m "feat: add voice capture FAB to HomeScreen and Voice route to navigation"
```

---

## Phase 3: Knowledge Graph

### Task 9: GraphData + DB Migration

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/graph/GraphData.kt`
- Modify: `app/src/main/java/com/omnidocs/app/data/local/entity/NoteEntity.kt`
- Modify: `app/src/main/java/com/omnidocs/app/data/local/NotesDatabase.kt`
- Modify: `app/src/main/java/com/omnidocs/app/domain/model/Note.kt`
- Modify: `app/src/main/java/com/omnidocs/app/data/repository/NotesRepository.kt`

**Interfaces:**
- Consumes: Nothing (data layer only)
- Produces: `GraphNode`, `GraphEdge`, `GraphData`, `NoteEntity.relatedNotes`, `Note.relatedNotes`

- [ ] **Step 1: Create GraphData.kt**

```kotlin
package com.omnidocs.app.graph

data class GraphNode(
    val noteId: String,
    val title: String,
    val wordCount: Int = 0,
    val tags: List<String> = emptyList()
)

data class GraphEdge(
    val from: String,
    val to: String,
    val label: String,
    val strength: Float
)

data class GraphData(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>
)
```

- [ ] **Step 2: Add relatedNotes to NoteEntity**

Add to NoteEntity data class:
```kotlin
val relatedNotes: String = "[]" // JSON array of related note connections
```

- [ ] **Step 3: Add relatedNotes to domain Note**

Add to Note data class:
```kotlin
val relatedNotes: String = "[]"
```

- [ ] **Step 4: Update NoteEntity.toDomain() and fromDomain()**

Find the `toDomain()` extension function in NoteEntity.kt (or wherever it's defined) and add:
```kotlin
relatedNotes = relatedNotes
```

If there's no explicit mapping, check the `NoteEntity` constructor -- since both use the same field name, it should map automatically if using copy(). If there's a manual mapping, add the field.

- [ ] **Step 5: Add DB migration 5->6**

In `NotesDatabase.kt`:
1. Change `version = 5` to `version = 6`
2. Add migration:
```kotlin
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notes ADD COLUMN relatedNotes TEXT NOT NULL DEFAULT '[]'")
    }
}
```
3. Add to `addMigrations`:
```kotlin
.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
```

- [ ] **Step 6: Verify build compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/graph/GraphData.kt app/src/main/java/com/omnidocs/app/data/local/entity/NoteEntity.kt app/src/main/java/com/omnidocs/app/data/local/NotesDatabase.kt app/src/main/java/com/omnidocs/app/domain/model/Note.kt app/src/main/java/com/omnidocs/app/data/repository/NotesRepository.kt
git commit -m "feat: add GraphData models and DB migration 5->6 for relatedNotes"
```

---

### Task 10: GraphEngine

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/graph/GraphEngine.kt`

**Interfaces:**
- Consumes: `LlamaCppService.generate()`, `PromptBuilder.buildPrompt()`, `AiOutputProcessor.process()`, `NotesRepository` (getAllNotesSync, updateNote)
- Produces: `GraphEngine.buildGraph()`, `GraphEngine.rebuildGraph()`

- [ ] **Step 1: Create GraphEngine.kt**

```kotlin
package com.omnidocs.app.graph

import android.util.Log
import com.omnidocs.app.ai.AiOutputProcessor
import com.omnidocs.app.ai.LlamaCppService
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.ai.PromptBuilder
import com.omnidocs.app.data.repository.NotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GraphEngine"

@Singleton
class GraphEngine @Inject constructor(
    private val llamaCppService: LlamaCppService,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val repository: NotesRepository
) {
    private suspend fun getActiveModel() = run {
        val selectedId = modelPreferences.selectedModelId.first()
        val downloaded = modelDownloadManager.getDownloadedModels()
        downloaded.find { it.id == selectedId && it.isDownloaded }
            ?: downloaded.firstOrNull { it.isDownloaded }
    }

    /**
     * Build a GraphData from all notes. Uses word overlap to find candidate
     * neighbors, then asks the LLM to confirm and label relationships.
     */
    suspend fun buildGraph(): GraphData = withContext(Dispatchers.IO) {
        val notes = repository.getAllNotesSync()
        if (notes.size < 2) {
            return@withContext GraphData(
                nodes = notes.map { GraphNode(it.id, it.title, it.plainText.split("\\s+".toRegex()).size) },
                edges = emptyList()
            )
        }

        val model = getActiveModel()
        val nodes = notes.map { note ->
            val wordCount = note.plainText.split("\\s+".toRegex()).size
            val tags = try {
                org.json.JSONArray(note.tags).let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
            } catch (_: Exception) { emptyList() }
            GraphNode(note.id, note.title, wordCount, tags)
        }

        val edges = mutableListOf<GraphEdge>()

        if (model != null) {
            // Find candidate pairs by word overlap
            val candidates = findCandidatePairs(notes, maxPairs = minOf(notes.size * 2, 50))

            for ((noteA, noteB) in candidates) {
                val connections = analyzePair(model.promptFormat, noteA.plainText, noteA.title, noteB.plainText, noteB.title)
                for (conn in connections) {
                    val targetId = if (conn["targetTitle"] == noteB.title) noteB.id else noteA.id
                    edges.add(GraphEdge(
                        from = noteA.id,
                        to = targetId,
                        label = conn["label"] ?: "related to",
                        strength = (conn["strength"] as? Number)?.toFloat() ?: 0.5f
                    ))
                }
            }
        }

        // Store relationships back to notes
        storeRelationships(notes, edges)

        GraphData(nodes = nodes, edges = edges)
    }

    /**
     * Find candidate pairs by word overlap (cheap pre-filter before LLM).
     */
    private fun findCandidatePairs(
        notes: List<com.omnidocs.app.data.local.entity.NoteEntity>,
        maxPairs: Int
    ): List<Pair<com.omnidocs.app.data.local.entity.NoteEntity, com.omnidocs.app.data.local.entity.NoteEntity>> {
        val stopWords = setOf("the", "a", "an", "is", "are", "was", "were", "be", "been",
            "being", "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "shall", "can", "to", "of", "in", "for", "on", "with",
            "at", "by", "from", "as", "into", "through", "during", "before", "after", "and",
            "but", "or", "nor", "not", "so", "yet", "both", "either", "neither", "each",
            "every", "all", "any", "few", "more", "most", "other", "some", "such", "no",
            "only", "own", "same", "than", "too", "very", "just", "that", "this", "these",
            "those", "i", "me", "my", "we", "our", "you", "your", "he", "him", "his",
            "she", "her", "it", "its", "they", "them", "their", "what", "which", "who",
            "whom", "when", "where", "why", "how", "if", "then", "else", "because")

        fun getWords(text: String): Set<String> {
            return text.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), "")
                .split("\\s+".toRegex())
                .filter { it.length > 2 && it !in stopWords }
                .toSet()
        }

        val noteWords = notes.map { it.id to getWords(it.plainText) }.toMap()
        val pairs = mutableListOf<Triple<String, String, Float>>()

        for (i in notes.indices) {
            for (j in i + 1 until notes.size) {
                val wordsA = noteWords[notes[i].id] ?: emptySet()
                val wordsB = noteWords[notes[j].id] ?: emptySet()
                if (wordsA.isEmpty() || wordsB.isEmpty()) continue
                val overlap = wordsA.intersect(wordsB).size.toFloat() /
                    minOf(wordsA.size, wordsB.size).coerceAtLeast(1)
                if (overlap > 0.1f) {
                    pairs.add(Triple(notes[i].id, notes[j].id, overlap))
                }
            }
        }

        return pairs.sortedByDescending { it.th }
            .take(maxPairs)
            .map { (idA, idB, _) ->
                notes.first { it.id == idA } to notes.first { it.id == idB }
            }
    }

    /**
     * Ask the LLM to find connections between two notes.
     */
    private suspend fun analyzePair(
        format: com.omnidocs.app.ai.PromptFormat,
        textA: String, titleA: String,
        textB: String, titleB: String
    ): List<Map<String, Any?>> {
        val systemPrompt = "You are a knowledge graph builder. Analyze whether two notes are related. " +
            "If they are, return a JSON array of connections. Each connection has: " +
            "\"label\" (one of: 'related to', 'references', 'supports', 'contradicts', 'builds on'), " +
            "\"strength\" (0.3-1.0), and \"targetTitle\" (the title of the related note). " +
            "If no meaningful connection exists, return an empty array []. " +
            "Return ONLY the JSON array, no other text."

        val userPrompt = "Note A: \"$titleA\"\n${textA.take(1500)}\n\n" +
            "Note B: \"$titleB\"\n${textB.take(1500)}\n\n" +
            "Are these notes related? Return JSON array."

        val prompt = PromptBuilder.buildPrompt(format, systemPrompt, userPrompt)
        val result = llamaCppService.generate(prompt, maxTokens = 500) ?: return emptyList()
        val cleaned = AiOutputProcessor.process(result)

        return parseConnections(cleaned)
    }

    private fun parseConnections(json: String): List<Map<String, Any?>> {
        return try {
            val jsonStr = json.replace(Regex("```json\\s*"), "").replace(Regex("```\\s*"), "").trim()
            val arr = JSONArray(jsonStr)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                mapOf(
                    "label" to obj.optString("label", "related to"),
                    "strength" to obj.optDouble("strength", 0.5),
                    "targetTitle" to obj.optString("targetTitle", "")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun storeRelationships(
        notes: List<com.omnidocs.app.data.local.entity.NoteEntity>,
        edges: List<GraphEdge>
    ) {
        val notesById = notes.associateBy { it.id }
        for (note in notes) {
            val related = edges.filter { it.from == note.id || it.to == note.id }
                .map { edge ->
                    val targetId = if (edge.from == note.id) edge.to else edge.from
                    val targetTitle = notesById[targetId]?.title ?: ""
                    JSONObject().apply {
                        put("noteId", targetId)
                        put("title", targetTitle)
                        put("label", edge.label)
                        put("strength", edge.strength)
                    }
                }
            if (related.isNotEmpty()) {
                repository.updateNote(note.copy(relatedNotes = JSONArray(related).toString()))
            }
        }
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/graph/GraphEngine.kt
git commit -m "feat: add GraphEngine with word-overlap candidate filtering and LLM relationship analysis"
```

---

### Task 11: graph.html (D3.js Force Graph)

**Files:**
- Create: `app/src/main/assets/graph.html`

**Interfaces:**
- Consumes: Graph data passed via `loadGraph(jsonString)` JS function, theme colors via `setGraphTheme(jsonString)`
- Produces: Interactive force-directed graph visualization

- [ ] **Step 1: Create graph.html**

The full HTML file with inline D3.js is large. Create `app/src/main/assets/graph.html` with:

- D3.js v7 minified bundle (from CDN or inline -- use inline for offline support)
- Force simulation: charge (-300), link distance (100), center gravity (0.05)
- Node rendering: circles with glassmorphism (backdrop-filter: blur), sized by word count (min 20, max 50), colored by tag or default primary color
- Edge rendering: curved paths with arrowheads, labeled with relationship text
- Interactions: drag nodes, zoom/pan, click node -> `Android.onNodeTapped(noteId)`
- Hover: highlight connected edges, show tooltip with title + word count
- Theme: `setGraphTheme(colors)` function receives JSON with primary, surface, onSurface colors
- Animation: nodes pulse in on load, edges fade in with stagger
- Empty state: centered text "Add more notes to see connections"

Key structure:
```html
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { overflow: hidden; background: #1a1a1a; font-family: -apple-system, sans-serif; }
        svg { width: 100vw; height: 100vh; }
        .node-circle { cursor: pointer; transition: filter 0.2s; }
        .node-circle:hover { filter: brightness(1.2) drop-shadow(0 0 8px rgba(255,255,255,0.3)); }
        .node-label { fill: #e0e0e0; font-size: 11px; pointer-events: none; text-anchor: middle; }
        .edge-path { fill: none; stroke-opacity: 0.4; }
        .edge-label { font-size: 9px; fill: #888; pointer-events: none; }
        .tooltip { position: absolute; background: rgba(30,30,30,0.9); color: #fff;
                   padding: 8px 12px; border-radius: 8px; font-size: 12px;
                   pointer-events: none; backdrop-filter: blur(4px); display: none; }
        .empty-state { position: absolute; top: 50%; left: 50%; transform: translate(-50%,-50%);
                       text-align: center; color: #666; }
    </style>
</head>
<body>
    <div id="graph-container"></div>
    <div class="tooltip" id="tooltip"></div>
    <div class="empty-state" id="empty-state" style="display:none;">
        <p style="font-size:18px;margin-bottom:8px;">No connections yet</p>
        <p style="font-size:14px;">Add more notes to see how they connect</p>
    </div>

    <script src="https://d3js.org/d3.v7.min.js"></script>
    <script>
        // ... force simulation, rendering, interaction code ...
        // (see implementation details below)
    </script>
</body>
</html>
```

**IMPORTANT: Bundle D3.js inline** -- do NOT use a CDN script tag. This is an on-device app that must work offline. Download D3.js v7 minified from `https://d3js.org/d3.v7.min.js` and paste the entire contents directly into the HTML file inside a `<script>` tag. No `src` attribute, no `integrity` attribute needed since it's inline.

Key JS functions to implement:
- `loadGraph(jsonString)` -- receives `{nodes: [...], edges: [...]}`, renders the graph
- `setGraphTheme(colors)` -- updates CSS variables for node/edge colors
- `Android.onNodeTapped(noteId)` -- called when user taps a node (JS interface)
- Force simulation with `d3.forceSimulation(nodes).force("link", d3.forceLink(edges)).force("charge", d3.forceManyBody().strength(-300)).force("center", d3.forceCenter(w/2, h/2))`

- [ ] **Step 2: Verify file exists**

Run: `ls -la app/src/main/assets/graph.html`
Expected: file exists, size > 50KB (D3.js bundle)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/assets/graph.html
git commit -m "feat: add graph.html with D3.js force-directed graph visualization"
```

---

### Task 12: GraphViewModel + GraphScreen

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/ui/screens/graph/GraphViewModel.kt`
- Create: `app/src/main/java/com/omnidocs/app/ui/screens/graph/GraphScreen.kt`

**Interfaces:**
- Consumes: `GraphEngine.buildGraph()`, `GraphData`
- Produces: `GraphViewModel.buildGraph()`, `GraphViewModel.rebuildGraph()`, `GraphScreen` composable

- [ ] **Step 1: Create GraphViewModel.kt**

```kotlin
package com.omnidocs.app.ui.screens.graph

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.graph.GraphData
import com.omnidocs.app.graph.GraphEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GraphViewModel @Inject constructor(
    private val graphEngine: GraphEngine
) : ViewModel() {

    private val _graphData = MutableStateFlow<GraphData?>(null)
    val graphData: StateFlow<GraphData?> = _graphData.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        buildGraph()
    }

    fun buildGraph() {
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val data = graphEngine.buildGraph()
                _graphData.value = data
                if (data.nodes.isEmpty()) {
                    _error.value = "No notes found"
                } else if (data.edges.isEmpty()) {
                    _error.value = "No connections found between notes"
                }
            } catch (e: Exception) {
                _error.value = "Failed to build graph: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun rebuildGraph() {
        buildGraph()
    }
}
```

- [ ] **Step 2: Create GraphScreen.kt**

```kotlin
package com.omnidocs.app.ui.screens.graph

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.omnidocs.app.ui.navigation.Screen
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(
    onNavigateBack: () -> Unit,
    onNodeTap: (String) -> Unit,
    navController: NavController,
    viewModel: GraphViewModel = hiltViewModel()
) {
    val graphData by viewModel.graphData.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var webView by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    // Push graph data to WebView when it changes
    LaunchedEffect(graphData) {
        graphData?.let { data ->
            val json = JSONObject().apply {
                put("nodes", org.json.JSONArray().apply {
                    data.nodes.forEach { node ->
                        put(org.json.JSONObject().apply {
                            put("id", node.noteId)
                            put("title", node.title)
                            put("wordCount", node.wordCount)
                        })
                    }
                })
                put("edges", org.json.JSONArray().apply {
                    data.edges.forEach { edge ->
                        put(org.json.JSONObject().apply {
                            put("source", edge.from)
                            put("target", edge.to)
                            put("label", edge.label)
                            put("strength", edge.strength)
                        })
                    }
                })
            }
            webView?.evaluateJavascript("loadGraph('${json.toString().replace("'", "\\'")}')", null)
        }
    }

    // Push theme colors
    LaunchedEffect(webView, colorScheme) {
        webView?.let { wv ->
            val themeJson = JSONObject().apply {
                put("primary", "#%06X".format(colorScheme.primary.hashCode() and 0xFFFFFF))
                put("surface", "#%06X".format(colorScheme.surface.hashCode() and 0xFFFFFF))
                put("onSurface", "#%06X".format(colorScheme.onSurface.hashCode() and 0xFFFFFF))
                put("primaryContainer", "#%06X".format(colorScheme.primaryContainer.hashCode() and 0xFFFFFF))
            }
            wv.evaluateJavascript("setGraphTheme($themeJson)", null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Knowledge Graph") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.rebuildGraph() }) {
                        Icon(Icons.Default.Refresh, "Rebuild Graph")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onNodeTapped(noteId: String) {
                                onNodeTap(noteId)
                            }
                        }, "Android")

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                // Push data after page loads
                                graphData?.let { data ->
                                    val json = JSONObject().apply {
                                        put("nodes", org.json.JSONArray().apply {
                                            data.nodes.forEach { node ->
                                                put(org.json.JSONObject().apply {
                                                    put("id", node.noteId)
                                                    put("title", node.title)
                                                    put("wordCount", node.wordCount)
                                                })
                                            }
                                        })
                                        put("edges", org.json.JSONArray().apply {
                                            data.edges.forEach { edge ->
                                                put(org.json.JSONObject().apply {
                                                    put("source", edge.from)
                                                    put("target", edge.to)
                                                    put("label", edge.label)
                                                    put("strength", edge.strength)
                                                })
                                            }
                                        })
                                    }
                                    view?.evaluateJavascript("loadGraph('${json.toString().replace("'", "\\'")}')", null)
                                }
                            }
                        }

                        loadUrl("file:///android_asset/graph.html")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            error?.let { msg ->
                if (graphData?.edges?.isEmpty() == true) {
                    // Show empty state overlay
                    Surface(
                        modifier = Modifier.align(Alignment.Center),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/graph/GraphViewModel.kt app/src/main/java/com/omnidocs/app/ui/screens/graph/GraphScreen.kt
git commit -m "feat: add GraphViewModel and GraphScreen with WebView D3.js integration"
```

---

### Task 13: Navigation + HomeScreen Graph Button

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/navigation/Navigation.kt`
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/home/HomeScreen.kt`

**Interfaces:**
- Consumes: `GraphScreen` composable
- Produces: Graph route in navigation, graph icon in HomeScreen TopAppBar

- [ ] **Step 1: Add Graph route to Navigation**

Add to the `Screen` sealed class:
```kotlin
object Graph : Screen("graph")
```

Add the composable route in `NotesNavHost` (after the Voice composable):
```kotlin
composable(
    route = Screen.Graph.route,
    enterTransition = { screenEnterTransition() },
    exitTransition = { screenExitTransition() },
    popEnterTransition = { screenPopEnterTransition() },
    popExitTransition = { screenPopExitTransition() }
) {
    GraphScreen(
        onNavigateBack = { navController.popBackStack() },
        onNodeTap = { noteId -> navController.navigate(Screen.Editor.createRoute(noteId)) },
        navController = navController
    )
}
```

- [ ] **Step 2: Add graph button to HomeScreen TopAppBar**

Update the `HomeScreen` signature:
```kotlin
fun HomeScreen(
    onNoteClick: (String) -> Unit,
    onNewNote: () -> Unit,
    onSettingsClick: () -> Unit,
    onFeedClick: () -> Unit = {},
    onVoiceCapture: () -> Unit = {},
    onGraphClick: () -> Unit = {}, // NEW
    viewModel: HomeViewModel = hiltViewModel()
)
```

Add a graph icon button in the `TopAppBar` `actions` block, before the search icon:
```kotlin
IconButton(onClick = onGraphClick) {
    Icon(
        imageVector = Icons.Default.AccountTree,
        contentDescription = "Knowledge Graph"
    )
}
```

Update the HomeScreen call in Navigation.kt:
```kotlin
HomeScreen(
    onNoteClick = { noteId -> navController.navigate(Screen.Editor.createRoute(noteId)) },
    onNewNote = { navController.navigate(Screen.Editor.createRoute()) },
    onSettingsClick = { navController.navigate(Screen.Settings.route) },
    onFeedClick = { navController.navigate(Screen.Feed.route) },
    onVoiceCapture = { navController.navigate(Screen.Voice.route) },
    onGraphClick = { navController.navigate(Screen.Graph.route) }
)
```

Note: `Icons.Default.AccountTree` may not exist in the extended icons. If not, use `Icons.Default.AccountTree` from `material-icons-extended` or fall back to `Icons.Default.Schema` or `Icons.Default.Lan`.

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/navigation/Navigation.kt app/src/main/java/com/omnidocs/app/ui/screens/home/HomeScreen.kt
git commit -m "feat: add Graph route and graph button to HomeScreen TopAppBar"
```

---

### Task 14: Final Build + Deploy

**Files:** None (verification only)

- [ ] **Step 1: Full clean build**

Run: `./gradlew clean :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Deploy to device**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: Success

- [ ] **Step 3: Test all three features**

1. **Note Intelligence:** Open a note with content -> tap the AI sparkles button in toolbar -> type a question -> verify answer appears in panel
2. **Smart Explain:** Select text in editor -> verify "Explain selected text" chip appears -> tap it -> verify explanation in panel
3. **Concept Extract:** Tap the lightbulb icon in toolbar -> verify concepts dialog appears with "Create Note" buttons
4. **Voice Capture:** Tap mic FAB on home screen -> speak -> verify transcript appears -> tap "Save Note" -> verify note created
5. **Knowledge Graph:** Tap graph icon in home screen TopAppBar -> verify graph renders with nodes and edges -> tap a node -> verify it opens the note

- [ ] **Step 4: Final commit (if any fixes needed)**

```bash
git add -A
git commit -m "fix: final adjustments for Thinking Tools features"
```
