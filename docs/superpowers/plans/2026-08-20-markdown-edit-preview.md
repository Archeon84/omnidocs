# Markdown Edit / Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Rich / Markdown / Preview toggle to the note editor, keeping the existing rich WebView editor as the default, with markdown source editing and a read-only rendered preview.

**Architecture:** Notes stay stored as HTML. A mode enum drives which editor UI shows: Rich = existing contenteditable WebView; Markdown = monospace plain-text field seeded from `HtmlToMarkdown.convert(html)`; Preview = read-only WebView populated with flexmark-rendered HTML. A snapshot of the original HTML is kept so an unedited round-trip restores the note exactly.

**Tech Stack:** Kotlin, Compose M3, flexmark-all 0.64.8 (already a dependency), jsoup HtmlSanitizer (existing), JUnit for JVM tests.

## Global Constraints

- Notes are stored as HTML; never change the Room schema or the persisted format.
- Reuse existing utilities: `com.omnidocs.app.util.HtmlToMarkdown.convert(html)` for HTML->markdown, flexmark (`com.vladsch.flexmark.parser.Parser` + `com.vladsch.flexmark.html.HtmlRenderer`) for markdown->HTML, and `com.omnidocs.app.util.HtmlSanitizer.sanitize(html)` for all HTML entering any WebView.
- Round-trip fidelity: markdown->HTML conversion runs only after a real markdown edit. An unedited Rich->Markdown->Rich cycle must restore the original HTML byte-for-byte.
- All composables remain in the existing files (`EditorScreen.kt`, `EditorViewModel.kt`) following their established patterns. New utility code goes in its own focused file. Each mode owns its own WebView instance; there is no shared WebView between Rich, Markdown, and Preview.
- Build command is `gradlew assembleDebug` from repo root. Device is Xiaomi 13 Ultra (arm64-v8a only).

---

### Task 1: MarkdownCodec conversion facade

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/util/MarkdownCodec.kt`
- Test: `app/src/test/java/com/omnidocs/app/util/MarkdownCodecTest.kt`

**Interfaces:**
- Consumes: `com.omnidocs.app.util.HtmlToMarkdown.convert(String): String`
- Produces: `object MarkdownCodec { fun htmlToMarkdown(html: String): String; fun markdownToHtml(md: String): String }` — the single conversion point used by the ViewModel and UI. `markdownToHtml` returns sanitized HTML (never raw flexmark output).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.omnidocs.app.util

import org.junit.Test
import org.junit.Assert.*

class MarkdownCodecTest {

    @Test
    fun `markdown to html - headers bold links`() {
        val md = "# Title\n\nSome **bold** and a [link](https://x.com)."
        val html = MarkdownCodec.markdownToHtml(md)
        assertTrue(html.contains("<h1>"))
        assertTrue(html.contains("Title"))
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("<a href=\"https://x.com\">link</a>"))
    }

    @Test
    fun `markdown to html - strips script tags (sanitized)`() {
        val md = "hi\n<script>alert(1)</script>"
        val html = MarkdownCodec.markdownToHtml(md)
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("alert(1)"))
    }

    @Test
    fun `html to markdown - round trips back for plain content`() {
        val html = "<h1>Hello</h1><p>Some <strong>bold</strong> text.</p>"
        val md = MarkdownCodec.htmlToMarkdown(html)
        assertTrue(md.contains("# Hello"))
        assertTrue(md.contains("**bold**"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.omnidocs.app.util.MarkdownCodecTest"`
Expected: FAIL — `MarkdownCodec` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.omnidocs.app.util

import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser

/**
 * Single conversion point between markdown source and rich HTML.
 * markdownToHtml always sanitizes its output so it is safe to inject
 * into a WebView. htmlToMarkdown delegates to the existing regex-based
 * HtmlToMarkdown (used by export); it is inherently lossy for complex
 * embeds, which the editor's snapshot guard protects against.
 */
object MarkdownCodec {
    private val parser: Parser = Parser.builder().build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder().build()

    fun htmlToMarkdown(html: String): String =
        HtmlToMarkdown.convert(html)

    fun markdownToHtml(md: String): String {
        if (md.isBlank()) return ""
        return try {
            val doc = parser.parse(md)
            HtmlSanitizer.sanitize(renderer.render(doc))
        } catch (_: Exception) {
            // Never let a parse failure escape; fall back to escaped plain text.
            sanitizeForHtml(md)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.omnidocs.app.util.MarkdownCodecTest"`
Expected: PASS (3 tests). Note flexmark renders `<h1>Title</h1>` (the `# Title\n` becomes a heading); if the exact tag match is brittle, assert on substring content instead (`html.contains("Title")`, `html.contains("bold")`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/util/MarkdownCodec.kt app/src/test/java/com/omnidocs/app/util/MarkdownCodecTest.kt
git commit -m "feat: MarkdownCodec conversion facade for md<->html"
```

---

### Task 2: Markdown-mode state in EditorViewModel

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorViewModel.kt`

**Interfaces:**
- Consumes: `com.omnidocs.app.util.MarkdownCodec.{htmlToMarkdown, markdownToHtml}`
- Produces (used by EditorScreen):
  - `enum class EditorMode { RICH, MARKDOWN, PREVIEW }`
  - `val editorMode: StateFlow<EditorMode>` (default RICH)
  - `val markdownText: StateFlow<String>`
  - `fun setEditorMode(mode: EditorMode)` — handles snapshot capture, dirty detection, and markdown->HTML conversion on leaving markdown.
  - `fun updateMarkdown(text: String)` — sets dirty + autosaves only when actually leaving markdown mode.
  - `fun currentMarkdown(): String` — the markdown source to render in Preview mode (re-derived from the effective content).

- [ ] **Step 1: Add EditorMode enum and state fields**

After the `ContentSource` enum (near line 30), add:

```kotlin
/** Which editor the user is currently viewing. RICH is the default WebView
 *  contenteditable editor; MARKDOWN is a plain-text source view; PREVIEW is a
 *  read-only rendered view. */
enum class EditorMode { RICH, MARKDOWN, PREVIEW }
```

Near the other `_content` state field (after line 56), add:

```kotlin
private val _editorMode = MutableStateFlow(EditorMode.RICH)
val editorMode: StateFlow<EditorMode> = _editorMode.asStateFlow()

private val _markdownText = MutableStateFlow("")
val markdownText: StateFlow<String> = _markdownText.asStateFlow()

// Snapshot of the HTML as it was when markdown mode was entered. If the user
// edits nothing and returns to Rich, this exact HTML is restored (fidelity).
private var richHtmlSnapshot: String = ""
// True once the markdown text has diverged from htmlToMarkdown(richHtmlSnapshot).
private var markdownDirty = false
```

- [ ] **Step 2: Add setEditorMode / updateMarkdown / currentMarkdown**

Add these methods after `appendToContent` (around line 284):

```kotlin
fun setEditorMode(mode: EditorMode) {
    val previous = _editorMode.value
    if (previous == mode) return

    when (mode) {
        EditorMode.RICH -> {
            if (previous == EditorMode.MARKDOWN && markdownDirty) {
                // A real markdown edit happened; convert source -> HTML and push
                // it into content. RichTextEditor reacts to the PROGRAMMATIC
                // source change and refreshes the WebView.
                val html = MarkdownCodec.markdownToHtml(_markdownText.value)
                if (html.isNotBlank()) {
                    pushUndo()
                    _contentSource.value = ContentSource.PROGRAMMATIC
                    _content.value = html
                    isDirty = true
                }
            } else if (previous == EditorMode.MARKDOWN) {
                // No edit: restore the exact original HTML, preserving embeds.
                if (richHtmlSnapshot != _content.value) {
                    _contentSource.value = ContentSource.PROGRAMMATIC
                    _content.value = richHtmlSnapshot
                }
            }
            // In RICH, current content (possibly converted or restored) is the
            // source of truth; markdownText is stale until next markdown entry.
            markdownDirty = false
        }
        EditorMode.MARKDOWN -> {
            // Seed markdown from the current HTML, snapshotting it for fidelity.
            richHtmlSnapshot = _content.value
            _markdownText.value = MarkdownCodec.htmlToMarkdown(richHtmlSnapshot)
            markdownDirty = false
        }
        EditorMode.PREVIEW -> {
            // Preview always branches from the current effective source.
            if (previous == EditorMode.MARKDOWN) {
                // No conversion here; PREVIEW from MARKDOWN edits via render of markdownText.
            }
        }
    }
    _editorMode.value = mode
}

fun updateMarkdown(text: String) {
    _markdownText.value = text
    markdownDirty = true
}

/** Markdown source shown in Preview mode. From RICH it re-derives from HTML so
 *  unstaged HTML edits are reflected; from MARKDOWN it is the live edited text. */
fun currentMarkdown(): String = when (_editorMode.value) {
    EditorMode.RICH, EditorMode.PREVIEW -> MarkdownCodec.htmlToMarkdown(_content.value)
    EditorMode.MARKDOWN -> _markdownText.value
}
```

- [ ] **Step 3: Compile check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (The markdownDirty/markdownText fields are referenced by EditorScreen in the next task; this task only needs the ViewModel to compile standalone.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorViewModel.kt
git commit -m "feat: markdown-mode state and transitions in EditorViewModel"
```

---

### Task 3: Mode UI in EditorScreen (segmented toggle, markdown editor, preview)

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt`

**Interfaces:**
- Consumes: `EditorViewModel.EditorMode`, `viewModel.editorMode`, `viewModel.markdownText`, `viewModel.setEditorMode(EditorMode)`, `viewModel.updateMarkdown(String)`, `viewModel.currentMarkdown(): String`; `MarkdownCodec.markdownToHtml`; `HtmlSanitizer`.
- Produces: `MarkdownEditor` and `MarkdownPreview` composables (private to file).

The `handleBack` lambda must save based on the current mode. Add a mode-aware save step.

- [ ] **Step 1: Add EditorMode import reference and read mode state**

In `EditorScreen`, after `val contentSource by viewModel.contentSource.collectAsState()` (line 160), add mode state collection:

```kotlin
val editorMode by viewModel.editorMode.collectAsState()
val markdownText by viewModel.markdownText.collectAsState()
```

Import `MarkdownCodec` (add to existing imports): `import com.omnidocs.app.util.MarkdownCodec`

- [ ] **Step 2: Make back-save mode-aware**

Replace the body of `handleBack` (lines 195-217) so that in MARKDOWN mode it converts the markdown source to HTML before saving. Insert at the top of the `else` branch:

```kotlin
} else {
    // In markdown mode, commit markdown source back to content before saving
    // so the DB stores the markdown in rich HTML form.
    if (viewModel.editorMode.value == EditorMode.MARKDOWN && markdownText != viewModel.currentMarkdown()) {
        viewModel.setEditorMode(EditorMode.RICH)
    }
    webViewRef?.evaluateJavascript("getContent()") { result ->
        ...
    } ?: run {
        viewModel.saveAndNavigate(onNavigateBack)
    }
}
```

Note: `setEditorMode(RICH)` converts edited markdown to HTML and restores the snapshot for unedited content; after it, `getContent()` reads the (now updated, or restored) WebView DOM. This simple form is always safe:

```kotlin
if (viewModel.editorMode.value == EditorMode.MARKDOWN) {
    viewModel.setEditorMode(EditorMode.RICH)
}
```

Unedited paths restore the snapshot into the WebView; edited paths convert and push HTML. In both cases the WebView DOM reflects the content that `getContent()` will read and save.

- [ ] **Step 3: Add the segmented mode selector**

In the `Column` body, above the formatting toolbar block (before line 460), add the mode selector:

```kotlin
// Mode selector: Rich / Markdown / Preview
Column(
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp)
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        EditorMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = editorMode == mode,
                onClick = { viewModel.setEditorMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = EditorMode.entries.size)
            ) {
                Text(mode.name, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
```

Hide the formatting toolbar unless in RICH mode. Wrap the existing `Box { FormattingToolbar(...) }` block (lines 460-488) with:

```kotlin
if (editorMode == EditorMode.RICH) {
    Box(...) { FormattingToolbar(...) }
}
```

- [ ] **Step 4: Render the right editor per mode**

Replace the `RichTextEditor` usage block (lines 520-543) with a mode switch:

```kotlin
when (editorMode) {
    EditorMode.RICH -> RichTextEditor(
        content = content,
        contentSource = contentSource,
        onContentChange = { newContent, fromWebView ->
            viewModel.updateContent(newContent, fromWebView)
        },
        onFormatStateChange = { formatState = it },
        onWebViewCreated = { webViewRef = it },
        onEditorFocusChanged = { isEditorFocused = it },
        onTextSelectionChanged = { text ->
            selectedText = text
        },
        modifier = Modifier.fillMaxSize()
    )
    EditorMode.MARKDOWN -> MarkdownEditor(
        text = markdownText,
        onTextChange = { viewModel.updateMarkdown(it) },
        modifier = Modifier.fillMaxSize()
    )
    EditorMode.PREVIEW -> MarkdownPreview(
        markdown = viewModel.currentMarkdown(),
        modifier = Modifier.fillMaxSize()
    )
}
```

- [ ] **Step 5: Add the MarkdownEditor and MarkdownPreview composables**

Append these private composables near the bottom of the file (after `RichTextEditor`, before its closing, or as file-level private functions after it):

```kotlin
/** Monospace plain-text markdown source editor used in MARKDOWN mode. */
@Composable
private fun MarkdownEditor(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = fontFamily),
        placeholder = { Text("Write in markdown...") },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.outline,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
        )
    )
}

/** Read-only rendered markdown preview used in PREVIEW mode. Reuses the
 *  editor.html WebView styling by rendering flexmark HTML into the same
 *  contenteditable container, made non-editable. */
@Composable
private fun MarkdownPreview(
    markdown: String,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                val htmlTemplate = context.assets.open("editor.html").bufferedReader().use { it.readText() }
                val rendered = MarkdownCodec.markdownToHtml(markdown)
                val html = htmlTemplate.replace("<!-- CONTENT_PLACEHOLDER -->", rendered)
                    .replace("contenteditable=\"true\"", "contenteditable=\"false\"")
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        modifier = modifier.semantics {
            contentDescription = "Markdown preview"
        }
    )
}
```

- [ ] **Step 6: Compile check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt
git commit -m "feat: rich/markdown/preview mode toggle in note editor"
```

---

### Task 4: Build and verify on device

**Files:** none (validation only).

- [ ] **Step 1: Full build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL, APK produced.

- [ ] **Step 2: Run unit tests once more**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (MarkdownCodecTest + existing SecurityTests).

- [ ] **Step 3: Install to device**

Run (device is Xiaomi 13 Ultra, adb already configured):
`adb install -r app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 4: Manual verification checklist**

Open a note and verify:
- Default shows the Rich editor with the formatting toolbar and the three-segment toggle (Rich | Markdown | Preview).
- Tapping Markdown shows a monospace editor seeded with markdown source; the formatting toolbar is hidden.
- Tapping Preview shows rendered markdown (headers, bold, code) in a non-editable padded view.
- **Fidelity check:** with a note containing an image or bold text, Rich -> Markdown -> Rich with NO edits restores the image/content exactly (no degradation).
- **Edit check:** in Markdown mode, type `## New Heading`; switch to Preview -> header renders; switch back to Rich -> heading appears as rich text.
- Back from Markdown mode after an edit saves the note with the converted HTML (relaunch app, reopen note, edit persisted).
- Back with an unedited markdown visit does not alter the stored content.

- [ ] **Step 5: Commit any fixes**

If the manual checks surface a bug, fix in the relevant file and re-run Step 3-4, then commit. Otherwise Task 4 is validation-only; mark complete.

---

## Self-Review

- **Spec coverage:** Segmented toggle (Task 3 Step 3), markdown source editor (Task 3 Step 5), read-only preview (Task 3 Step 5), Rich unchanged (Task 3 Step 4 keeps RichTextEditor), fidelity snapshot guard (Task 2 Step 2 + back-save in Task 3 Step 2), HTML stays the storage format (Global Constraints). All spec decisions map to tasks.
- **Placeholder scan:** No TBD/TODO; every code step has full code. ✓
- **Type consistency:** `EditorMode.{RICH,MARKDOWN,PREVIEW}`, `MarkdownCodec.{htmlToMarkdown,markdownToHtml}`, `viewModel.{editorMode,markdownText,setEditorMode,updateMarkdown,currentMarkdown}` are defined once (Task 2) and consumed consistently (Task 3). The unused `webViewRef` param on `MarkdownPreview` was removed so each mode owns its own WebView.
