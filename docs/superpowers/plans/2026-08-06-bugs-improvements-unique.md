# OmniDocs Bug Fixes, Improvements, and Unique Features

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix existing bugs, improve UI/UX quality, and add unique differentiating features to make OmniDocs a truly distinctive note-taking app.

**Architecture:** Three-phase approach: (1) Fix bugs and remove dead code, (2) Improve UI/UX across all screens, (3) Add unique features that leverage on-device AI.

**Tech Stack:** Kotlin, Jetpack Compose M3, Room/SQLCipher, Hilt, llama.cpp JNI, ML Kit OCR, WebView rich text editor

## Global Constraints

- Target device: Xiaomi 13 Ultra (arm64-v8a only)
- Min SDK: 26, Target SDK: 34
- NDK: 27.0.12077973
- Compose BOM: 2024.02.00
- Hilt: 2.51.1
- Room: 2.6.1
- All changes must pass `./gradlew :app:assembleDebug`
- No sycophantic openers or closing fluff in code comments
- No emojis or em-dashes in code

---

## Phase 1: Bug Fixes and Dead Code Removal

### Task 1: Remove dead PaddleOcrService

**Files:**
- Delete: `app/src/main/java/com/omnidocs/app/ocr/PaddleOcrService.kt`

**Interfaces:**
- Consumes: Nothing
- Produces: Nothing (removal only)

- [ ] **Step 1: Delete the dead file**

```bash
rm app/src/main/java/com/omnidocs/app/ocr/PaddleOcrService.kt
```

- [ ] **Step 2: Verify no references exist**

Run: `grep -r "PaddleOcrService" app/src/main/java/`
Expected: No results

- [ ] **Step 3: Build and verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Remove old Paddle OCR model assets**

```bash
rm -rf app/src/main/assets/models/ocr/
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: remove dead PaddleOcrService and old model assets after ML Kit migration"
```

---

### Task 2: Clean up OcrScreen unused imports

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt:1-20`

**Interfaces:**
- Consumes: Nothing
- Produces: Nothing (cleanup only)

- [ ] **Step 1: Remove unused imports**

Remove these imports that are no longer needed after the MediaImage migration:
```kotlin
// REMOVE these lines:
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
```

- [ ] **Step 2: Build and verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt
git commit -m "chore: remove unused imports from OcrScreen after MediaImage migration"
```

---

### Task 3: Fix language menu to show all supported languages

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt:461-484`

**Interfaces:**
- Consumes: `OcrEngineFactory.getSupportedLanguages()` for language list
- Produces: Updated language selection dialog

- [ ] **Step 1: Replace hardcoded language dialog**

Replace the hardcoded English/Bahasa Melayu dialog (lines 461-484) with a dynamic list:

```kotlin
// Language Menu
if (showLanguageMenu) {
    val languages = mapOf(
        "en" to "English",
        "ms" to "Bahasa Melayu",
        "zh" to "Chinese",
        "ja" to "Japanese",
        "ko" to "Korean",
        "ar" to "Arabic",
        "hi" to "Hindi",
        "ru" to "Russian",
        "fr" to "French",
        "de" to "German",
        "es" to "Spanish",
        "pt" to "Portuguese",
        "it" to "Italian",
        "nl" to "Dutch",
        "tr" to "Turkish",
        "vi" to "Vietnamese",
        "th" to "Thai",
        "id" to "Indonesian"
    )
    AlertDialog(
        onDismissRequest = { showLanguageMenu = false },
        title = { Text("Note Language") },
        text = {
            LazyColumn {
                items(languages.entries.toList()) { (code, name) ->
                    ListItem(
                        headlineContent = { Text(name) },
                        leadingContent = {
                            if (viewModel.currentLanguage.value == code) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        modifier = Modifier.clickable {
                            viewModel.setLanguage(code)
                            showLanguageMenu = false
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showLanguageMenu = false }) {
                Text("Cancel")
            }
        }
    )
}
```

- [ ] **Step 2: Add missing import**

Add to the imports at the top of EditorScreen.kt:
```kotlin
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Check
```

- [ ] **Step 3: Build and verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt
git commit -m "feat: replace hardcoded language menu with full language list"
```

---

### Task 4: Add empty state to HomeScreen

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/home/HomeScreen.kt`

**Interfaces:**
- Consumes: `notes` StateFlow, `searchQuery` StateFlow
- Produces: Empty state UI when no notes exist

- [ ] **Step 1: Add empty state composable**

After the LazyColumn/Grid, add an empty state when notes list is empty and not loading:

```kotlin
// After the main list/grid content, add:
if (!isInitialLoading && notes.isEmpty() && searchQuery.isEmpty()) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.NoteAdd,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
            Text(
                text = "No notes yet",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Tap the + button to create your first note,\nor import a document to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
```

- [ ] **Step 2: Add search empty state**

When search returns no results:

```kotlin
if (!isInitialLoading && notes.isEmpty() && searchQuery.isNotEmpty()) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                text = "No results for \"$searchQuery\"",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

- [ ] **Step 3: Add missing import**

```kotlin
import androidx.compose.material.icons.filled.SearchOff
```

- [ ] **Step 4: Build and verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/home/HomeScreen.kt
git commit -m "feat: add empty state and search-not-found state to HomeScreen"
```

---

### Task 5: Add reading time estimate to editor

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorViewModel.kt:31-34`
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt:407-417`

**Interfaces:**
- Consumes: `wordCount` StateFlow
- Produces: `readingTimeMinutes` StateFlow

- [ ] **Step 1: Add reading time to ViewModel**

After the `wordCount` StateFlow definition (line 34), add:

```kotlin
val readingTimeMinutes: StateFlow<Int> = wordCount.map { words ->
    if (words == 0) 0 else maxOf(1, words / 200) // 200 WPM average
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
```

- [ ] **Step 2: Update word count display in EditorScreen**

Replace the word count section (lines 407-417) with:

```kotlin
// Word count and reading time
if (wordCount > 0) {
    val readingTime by viewModel.readingTimeMinutes.collectAsState()
    Text(
        text = "$wordCount words" + if (readingTime > 0) " · ~${readingTime} min read" else "",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    )
}
```

- [ ] **Step 3: Build and verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorViewModel.kt app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt
git commit -m "feat: add reading time estimate to editor word count"
```

---

### Task 2: Clean up debug logging from OCR migration

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ocr/MlKitOcrEngine.kt`
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt`
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrViewModel.kt`

**Interfaces:**
- Consumes: Nothing
- Produces: Cleaner log output (metadata only, no text content)

- [ ] **Step 1: Strip text content from MlKitOcrEngine logs**

Replace verbose logging in `MlKitOcrEngine.kt` with metadata-only logging:

- `recognizeFromMediaImage`: Keep dimension/rotation/block-count logs, remove text content logging
- Remove per-block text logging (`Block: '${block.text.take(50)}...'`)
- Keep error logging as-is

- [ ] **Step 2: Strip verbose logging from OcrScreen.kt**

Remove the `LaunchedEffect` log that only says `isFrozen=true/false` (no useful information).

- [ ] **Step 3: Strip verbose logging from OcrViewModel.kt**

Remove `recognizeFromMediaImage` result text logging (keep only success/failure).

- [ ] **Step 4: Build and verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ocr/MlKitOcrEngine.kt app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrViewModel.kt
git commit -m "chore: strip text content from OCR debug logs, keep metadata only"
```

---

## Phase 2: UI/UX Improvements

### Task 6: Add search bar to HomeScreen

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/home/HomeScreen.kt:164-180`

**Interfaces:**
- Consumes: `searchQuery` StateFlow, `viewModel.searchNotes()`
- Produces: Search bar in TopAppBar

- [ ] **Step 1: Add search bar to TopAppBar**

Replace the static TopAppBar title with a collapsible search bar:

```kotlin
TopAppBar(
    title = {
        if (isSearching) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.searchNotes(it) },
                placeholder = { Text("Search notes...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surface
                )
            )
        } else {
            Column {
                Text("OmniDocs")
                Text(
                    text = "v1.0.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    },
    actions = {
        IconButton(onClick = {
            isSearching = !isSearching
            if (!isSearching) viewModel.searchNotes("")
        }) {
            Icon(
                imageVector = if (isSearching) Icons.Default.Close else Icons.Default.Search,
                contentDescription = if (isSearching) "Close search" else "Search"
            )
        }
        // ... rest of actions
    }
)
```

- [ ] **Step 2: Add state variable**

Add near the top of HomeScreen composable:
```kotlin
var isSearching by remember { mutableStateOf(false) }
```

- [ ] **Step 3: Add missing import**

```kotlin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
```

- [ ] **Step 4: Build and verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/home/HomeScreen.kt
git commit -m "feat: add collapsible search bar to HomeScreen TopAppBar"
```

---

### Task 7: Move word count to TopAppBar

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt:269-430`

**Interfaces:**
- Consumes: `wordCount` StateFlow, `readingTimeMinutes` StateFlow
- Produces: Word count in TopAppBar actions

- [ ] **Step 1: Move word count to TopAppBar actions**

In the `actions` block of the TopAppBar, add before the save indicator:

```kotlin
// Word count + reading time
if (wordCount > 0) {
    val readingTime by viewModel.readingTimeMinutes.collectAsState()
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Text(
            text = "$wordCount" + if (readingTime > 0) " · ${readingTime}m" else "",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
```

- [ ] **Step 2: Remove old word count from Column**

Remove the word count Text element from below the toolbar (lines 407-417).

- [ ] **Step 3: Build and verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt
git commit -m "refactor: move word count to editor TopAppBar to save vertical space"
```

---

## Phase 3: Unique Features

### Task 8: Add smart note templates

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/ui/screens/editor/NoteTemplates.kt`
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt`

**Interfaces:**
- Consumes: `viewModel.updateContent()`, `viewModel.updateTitle()`
- Produces: Template selection UI, pre-filled note content

- [ ] **Step 1: Create NoteTemplates.kt**

```kotlin
package com.omnidocs.app.ui.screens.editor

data class NoteTemplate(
    val id: String,
    val name: String,
    val icon: String,
    val title: String,
    val content: String
)

val noteTemplates = listOf(
    NoteTemplate(
        id = "meeting",
        name = "Meeting Notes",
        icon = "📅",
        title = "Meeting - ",
        content = """<h2>Meeting Notes</h2>
<p><strong>Date:</strong> ${java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date())}</p>
<p><strong>Attendees:</strong> </p>
<hr/>
<h3>Agenda</h3>
<ul><li></li></ul>
<h3>Discussion</h3>
<p></p>
<h3>Action Items</h3>
<ul><li></li></ul>
<h3>Next Steps</h3>
<p></p>"""
    ),
    NoteTemplate(
        id = "research",
        name = "Research Notes",
        icon = "🔬",
        title = "Research: ",
        content = """<h2>Research Notes</h2>
<p><strong>Topic:</strong> </p>
<p><strong>Source:</strong> </p>
<hr/>
<h3>Key Findings</h3>
<ul><li></li></ul>
<h3>Analysis</h3>
<p></p>
<h3>References</h3>
<ul><li></li></ul>"""
    ),
    NoteTemplate(
        id = "journal",
        name = "Daily Journal",
        icon = "📝",
        title = "${java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault()).format(java.util.Date())}",
        content = """<h2>Daily Journal</h2>
<h3>Mood</h3>
<p></p>
<h3>Highlights</h3>
<ul><li></li></ul>
<h3>Challenges</h3>
<ul><li></li></ul>
<h3>Gratitude</h3>
<ul><li></li></ul>
<h3>Tomorrow's Intentions</h3>
<ul><li></li></ul>"""
    ),
    NoteTemplate(
        id = "project",
        name = "Project Plan",
        icon = "🎯",
        title = "Project: ",
        content = """<h2>Project Plan</h2>
<p><strong>Goal:</strong> </p>
<p><strong>Deadline:</strong> </p>
<hr/>
<h3>Tasks</h3>
<ul><li>[ ] </li></ul>
<h3>Resources Needed</h3>
<ul><li></li></ul>
<h3>Risks</h3>
<ul><li></li></ul>
<h3>Progress</h3>
<p></p>"""
    ),
    NoteTemplate(
        id = "blank",
        name = "Blank Note",
        icon = "📄",
        title = "",
        content = ""
    )
)
```

- [ ] **Step 2: Add template selection to EditorScreen**

Add a template selection dialog that appears when creating a new note (when `noteId == null`):

```kotlin
// Template selection dialog for new notes
var showTemplateDialog by remember { mutableStateOf(noteId == null) }

if (showTemplateDialog) {
    AlertDialog(
        onDismissRequest = { showTemplateDialog = false },
        title = { Text("Choose a template") },
        text = {
            LazyColumn {
                items(noteTemplates) { template ->
                    ListItem(
                        headlineContent = { Text(template.name) },
                        leadingContent = { Text(template.icon, style = MaterialTheme.typography.headlineSmall) },
                        modifier = Modifier.clickable {
                            viewModel.updateTitle(template.title)
                            if (template.content.isNotEmpty()) {
                                viewModel.updateContent(template.content)
                            }
                            showTemplateDialog = false
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showTemplateDialog = false }) {
                Text("Cancel")
            }
        }
    )
}
```

- [ ] **Step 3: Add missing imports**

```kotlin
import androidx.compose.foundation.lazy.items
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
```

- [ ] **Step 4: Build and verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/editor/NoteTemplates.kt app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt
git commit -m "feat: add smart note templates for new notes"
```

---

### Task 9: Add AI auto-tagging to notes

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/ai/AutoTagger.kt`
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorViewModel.kt`
- Modify: `app/src/main/java/com/omnidocs/app/data/local/entity/NoteEntity.kt`
- Modify: `app/src/main/java/com/omnidocs/app/domain/model/Note.kt`

**Interfaces:**
- Consumes: `LlamaCppService`, note content
- Produces: `tags` field on Note, auto-tagging on save

- [ ] **Step 1: Add tags field to Note model**

```kotlin
data class Note(
    val id: String = "",
    val title: String = "",
    val content: String = "",
    val plainText: String = "",
    val isPinned: Boolean = false,
    val language: String = "en",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val imageUrl: String? = null,
    val attachments: String = "[]",
    val isDeleted: Boolean = false,
    val tags: String = "[]" // JSON array of tag strings
)
```

- [ ] **Step 2: Add tags field to NoteEntity**

```kotlin
@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["isPinned", "updatedAt"]),
        Index(value = ["isSynced"])
    ]
)
data class NoteEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val content: String,
    val plainText: String,
    val isPinned: Boolean,
    val language: String,
    val createdAt: Long,
    val updatedAt: Long,
    val imageUrl: String?,
    val attachments: String = "[]",
    val isSynced: Boolean = false,
    val isDeleted: Boolean = false,
    val tags: String = "[]" // JSON array of tag strings
)
```

- [ ] **Step 3: Create AutoTagger.kt**

```kotlin
package com.omnidocs.app.ai

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AutoTagger"

@Singleton
class AutoTagger @Inject constructor(
    private val llamaCppService: LlamaCppService
) {
    /**
     * Generate tags for a note based on its content.
     * Returns a JSON array string of tag strings.
     */
    suspend fun generateTags(title: String, content: String): String {
        if (content.length < 50) return "[]"

        return try {
            val plainText = android.text.Html.fromHtml(content, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            val truncated = plainText.take(500)

            val prompt = """Analyze this note and generate 2-5 relevant tags.
Return ONLY a JSON array of lowercase strings, nothing else.
Example: ["programming", "tutorial", "javascript"]

Title: $title
Content: $truncated"""

            val result = llamaCppService.generate(prompt, maxTokens = 100)
            val tags = parseTags(result)
            Log.d(TAG, "Generated ${tags.size} tags")
            org.json.JSONArray(tags).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Tag generation failed", e)
            "[]"
        }
    }

    private fun parseTags(raw: String): List<String> {
        return try {
            val cleaned = raw.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val array = org.json.JSONArray(cleaned)
            (0 until array.length()).map { array.getString(it).lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
```

- [ ] **Step 4: Add auto-tagging to EditorViewModel save flow**

In `EditorViewModel`, inject `AutoTagger` and call it during save:

```kotlin
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val repository: NotesRepository,
    private val aiService: AiService,
    private val autoTagger: AutoTagger
) : ViewModel() {
```

In `saveNoteInternal()`, after saving the note, trigger auto-tagging:

```kotlin
private suspend fun saveNoteInternal() {
    _isSaving.value = true
    try {
        val note = _currentNote.value
        if (note != null) {
            // Auto-tag if content changed significantly
            val currentTags = try {
                org.json.JSONArray(note.tags).length()
            } catch (e: Exception) { 0 }

            if (currentTags == 0 && _content.value.length > 100) {
                val tags = autoTagger.generateTags(_title.value, _content.value)
                repository.updateNote(note.copy(tags = tags))
            }
            // ... existing save logic
        }
    } finally {
        _isSaving.value = false
    }
}
```

- [ ] **Step 5: Update database migration**

In `NotesDatabase.kt`, add migration 4->5 to add the tags column. Update the `@Database(version = 4)` to `version = 5`:

```kotlin
@Database(entities = [NoteEntity::class, NoteFtsEntity::class], version = 5, exportSchema = false)
```

Add migration:
```kotlin
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notes ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'")
    }
}
```

And add it to the migrations list:
```kotlin
.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
```

- [ ] **Step 6: Build and verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ai/AutoTagger.kt app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorViewModel.kt app/src/main/java/com/omnidocs/app/data/local/entity/NoteEntity.kt app/src/main/java/com/omnidocs/app/domain/model/Note.kt app/src/main/java/com/omnidocs/app/data/local/NotesDatabase.kt
git commit -m "feat: add AI auto-tagging that generates tags on note save"
```

---

### Task 10: Add collapsible sections in editor

**Files:**
- Modify: `app/src/main/assets/editor.html` (or equivalent WebView HTML)
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt`

**Interfaces:**
- Consumes: WebView JavaScript interface
- Produces: Collapsible heading functionality

- [ ] **Step 1: Add collapsible CSS and JS to editor.html**

Add to the editor's CSS:
```css
.collapsible-heading {
    cursor: pointer;
    user-select: none;
    position: relative;
    padding-left: 20px;
}
.collapsible-heading::before {
    content: '▸';
    position: absolute;
    left: 0;
    transition: transform 0.2s;
}
.collapsible-heading.collapsed::before {
    transform: rotate(0deg);
}
.collapsible-heading.expanded::before {
    transform: rotate(90deg);
}
.collapsible-content {
    overflow: hidden;
    transition: max-height 0.3s ease-out;
}
.collapsible-content.collapsed {
    max-height: 0;
    opacity: 0;
}
```

Add to the editor's JavaScript:
```javascript
function toggleCollapse(heading) {
    heading.classList.toggle('collapsed');
    heading.classList.toggle('expanded');
    const content = heading.nextElementSibling;
    if (content && content.classList.contains('collapsible-content')) {
        content.classList.toggle('collapsed');
    }
}

// Make H2 and H3 clickable for collapsing
document.querySelectorAll('h2, h3').forEach(heading => {
    heading.classList.add('collapsible-heading', 'expanded');
    heading.onclick = () => toggleCollapse(heading);
    
    // Wrap following content until next heading
    let content = [];
    let sibling = heading.nextElementSibling;
    while (sibling && !['H1', 'H2', 'H3'].includes(sibling.tagName)) {
        content.push(sibling);
        sibling = sibling.nextElementSibling;
    }
    if (content.length > 0) {
        const wrapper = document.createElement('div');
        wrapper.className = 'collapsible-content expanded';
        heading.parentNode.insertBefore(wrapper, heading.nextSibling);
        content.forEach(el => wrapper.appendChild(el));
    }
});
```

- [ ] **Step 2: Add toggle button to formatting toolbar**

Add a collapse toggle button to the FormattingToolbar in EditorScreen.kt:

```kotlin
FormatIconButton(
    icon = Icons.Default.UnfoldMore,
    contentDescription = "Toggle Section",
    isActive = false,
    onClick = { webViewRef?.evaluateJavascript("toggleAllCollapse()", null) }
)
```

- [ ] **Step 3: Build and verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/editor.html app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt
git commit -m "feat: add collapsible sections for headings in rich text editor"
```

---

### Task 11: Add markdown export

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt`
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorViewModel.kt`

**Interfaces:**
- Consumes: Current note content (HTML)
- Produces: Exported markdown file

- [ ] **Step 1: Add HTML-to-Markdown converter**

Create `app/src/main/java/com/omnidocs/app/util/HtmlToMarkdown.kt`:

```kotlin
package com.omnidocs.app.util

object HtmlToMarkdown {
    fun convert(html: String): String {
        var md = html
        // Headers
        md = md.replace(Regex("<h1[^>]*>(.*?)</h1>"), "# $1\n")
        md = md.replace(Regex("<h2[^>]*>(.*?)</h2>"), "## $1\n")
        md = md.replace(Regex("<h3[^>]*>(.*?)</h3>"), "### $1\n")
        // Bold and italic
        md = md.replace(Regex("<strong[^>]*>(.*?)</strong>"), "**$1**")
        md = md.replace(Regex("<b[^>]*>(.*?)</b>"), "**$1**")
        md = md.replace(Regex("<em[^>]*>(.*?)</em>"), "*$1*")
        md = md.replace(Regex("<i[^>]*>(.*?)</i>"), "*$1*")
        // Lists
        md = md.replace(Regex("<li[^>]*>(.*?)</li>"), "- $1\n")
        md = md.replace(Regex("<ul[^>]*>|</ul>"), "")
        md = md.replace(Regex("<ol[^>]*>|</ol>"), "")
        // Links and images
        md = md.replace(Regex("<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>"), "[$2]($1)")
        md = md.replace(Regex("<img[^>]*src=\"([^\"]+)\"[^>]*/?>"), "![]($1)")
        // Blockquotes
        md = md.replace(Regex("<blockquote[^>]*>(.*?)</blockquote>"), "> $1\n")
        // Code
        md = md.replace(Regex("<code[^>]*>(.*?)</code>"), "`$1`")
        md = md.replace(Regex("<pre[^>]*>(.*?)</pre>"), "```\n$1\n```")
        // Line breaks and paragraphs
        md = md.replace(Regex("<br\s*/?>"), "\n")
        md = md.replace(Regex("<p[^>]*>(.*?)</p>"), "$1\n\n")
        // Remove remaining HTML tags
        md = md.replace(Regex("<[^>]+>"), "")
        // Clean up whitespace
        md = md.replace(Regex("\n{3,}"), "\n\n")
        return md.trim()
    }
}
```

- [ ] **Step 2: Add export function to ViewModel**

```kotlin
fun exportAsMarkdown(context: Context) {
    viewModelScope.launch {
        try {
            val note = _currentNote.value ?: return@launch
            val markdown = HtmlToMarkdown.convert(_content.value)
            val fileName = "${note.title.take(50).replace(Regex("[^a-zA-Z0-9]"), "_")}.md"
            val file = File(context.getExternalFilesDir(null), "exports/$fileName")
            file.parentFile?.mkdirs()
            file.writeText("# ${note.title}\n\n$markdown")
            _snackbarEvent.emit("Exported to: ${file.name}")
        } catch (e: Exception) {
            _snackbarEvent.emit("Export failed: ${e.message}")
        }
    }
}
```

- [ ] **Step 3: Add export button to editor menu**

Add an export option to the editor's overflow menu or actions.

- [ ] **Step 4: Build and verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/util/HtmlToMarkdown.kt app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorViewModel.kt app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt
git commit -m "feat: add markdown export for notes"
```

---

## Verification

After all tasks are complete:

1. Build the app: `./gradlew :app:assembleDebug`
2. Install on device: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. Test each feature:
   - Create new note with template selection
   - Verify language menu shows all options
   - Check word count + reading time in TopAppBar
   - Test search bar on home screen
   - Verify empty states appear correctly
   - Test collapsible sections in editor
   - Export a note as markdown
   - Verify AI auto-tagging works on save
4. Check for any regressions in existing functionality
