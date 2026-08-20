# Markdown Edit / Preview for Notes

**Date:** 2026-08-20
**Status:** Approved design (pending spec review)

## Goal

Add a markdown edit + rendered-preview mode to the note editor, while keeping the
existing rich (WYSIWYG WebView) editor as the default mode. Users can switch between
three modes via a segmented control.

## Current State

- Notes are stored as **HTML** in Room (no schema change needed).
- The primary editor is a WebView (`assets/editor.html`) whose `#editor` div is
  `contenteditable`. Compose `RichTextEditor` wraps it; content flows through
  `EditorViewModel.content` (HTML string).
- `HtmlToMarkdown.convert(html)` (regex-based) already exists for export and is lossy
  on advanced structures (nested lists, tables, `content://` image/audio embeds).
- **flexmark** (`com.vladsch.flexmark:flexmark-all:0.64.8`) is already a dependency,
  used by `MarkdownDocumentConverter` (markdown -> HTML via `Parser` + `HtmlRenderer`).

## Decisions

1. **Mode flow:** A segmented control toggles between `Rich`, `Markdown`, `Preview`.
   - `Rich` = existing WebView contenteditable editor (unchanged) with formatting toolbar.
   - `Markdown` = plain-text monospace markdown source editor.
   - `Preview` = read-only rendered markdown (flexmark HTML shown in a read-only WebView,
     so it reuses all existing theme/styling).
2. **Fidelity (preserve HTML on unedited round-trip):** Entering markdown mode keeps the
   original HTML snapshot. Converting markdown -> HTML only happens if the user actually
   edited the markdown text. An untouched round-trip (Rich -> Markdown -> Rich with no
   edits) restores the original HTML unchanged, so image/audio/tables are never degraded
   by accidental toggling.

## Components

### 1. EditorMode enum
```kotlin
enum class EditorMode { RICH, MARKDOWN, PREVIEW }
```
Held as Compose state in `EditorScreen`. Default `RICH`.

### 2. Mode selector
An M3 `SingleChoiceSegmentedButtonRow` (or equivalent) shown above the editor area.
- In `RICH` mode the existing `FormattingToolbar` renders below/beside it as today.
- In `MARKDOWN` and `PREVIEW` the formatting toolbar is hidden (it operates on the rich
  WebView only).

### 3. Markdown source editor
A full-width scrollable monospace `OutlinedTextField` bound to a `markdownText`
`MutableStateFlow` in `EditorViewModel`.

### 4. Preview pane
A read-only WebView mode. Reuses `assets/editor.html` with `contenteditable=false` and
populates `#editor` with flexmark-rendered + sanitized HTML. Keeps identical styling.

## Data Flow

- **RICH -> MARKDOWN:** take current `content` (HTML), run `HtmlToMarkdown.convert()` to
  seed `markdownText`. Keep the original HTML in a `richHtmlSnapshot` field. Mark `dirty = false`.
- **MARKDOWN edit:** as the user types, mark `dirty = true` (any change to the
  pre-seeded text).
- **MARKDOWN -> RICH:** if `dirty` is true (text differs from the seeded snapshot),
  convert `markdownText` via flexmark `HtmlRenderer` -> HTML -> `HtmlSanitizer.sanitize` ->
  push to `viewModel.updateContent(html, PROGRAMMATIC)` and the WebView. If not dirty,
  restore `richHtmlSnapshot` untouched.
- **MARKDOWN -> PREVIEW** and **RICH -> PREVIEW:** run the current effective source through
  the same flexmark + sanitize path into the read-only preview WebView.
- Back navigation / save reads the currently-active editor's content:
  - RICH: WebView DOM via `getContent()` (existing path).
  - MARKDOWN: convert markdown -> HTML (via flexmark) if dirty, then save.
  - PREVIEW: content unchanged; save existing.

## Fidelity Notes

`HtmlToMarkdown.convert` is lossy for nested lists, tables, and `content://` attachments.
This is acceptable because:
- RICH is the default and primary mode.
- Markdown -> HTML conversion only runs after a real markdown edit (per decision 2).
- Preview is read-only; it renders a derivation of the source without mutating stored content.

## Error Handling

- Flexmark never throws on valid input; wrap the md -> html conversion in try/catch and
  fall back to the sanitized original HTML on any unexpected failure.
- Markdown -> HTML output is always passed through `HtmlSanitizer.sanitize` before entering
  the WebView, preserving the existing XSS guard.

## Testing

- Build (assembleDebug) passes.
- Manual: toggle Rich <-> Markdown with no edit restores identical HTML (no data loss).
- Manual: edit markdown, switch to Preview shows updated rendering, switch to Rich shows
  converted rich HTML.
- Manual: markdown with a table and a `content://` image bracket does not crash and, on an
  unedited round-trip, restores embeds intact.
- Manual: back-navigation from Markdown mode with edits saves converted HTML.
