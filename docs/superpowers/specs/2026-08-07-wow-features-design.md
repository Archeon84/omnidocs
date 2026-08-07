# OmniDocs "Thinking Tools" -- Wow Features Design

## Vision

Transform OmniDocs from a note-taking app into an **AI-powered thinking tool**. Three interconnected features that make the app feel alive:

1. **Knowledge Graph** -- see how your notes connect
2. **Voice Capture** -- speak a thought, AI structures it instantly
3. **Note Intelligence** -- ask questions about your notes, get explanations, extract concepts

These aren't separate features -- they form a loop: **capture** (voice) -> **understand** (intelligence) -> **connect** (graph). The graph reveals what you know, voice captures new thinking, and intelligence helps you work with existing knowledge.

---

## Feature 1: Knowledge Graph

### What it does

A new screen showing an interactive force-directed graph where each node is a note and edges represent AI-detected relationships. Tap a node to open the note. Pinch to zoom, drag to pan. Relationships have labels ("related to", "references", "contradicts").

### Architecture

**New files:**
- `app/src/main/java/com/omnidocs/app/graph/GraphEngine.kt` -- analyzes notes, builds relationship data
- `app/src/main/java/com/omnidocs/app/graph/GraphData.kt` -- data classes (GraphNode, GraphEdge)
- `app/src/main/java/com/omnidocs/app/ui/screens/graph/GraphScreen.kt` -- WebView-based graph screen
- `app/src/main/java/com/omnidocs/app/ui/screens/graph/GraphViewModel.kt` -- state management
- `app/src/main/assets/graph.html` -- D3.js force-directed graph (bundled)

**Modified files:**
- `app/src/main/java/com/omnidocs/app/data/local/entity/NoteEntity.kt` -- add `relatedNotes: String = "[]"` field
- `app/src/main/java/com/omnidocs/app/data/local/NotesDatabase.kt` -- version 5->6 migration
- `app/src/main/java/com/omnidocs/app/ui/navigation/Navigation.kt` -- add Graph route
- `app/src/main/java/com/omnidocs/app/ui/screens/home/HomeScreen.kt` -- add graph button in TopAppBar

### Data model

```kotlin
data class GraphNode(
    val noteId: String,
    val title: String,
    val x: Float,    // computed by simulation
    val y: Float,
    val radius: Float, // based on word count or link count
    val color: Color   // from tag or theme
)

data class GraphEdge(
    val from: String,  // noteId
    val to: String,    // noteId
    val label: String, // "related to", "references", etc.
    val strength: Float // 0.0-1.0, how strong the connection is
)
```

### GraphEngine

Uses the existing on-device LLM to analyze note pairs:

1. On first launch or when notes change significantly, batch-analyze notes
2. For each note, the LLM receives its plainText + titles of N nearest neighbors (by word overlap)
3. LLM returns JSON: `{"connections": [{"noteId": "...", "label": "...", "strength": 0.8}]}`
4. Store results in NoteEntity.relatedNotes (JSON array)
5. Incremental: only re-analyze notes that changed since last graph build

### Force Simulation

Simple Verlet integration (no external library):
- Repulsion between all nodes (inverse square)
- Attraction along edges (spring force)
- Center gravity to prevent drift
- 200 iterations on load, then stop (frozen layout)
- User can drag nodes to reposition

### GraphScreen

- Full-screen WebView loading a local HTML file (`graph.html` from assets)
- D3.js force-directed graph (bundled in assets, ~150KB minified)
- Nodes: colored circles with note title labels, sized by word count
- Edges: curved lines with arrowheads and relationship labels
- Node interactions via JavaScript interface:
  - Tap node -> `Android.onNodeTapped(noteId)` -> navigate to editor
  - Hover node -> highlight connected edges, show tooltip with preview
- Pinch-to-zoom and pan via D3's built-in zoom behavior
- Glassmorphism styling: semi-transparent node backgrounds, glow on edges
- FAB: "Rebuild Graph" to force re-analysis
- Empty state: "Add more notes to see connections"
- Dark/light mode: passes theme colors to JS via `setGraphTheme()`

### graph.html (assets)

- Single HTML file with inline D3.js (minified)
- CSS: glassmorphism nodes, gradient edges, smooth transitions
- JS: force simulation, drag behavior, zoom, node click handlers
- Color palette derived from MaterialTheme colors (passed from Kotlin)
- Animation: nodes gently pulse on load, edges fade in with stagger

---

## Feature 2: Voice Capture

### What it does

A floating action button on the home screen that opens a voice recording overlay. User speaks, real-time transcription appears. On stop, the on-device LLM structures the raw transcript into a formatted note with headings, bullets, and action items.

### Architecture

**New files:**
- `app/src/main/java/com/omnidocs/app/voice/VoiceCaptureManager.kt` -- manages SpeechRecognizer + LLM structuring
- `app/src/main/java/com/omnidocs/app/ui/screens/voice/VoiceCaptureOverlay.kt` -- floating overlay UI
- `app/src/main/java/com/omnidocs/app/ui/screens/voice/VoiceCaptureViewModel.kt` -- state management

**Modified files:**
- `app/src/main/java/com/omnidocs/app/ui/screens/home/HomeScreen.kt` -- add voice FAB

### VoiceCaptureManager

```kotlin
@Singleton
class VoiceCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llamaCppService: LlamaCppService,
    private val repository: NotesRepository
) {
    private var recognizer: SpeechRecognizer? = null
    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    fun startListening() { ... }
    fun stopListening() { ... }
    suspend fun structureTranscript(rawText: String, language: String): String { ... }
}
```

### Structuring Prompt

The LLM receives the raw transcript and returns structured HTML:

```
System: You are a note structuring assistant. Convert raw speech transcript into
well-structured HTML notes. Add headings (h2), bullet lists, action items with
checkboxes, and bold key terms. Preserve all meaning. Return only HTML.

User: [raw transcript]
```

### VoiceCaptureOverlay

- Appears as a bottom sheet overlay on the home screen
- Large circular recording button (pulses red when recording)
- Real-time transcript text appears as user speaks
- Waveform visualization (simple amplitude bars from audio levels)
- "Done" button -> sends transcript to LLM -> creates note -> navigates to editor
- "Cancel" button -> discards
- Shows "Structuring..." with loading animation while LLM processes
- Respects reduced motion

### Integration

- Voice FAB appears on HomeScreen (bottom-right, above existing FAB)
- Permission: RECORD_AUDIO requested on first use
- Language: uses the user's selected language from settings
- Output: creates a new note with structured HTML content, title auto-generated from first sentence

---

## Feature 3: Note Intelligence

### What it does

AI-powered interactions within the editor:

1. **Ask About Note** -- type a question about the current note, get an answer in a floating panel
2. **Smart Explain** -- select text, AI explains it in context
3. **Concept Extractor** -- AI finds key concepts and offers to create linked notes for each

### Architecture

**New files:**
- `app/src/main/java/com/omnidocs/app/ai/NoteIntelligenceService.kt` -- orchestrates all three operations
- `app/src/main/java/com/omnidocs/app/ui/screens/editor/IntelligencePanel.kt` -- bottom panel UI
- `app/src/main/java/com/omnidocs/app/ui/screens/editor/ConceptDialog.kt` -- concept extraction results

**Modified files:**
- `app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt` -- add intelligence panel
- `app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorViewModel.kt` -- add intelligence state
- `app/src/main/java/com/omnidocs/app/ui/screens/editor/FormattingToolbar.kt` -- add AI intelligence button
- `app/src/main/assets/editor.html` -- add text selection listener + `getSelectedText()` JS function

### NoteIntelligenceService

```kotlin
@Singleton
class NoteIntelligenceService @Inject constructor(
    private val llamaCppService: LlamaCppService,
    private val repository: NotesRepository
) {
    // Ask a question about a note
    suspend fun askAboutNote(
        noteContent: String,
        question: String,
        language: String
    ): String { ... }

    // Explain selected text in context
    suspend fun explainText(
        fullNote: String,
        selectedText: String,
        language: String
    ): String { ... }

    // Extract key concepts from a note
    suspend fun extractConcepts(
        noteContent: String,
        language: String
    ): List<ExtractedConcept>
}

data class ExtractedConcept(
    val name: String,
    val description: String,
    val existingNoteId: String? // if a note with this concept already exists
)
```

### Ask About Note

- A text input field at the bottom of the editor (above keyboard)
- User types a question -> LLM answers using the note content as context
- Answer appears in an expandable panel above the input
- Conversation history maintained (multi-turn Q&A within the note)
- Panel can be minimized to a floating chip

### Smart Explain

- In editor.html, add `getSelectedText()` JS function:
```javascript
function getSelectedText() {
    var sel = window.getSelection();
    return sel.toString();
}
```
- Add `onTextSelectionChanged(selectedText)` to JS interface
- When text is selected, a small "Explain" chip appears near the selection
- Tapping it sends the selected text + surrounding context to the LLM
- Explanation appears in the intelligence panel

### Concept Extractor

- Button in the AI action menu: "Extract Concepts"
- LLM analyzes the note and returns 3-5 key concepts with descriptions
- Dialog shows concepts as cards, each with:
  - Concept name (bold)
  - One-sentence description
  - "Create Note" button (creates a new note pre-filled with the concept as title and description as content)
  - "Already exists" badge if a note with that title exists
- Created notes appear in the knowledge graph

### EditorScreen Integration

The intelligence panel slides up from the bottom (similar to keyboard):
```kotlin
var showIntelligencePanel by remember { mutableStateOf(false) }

Column(modifier = Modifier.fillMaxSize().imePadding()) {
    // toolbar
    // editor
    // intelligence panel (animated visibility)
    AnimatedVisibility(
        visible = showIntelligencePanel,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })
    ) {
        IntelligencePanel(
            question = ...,
            answer = ...,
            onAsk = { ... },
            onDismiss = { showIntelligencePanel = false }
        )
    }
}
```

---

## Navigation Changes

Add Graph route:
```kotlin
object Graph : Screen("graph")
```

Home screen gets two new buttons in TopAppBar:
- Graph icon (top-right, before settings) -> navigates to GraphScreen
- Voice FAB (bottom-right of content area)

---

## Database Migration (v5 -> v6)

```sql
ALTER TABLE notes ADD COLUMN relatedNotes TEXT NOT NULL DEFAULT '[]'
```

`relatedNotes` stores JSON array of related note IDs with labels and strengths, computed by GraphEngine.

---

## Implementation Order

The features have natural dependencies:

1. **Phase 1: Note Intelligence** (no new screens, modifies existing editor)
   - NoteIntelligenceService
   - IntelligencePanel UI
   - Editor.html text selection
   - Concept extraction + dialog
   - EditorViewModel integration

2. **Phase 2: Voice Capture** (new overlay, modifies home screen)
   - VoiceCaptureManager
   - VoiceCaptureOverlay UI
   - HomeScreen FAB integration
   - LLM structuring prompt

3. **Phase 3: Knowledge Graph** (new screen, new engine)
   - GraphData data classes
   - GraphEngine (LLM analysis)
   - ForceSimulation
   - GraphScreen + GraphViewModel
   - DB migration
   - HomeScreen graph button

Each phase is independently usable. Phase 1 enhances the editor. Phase 2 adds voice input. Phase 3 adds the graph. Together they form the "capture -> understand -> connect" loop.

---

## Success Criteria

- Voice capture produces well-structured notes from free-form speech
- Ask About Note answers questions accurately using note content
- Smart Explain provides context-aware explanations
- Concept Extractor identifies meaningful concepts and creates useful linked notes
- Knowledge Graph shows real relationships between notes
- Graph is interactive (zoom, pan, tap to open)
- All AI features work on-device (no internet required)
- Performance: graph renders smoothly with 50+ notes, voice capture starts within 1s
- Accessibility: all features work with reduced motion, screen readers
