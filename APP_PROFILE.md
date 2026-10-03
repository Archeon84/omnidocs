# OmniDocs — Application Profile

> **Document Role**: Product Context, Strategic Architecture & Feature Inventory.  
> **Source of Truth**: For technical setup and builds, refer to [README.md](file:///h:/Work/OmniDocs/README.md). For engineering handoff, refer to [docs/handoff/overview.md](file:///h:/Work/OmniDocs/docs/handoff/overview.md).

## 1. Identity

| Field | Value |
|---|---|
| **Application Name** | OmniDocs |
| **Package ID** | `com.omnidocs.app` |
| **Version** | 1.0 (`versionCode = 1`) |
| **Platform** | Android (Kotlin / Jetpack Compose) |
| **Min SDK** | 26 (Android 8.0 Oreo) |
| **Target / Compile SDK** | 34 (Android 14) |
| **NDK** | 27.0.12077973 |
| **Target ABI** | `arm64-v8a` only |
| **Architecture** | Single-Activity, MVVM, Hilt DI |
| **Database** | Room 2.6.1 + SQLCipher AES-256 |
| **Test Device** | Xiaomi 13 Ultra (Snapdragon 8 Gen 2, ARMv8.6-A) |

## 2. What It Is

OmniDocs is a privacy-first, offline-capable Android note-taking and document intelligence app. It combines rich text editing, OCR scanning, voice recording with speech-to-text, on-device LLM reasoning, semantic vector search, and a knowledge graph into a single encrypted workspace. All AI/ML workloads run locally on the device with no telemetry. Cloud services are optional fallbacks, not defaults.

## 3. Feature Inventory

### 3.1 Navigation Screens (12 routes)

| Screen | Route | Purpose |
|---|---|---|
| Home | `home` | Note list, search, template picker, bottom nav |
| Editor | `editor?noteId={noteId}` | Tri-mode note editor (Rich/Markdown/Preview) with intelligence and evidence panels |
| OCR | `ocr` | Live camera scanning with bounding box overlays |
| Settings | `settings` | App preferences, AI model config, theme selection |
| Feed | `feed` | Activity feed / recent changes timeline |
| Auth | `auth` | Google Sign-In for cloud backup |
| Voice | `voice` | Live audio recording with waveform overlay |
| Graph | `graph` | Interactive knowledge graph (vis.js WebView) |
| Tasks | `tasks` | Auto-extracted action items from notes |
| Privacy | `privacy` | Privacy settings and audit log viewer |
| Recordings | `recordings` | Recording library with playback and transcript review |
| Ask | `ask` | RAG-powered Q&A chat over your notes |

### 3.2 Core Capabilities

- **Tri-Mode Note Editor**: Rich text (WebView `editor.html`), raw Markdown, and rendered Preview modes with bidirectional conversion via `MarkdownCodec` (Flexmark 0.64.8). Debounced autosave with `pendingContent` buffer and `isPageReady` guard to prevent WebView race conditions.
- **8-Format Document Import**: PDF (PDFBox-Android), DOCX/DOC (Apache POI XWPF/HWPF), XLSX (POI XSSF), PPTX (POI XSLF), HTML (Jsoup), Markdown (Flexmark), Plain Text/CSV. Routed through `DocumentConverterFactory`.
- **Neural OCR**: ML Kit Text Recognition v2 across Latin, Chinese, Japanese, Korean scripts. Live camera bounding box overlay (`LiveCameraOverlay`). Structured HTML output via `OcrHtmlBuilder`.
- **Speech-to-Text**: Sherpa-ONNX v1.13.5 runtime with Whisper Small/Large V3 INT8 and Moonshine models. 16kHz PCM capture via `VoiceCaptureManager`. Speaker diarization with per-speaker identity labels.
- **On-Device LLM**: llama.cpp (tag b9976) via custom JNI wrapper (`llama_jni.cpp`). Runs Qwen3 1.7B and Llama 3 8B GGUF models. Supports summarization, auto-tagging, proofreading, action item extraction, and evidence-grounded Q&A.
- **Hybrid RAG Search**: FTS4 BM25 full-text search fused with 384-dim vector similarity from `multilingual-e5-small-q8_0.gguf` embeddings. Score formula: `0.6 * BM25 + 0.4 * CosineSim`. N-gram hash fallback when no embedding model is loaded.
- **Knowledge Graph**: Entity extraction (Person, Organization, Location, Concept), inter-note wiki links (`[[note_title]]`), contradiction detection, idea evolution tracking, orphan note detection. Visualized in an interactive vis.js WebView.
- **Note Intelligence**: Summarization, claim extraction with source citations (`SourceBackedQA` + `EvidenceExtractor`), auto-tagging (`AutoTagger`), contradiction detection (`ContradictionDetector`), idea evolution tracking (`IdeaEvolution`), orphan detection (`OrphanDetector`).
- **Version History**: Per-note revision snapshots stored in `NoteVersionEntity`, reviewable via `VersionHistorySheet`.
- **Encrypted Backup**: ZIP-based local backup/restore via `LocalBackupService` with full Room transaction safety. Cloud backup via Google Drive (`DriveService`).
- **Smart Templates**: Built-in note templates managed by `TemplateManager`, selectable from `TemplatePickerSheet` on the home screen.
- **Security Audit Trail**: All sensitive operations logged to `AuditEventEntity` via `AuditLogger`, viewable in the privacy settings screen.

## 4. Technology Stack

### 4.1 Core Platform

| Component | Version |
|---|---|
| Kotlin | 2.1.0 |
| Compose Compiler | 2.1.0 |
| Android Gradle Plugin | 8.2.2 |
| KSP | 2.1.0-1.0.29 |
| Hilt | 2.51.1 |
| CMake | 3.22.1 |

### 4.2 UI and Presentation

| Library | Version | Purpose |
|---|---|---|
| Compose BOM | 2024.02.00 | Material 3, Animation, UI Tooling |
| Material Icons Extended | (BOM) | Full icon set |
| Navigation Compose | 2.7.7 | Single-activity screen routing |
| Hilt Navigation Compose | 1.2.0 | ViewModel injection in nav graph |
| Coil Compose | 2.5.0 | Image loading |
| Accompanist Permissions | 0.34.0 | Runtime permission handling |
| Core Splash Screen | 1.0.1 | Splash API |

### 4.3 Data and Persistence

| Library | Version | Purpose |
|---|---|---|
| Room (runtime/ktx/compiler) | 2.6.1 | ORM with FTS4 virtual tables |
| SQLCipher | 4.5.3 | AES-256 database encryption |
| DataStore Preferences | 1.0.0 | Key-value settings storage |

### 4.4 AI and ML

| Library | Version | Purpose |
|---|---|---|
| llama.cpp (JNI) | b9976 | On-device LLM inference and embeddings |
| Sherpa-ONNX | 1.13.5 | Whisper STT runtime |
| ML Kit Text Recognition | 19.0.1 | Latin script OCR |
| ML Kit Chinese/Japanese/Korean | 16.0.1 | CJK script OCR |
| ML Kit Document Scanner | 16.0.0-beta1 | Document scanning |

### 4.5 Document Processing

| Library | Version | Purpose |
|---|---|---|
| Apache POI | 5.2.5 | DOCX, DOC, XLSX, PPTX parsing |
| PDFBox-Android | 2.0.27.0 | PDF text extraction |
| iText7 Core | 7.2.5 | PDF generation |
| Flexmark | 0.64.8 | Markdown parsing and rendering |
| Jsoup | 1.17.2 | HTML parsing and sanitization |

### 4.6 Networking and Auth

| Library | Version | Purpose |
|---|---|---|
| OkHttp | 4.12.0 | HTTP client for model downloads |
| Play Services Auth | 21.0.0 | Google Sign-In |
| Play Services Auth API Phone | 18.0.2 | Phone number auth |

### 4.7 Camera

| Library | Version | Purpose |
|---|---|---|
| CameraX (camera2, core, lifecycle, view) | 1.3.1 | Live camera preview and analysis |

### 4.8 Testing

| Library | Version |
|---|---|
| JUnit | 4.13.2 |
| Turbine | 1.0.0 |
| Espresso | 3.5.1 |
| kotlinx-coroutines-test | (BOM) |
| Compose UI Test | (BOM) |
| Room Testing | 2.6.1 |

## 5. Themes

9 themes selectable via DataStore preferences:

| Theme | Description |
|---|---|
| Light | Default. Teal primary (`#00BFA6`), warm paper background |
| Dark | Dark surface with teal accents |
| AMOLED | True black background for OLED screens |
| Sepia | Warm, paper-like reading mode |
| Ocean | Blue-toned color scheme |
| Forest | Green-toned nature palette |
| Lavender | Purple-accented soft palette |
| System | Follows Android system dark/light toggle |
| Dynamic | Material You dynamic color extraction (Android 12+) |

## 6. Database Schema (16 Room Entities)

| Entity | Table | Purpose |
|---|---|---|
| `NoteEntity` | `notes` | Primary note storage (title, content, plainText, tags, attachments, pinned/deleted flags) |
| `NoteFtsEntity` | `notes_fts` | FTS4 virtual table for full-text search with auto-sync triggers |
| `RecordingEntity` | `recordings` | Audio recording metadata (path, duration, sample rate, format) |
| `TranscriptSegmentEntity` | `transcript_segments` | STT output with timestamps and speaker IDs |
| `SpeakerEntity` | `speakers` | Diarized speaker identity labels |
| `ClaimEntity` | `claims` | AI-extracted atomic propositions |
| `EvidenceLinkEntity` | `evidence_links` | Citation mappings from claims to source notes |
| `ActionItemEntity` | `action_items` | Auto-extracted tasks with assignees and due dates |
| `EntityEntity` | `entities` | Named entities (Person, Organization, Location, Concept) |
| `EntityMentionEntity` | `entity_mentions` | Entity occurrence positions within notes |
| `NoteLinkEntity` | `note_links` | Inter-note wiki-link edges |
| `EmbeddingEntity` | `embeddings` | 384-dim dense vector blobs with model-name partitioning |
| `AiRunEntity` | `ai_runs` | Execution log for local/cloud AI operations |
| `NoteVersionEntity` | `note_versions` | Revision history snapshots |
| `AuditEventEntity` | `audit_events` | Security audit trail |
| `SavedSearchEntity` | `saved_searches` | User-saved search queries and filters |

## 7. Native C++ Layer

### 7.1 llama.cpp JNI Bridge (`llama_jni.cpp`)

- **Init**: `nativeInit(path, addBos)` loads GGUF model into `llama_model` + `llama_context`
- **Generate**: `nativeGenerate(prompt, maxTokens)` evaluates in 64-token chunks; samples via top-k (40), top-p (0.9), temperature (0.7)
- **Thread Safety**: Timed mutex (`g_llama_mutex`) prevents corruption from Kotlin coroutine cancellation
- **SIMD**: Compiled with `armv8.6-a+dotprod+i8mm` for hardware int8 matrix multiply + OpenMP threading
- **Compiler Flags**: `-std=c++11 -frtti -fexceptions -O2 -DNDEBUG -DANDROID_STL=c++_shared -DANDROID_ARM_NEON=TRUE`

### 7.2 Model Integrity

- `GgufTensorValidator.kt`: Parses GGUF binary headers to verify quantization tags and vocabulary metadata before C++ loading, preventing native SIGSEGV faults from corrupt or incompatible models.

### 7.3 Model Download Security

- `ModelDownloadManager.kt`: SSRF-protected downloads restricted to an allowlist of hostnames: `github.com`, `objects.githubusercontent.com`, `github-releases.githubusercontent.com`, `release-assets.githubusercontent.com`, `huggingface.co`, `hf.co`.
- SHA-256 integrity verification of downloaded files.

## 8. On-Device AI Models

| Model | Format | Dimensions | Use Case |
|---|---|---|---|
| Qwen3 1.7B | GGUF | - | Primary reasoning: summarization, tagging, Q&A, proofreading |
| Llama 3 8B | GGUF | - | High-capacity reasoning fallback |
| Multilingual E5 Small Q8_0 | GGUF | 384-dim | Dense vector embeddings for semantic search |
| Whisper Small INT8 | ONNX | - | Speech-to-text (fast, smaller) |
| Whisper Large V3 INT8 | ONNX | - | Speech-to-text (accurate, larger) |
| Moonshine | ONNX | - | Alternative offline speech recognizer |

## 9. Architecture Diagrams

### 9.1 AI Pipeline

```
User Input (text/voice/image)
         |
    +----+----+
    |         |         |
    v         v         v
  OCR       STT      Direct
(ML Kit)  (Sherpa)   Text
    |         |         |
    +----+----+---------+
         |
         v
  +-------------+
  | ModelResolver| ---- selects engine
  +------+------+
         |
    +----+----+
    |         |
    v         v
  Local     Cloud
 (llama    (fallback)
  .cpp)
    |
    v
  AI Features
  - Summarize
  - Auto-tag
  - Extract claims
  - Extract tasks
  - Q&A with citations
  - Contradiction scan
  - Idea evolution
```

### 9.2 Hybrid Search Pipeline

```
        Search Query
             |
    +--------+--------+
    |                 |
    v                 v
 FTS4 BM25      Vector Search
 (SQLite)      (E5 Embeddings)
    |                 |
    | weight: 0.6     | weight: 0.4
    +--------+--------+
             |
             v
    Fused Ranked Results
```

### 9.3 Note Editor Modes

```
 [Rich Text]     [Markdown]     [Preview]
      |               |              |
   WebView       Plain Text     Compose MD
 (editor.html)    TextField      Renderer
      |               |
      +-------+-------+
              |
        MarkdownCodec
       (Flexmark 0.64.8)
              |
        Room + Autosave
```

## 10. Package Structure

```
com.omnidocs.app/                          (130 Kotlin files)
|
+-- NotesApplication.kt                    @HiltAndroidApp entry point
+-- MainActivity.kt                        Single activity + splash
|
+-- ai/                    (15 files)      On-device AI and LLM services
|   +-- LlamaCppService.kt                 JNI facade for llama.cpp
|   +-- EmbeddingEngine.kt                 JNI facade for vector embeddings
|   +-- ModelResolver.kt                   Local vs Cloud engine selector
|   +-- ModelDownloadManager.kt            SSRF-protected model downloader
|   +-- ModelPreferences.kt                AI model DataStore settings
|   +-- GgufTensorValidator.kt             Pre-load GGUF header validator
|   +-- NoteIntelligenceService.kt         Summarize, proofread, audit facade
|   +-- AutoTagger.kt                      Automatic topic tag generator
|   +-- SourceBackedQA.kt                  Evidence-grounded RAG Q&A
|   +-- EvidenceExtractor.kt               Claim and citation extractor
|   +-- NllbTranslationService.kt          Neural machine translation
|   +-- AiOutputProcessor.kt               Structured JSON AI output parser
|   +-- AiService.kt                       Cloud AI fallback interface
|   +-- PromptBuilder.kt                   Generic prompt formatter
|   +-- Qwen3PromptBuilder.kt              ChatML prompt builder for Qwen3
|
+-- audit/                 (1 file)        Security event logging
|   +-- AuditLogger.kt
|
+-- data/                  (~40 files)     Persistence layer
|   +-- local/
|   |   +-- NotesDatabase.kt               Room database config (16 entities)
|   |   +-- entity/                         16 Room entity classes
|   |   +-- *Dao.kt                         16 DAO interfaces
|   |   +-- Migration7.kt, Migration8.kt   Schema migrations
|   +-- backup/
|   |   +-- LocalBackupService.kt           Encrypted ZIP backup/restore
|   |   +-- LocalBackupPreferences.kt       Backup config DataStore
|   +-- remote/
|   |   +-- DriveService.kt                 Google Drive cloud backup
|   +-- repository/
|       +-- NotesRepository.kt              Unified data access facade
|
+-- di/                    (1 file)        Hilt dependency injection
|   +-- DatabaseModule.kt
|
+-- docimport/             (10 files)      Document converter pipeline
|   +-- DocumentConverterFactory.kt         Converter routing by MIME type
|   +-- DocumentConverter.kt                Base interface
|   +-- Pdf/Docx/Doc/Xlsx/Pptx/Html/Markdown/PlainText converters
|
+-- domain/                (2 files)       Domain models
|   +-- model/Note.kt, AppUpdate.kt
|
+-- graph/                 (3 files)       Knowledge graph engine
|   +-- GraphEngine.kt                      Entity and link graph builder
|   +-- GraphData.kt                        Node and edge representations
|   +-- Bm25Scorer.kt                       FTS4 BM25 relevance scoring
|
+-- knowledge/             (3 files)       Knowledge analysis
|   +-- ContradictionDetector.kt            Cross-note conflict scanner
|   +-- IdeaEvolution.kt                    Concept trajectory tracker
|   +-- OrphanDetector.kt                   Unlinked note finder
|
+-- ocr/                   (5 files)       Optical character recognition
|   +-- MlKitOcrEngine.kt                   ML Kit text recognition
|   +-- OcrEngine.kt                        Base OCR interface
|   +-- OcrEngineFactory.kt                 Engine factory
|   +-- OcrHtmlBuilder.kt                   Spatial layout to HTML
|   +-- ImagePreprocessor.kt                Camera frame preprocessing
|
+-- search/                (3 files)       Search and retrieval
|   +-- VectorSearch.kt                     In-memory cosine similarity
|   +-- EmbeddingService.kt                 Vector generation + n-gram fallback
|   +-- SearchExplainer.kt                  Score breakdown explainer
|
+-- stt/                   (4 files)       Speech-to-text
|   +-- SherpaOnnxSttEngine.kt              Sherpa-ONNX Whisper wrapper
|   +-- SttEngine.kt                        Base STT interface
|   +-- SttEngineFactory.kt                 Engine factory
|   +-- SttModelInfo.kt                     Model metadata registry
|
+-- templates/             (2 files)       Note templates
|   +-- TemplateManager.kt                  Built-in template registry
|   +-- NoteTemplate.kt                     Template definition model
|
+-- ui/                    (~34 files)     Compose UI layer
|   +-- components/
|   |   +-- BottomNavBar.kt                 Bottom navigation bar
|   |   +-- Shimmer.kt                      Loading shimmer animations
|   +-- navigation/
|   |   +-- Navigation.kt                   NavHost with 12 screen routes
|   +-- theme/
|   |   +-- Theme.kt                        9 AppTheme variants + DataStore
|   |   +-- Color.kt                        M3 color tokens per theme
|   |   +-- Type.kt                         Typography scale
|   |   +-- Shape.kt                        Shape tokens
|   |   +-- Motion.kt                       Animation tokens and transitions
|   +-- screens/
|       +-- home/           HomeScreen, TemplatePickerSheet
|       +-- editor/         EditorScreen, EditorViewModel, IntelligencePanel,
|       |                   EvidencePanel, VersionHistorySheet, NoteTemplates
|       +-- ocr/            OcrScreen, OcrViewModel, LiveCameraOverlay
|       +-- settings/       SettingsScreen, SettingsViewModel, PrivacySettingsScreen
|       +-- ask/            AskNotesScreen (RAG Chat)
|       +-- graph/          GraphScreen, GraphViewModel
|       +-- voice/          VoiceCaptureOverlay, VoiceCaptureViewModel
|       +-- feed/           FeedScreen
|       +-- auth/           AuthScreen, AuthViewModel
|       +-- tasks/          TaskListScreen, TaskListViewModel
|       +-- recordings/     RecordingsScreen
|
+-- util/                  (3 files)       Text utilities
|   +-- MarkdownCodec.kt                    Bidirectional MD/HTML conversion
|   +-- HtmlToMarkdown.kt                   HTML to GFM parser
|   +-- HtmlSanitizer.kt                    Jsoup HTML sanitizer
|
+-- voice/                 (3 files)       Audio recording and playback
    +-- VoiceCaptureManager.kt              AudioRecord 16kHz PCM capture
    +-- RecordingStorage.kt                 Audio file storage manager
    +-- AudioPlaybackController.kt          MediaPlayer wrapper
```

## 11. Web Assets (in `app/src/main/assets/`)

| File | Purpose |
|---|---|
| `editor.html` | Rich text editor runtime. JavaScript-based content editing with bidirectional Kotlin communication via `updateContent()` and `onPageFinished` lifecycle hooks. |
| `graph.html` | Interactive knowledge graph visualization powered by vis.js. Renders note nodes and entity edge connections. |

## 12. Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Model downloads, cloud backup, optional cloud AI |
| `CAMERA` | OCR live scanning and document capture |
| `RECORD_AUDIO` | Voice recording for speech-to-text |

## 13. Build Configuration

- **R8/ProGuard**: Enabled in release builds. ProGuard rules protect JNI method signatures (`LlamaCppService`, `EmbeddingEngine`, sherpa-onnx native methods) and reflectively loaded classes (Apache POI, iText7).
- **Packaging**: Excludes duplicate `META-INF` license files. BouncyCastle dependency excluded to resolve conflict between iText7 and Flexmark.
- **Native Build**: CMake 3.22.1 with `c++_shared` STL, ARM NEON enabled, targeting `arm64-v8a` only.

## 14. Summary Statistics

| Metric | Count |
|---|---|
| Kotlin source files | ~130 |
| Room entities | 16 |
| DAO interfaces | 16 |
| Navigation screens | 12 |
| Document import formats | 8 |
| OCR language scripts | 4 (Latin, Chinese, Japanese, Korean) |
| App themes | 9 |
| On-device AI models | 6 |
| Native C++ files | 2 (`llama.cpp`, `llama_jni.cpp`) |
| Schema migrations | 2 (Migration7, Migration8) |
