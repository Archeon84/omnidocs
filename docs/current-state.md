# OmniDocs Current State Audit & System Overview

## 1. System Overview & Platform Constraints
- **Platform**: Native Android (API level 26 minimum, API level 34 target).
- **Core Architecture**: Local-first, private evidence-backed knowledge and document intelligence workspace.
- **Hardware Target**: Modern 64-bit ARM architecture (`arm64-v8a`), with development and testing verified on Xiaomi 13 Ultra (MIUI / Android 14).
- **Security & Privacy Model**: 100% on-device offline inference (LLMs, STT, Embeddings, OCR) with hardware-backed Android KeyStore encrypted SQLite storage (SQLCipher).

---

## 2. Programming Languages & Dependencies
- **Languages**: 
  - Kotlin 1.9.22 (Coroutines, Flow, StateFlow, Serialization)
  - C++20 (llama.cpp JNI, whisper.cpp JNI, native SIMD NEON optimizations)
  - JavaScript / HTML5 (WebView rich-text editor via Quill.js)
- **Frameworks & Core Libraries**:
  - **UI**: Jetpack Compose 1.6.2 (Material Design 3, Navigation, Animations)
  - **Dependency Injection**: Dagger Hilt 2.50
  - **Persistence**: Room 2.6.1 + SQLCipher 4.5.4 + Room FTS4 BM25
  - **Native/JNI**: CMake 3.22.1 + Android NDK 26.1.10909125
  - **STT**: Sherpa-ONNX 1.13.5 (Whisper Tiny/Base/Small/LargeV3 INT8 + Moonshine ONNX)
  - **LLM/Embeddings**: Custom vendored `llama.cpp` JNI supporting GGUF inference (Qwen 2.5/3, Llama 3) and Bert/E5 embedding architectures (`multilingual-e5-small-q8_0`).
  - **OCR**: Google ML Kit Text Recognition (offline on-device bundle) + Document Import Converters (PDF, DOCX, XLSX, PPTX, HTML, Markdown).
  - **Async/Image**: KotlinX Coroutines 1.8.0, Coil 2.6.0.

---

## 3. Frontend & UI Architecture
The UI is built with Jetpack Compose following clean Unidirectional Data Flow (UDF) with Hilt `@HiltViewModel`s:
- **`HomeScreen` (`/ui/screens/home`)**:
  - Masonry/staggered grid note feed with thumbnail rendering and dynamic preview snippets.
  - Tag filtering chips, pinned note pinning, quick audio/photo/document import fab buttons.
- **`EditorScreen` (`/ui/screens/editor`)**:
  - Tri-modal editing interface: Rich-Text (Quill.js WebView), Raw Markdown (Syntax highlighted text field), and Rendered Markdown Preview.
  - Interactive OCR Bounding-Box Overlay sheet (`OcrBoundingBoxInspectorSheet`) allowing interactive inspection of extracted text blocks over original document scans.
  - Real-time autosave with debounce and edit session management.
- **`VoiceCaptureOverlay` / `VoiceScreen` (`/ui/screens/voice`)**:
  - Live audio waveform and pulse level animations.
  - Real-time partial streaming transcription with silence detection and language switching (EN, BM, Mixed).
- **`SearchScreen` (`/ui/screens/search`)**:
  - Hybrid search interface showing exact BM25 text matches, semantic similarity vectors, and match provenance ("Why this result?").
- **`GraphScreen` (`/ui/screens/graph`)**:
  - Interactive WebGL/Canvas visual knowledge graph showing note relationship edges and detected contradiction hyperedges.
  - Graph export to standard GraphML and JSON-LD formats with system share sheet integration.
- **`AiHubScreen` / Settings (`/ui/screens/ai`, `/ui/screens/settings`)**:
  - GGUF and ONNX offline model download manager, storage quota manager, prompt format selector, and provider selection.

---

## 4. Database & Storage Architecture
Room database (`NotesDatabase`) backed by SQLCipher KeyStore encryption across 11 migrations:
- **Database Version**: 11
- **Entities & Tables**:
  1. `notes` (`NoteEntity`): Primary note records (title, markdown, rich text, plain text, language, note type, timestamps, soft delete `isDeleted`).
  2. `notes_fts` (`NoteFtsEntity`): FTS4 full-text index with custom tokenization for BM25 ranking.
  3. `recordings` (`RecordingEntity`): Captured audio metadata, duration, storage key, language, processing status.
  4. `transcript_segments` (`TranscriptSegmentEntity`): Timestamped audio transcript slices (`startMs`, `endMs`, `rawText`, `correctedText`, `confidence`).
  5. `speakers` (`SpeakerEntity`): Speaker profiles identified across recording sessions.
  6. `claims` (`ClaimEntity`): Extracted factual claims, decisions, interpretations, tasks, and questions.
  7. `evidence_links` (`EvidenceLinkEntity`): Provenance links connecting claims to notes, transcript slices, or document blocks with offset and quote hash.
  8. `action_items` (`ActionItemEntity`): Actionable tasks with owners, deadlines, priority, and completion status.
  9. `entities` & `entity_mentions` (`EntityEntity`, `EntityMentionEntity`): Named entities (person, organization, place, concept) and mentions.
  10. `note_links` (`NoteLinkEntity`): Directed graph connections between notes (user, rule, or AI created).
  11. `embeddings` (`EmbeddingEntity`): Passage-level 384-dimensional vector embeddings with chunk hash and model tagging.
  12. `source_documents` & `content_blocks` (`SourceDocumentEntity`, `ContentBlockEntity`): Document import records with OCR bounding boxes (`left`, `top`, `right`, `bottom`, confidence).
  13. `note_versions` (`NoteVersionEntity`): Incremental note snapshots and rollback history.
  14. `audit_events` (`AuditEventEntity`): Tamper-evident audit log for security and mutation tracking.
  15. `agent_jobs` & `agent_events` (`AgentJobEntity`, `AgentEventEntity`): Multi-agent execution trace and event log.
  16. `ai_artifacts` (`AiArtifactEntity`): Structured outputs (summaries, flashcards, study guides, timelines).

---

## 5. Offline AI & Machine Learning Pipeline
- **Inference Engine**: `llama.cpp` JNI (`LlamaCppService.kt`, `EmbeddingEngine.kt`) running on CPU with NEON SIMD intrinsics.
- **Supported Models**:
  - *Generative Q&A / Summary / Extraction*: Qwen 2.5 / Qwen 3 0.5B-3B, Llama 3 8B, DeepSeek R1 distilled models.
  - *Embeddings*: `multilingual-e5-small-q8_0.gguf` (384 dimensions, normalized).
  - *Speech-to-Text*: Sherpa-ONNX with Whisper Tiny/Base/Small INT8 models and Moonshine STT.
  - *Translation*: Offline NLLB-200 via chunked inference.
- **Output Processing & Safety**:
  - `AiOutputProcessor`: Strips `<think>` tags, simulated subsequent turns (`\n\nUser:`), markdown code fences, and conversational preambles (`Here is the summary:`), with paragraph-level self-repetition deduplication.
  - `PromptBuilder`: Adapts prompts dynamically between ChatML, Llama 3, Alpaca, and Raw formats.

---

## 6. Hybrid Search & Retrieval System
- **Lexical Search**: SQLite FTS4 table (`notes_fts`) computing BM25 relevance scores.
- **Semantic Search**: In-memory cosine similarity over `EmbeddingDao` vector store with model tag verification.
- **Hybrid Fusion**: Reciprocal Rank Fusion (RRF) combining BM25 lexical ranks and cosine semantic ranks with exact-match boost and metadata filtering.
- **Provenance ("Why this result?")**: Exposes match reason (exact keyword, semantic synonym, title match, transcript match) and snippet citation.

---

## 7. Build, Verification & Testing Commands
- **Unit Tests**: `./gradlew :app:testDebugUnitTest`
- **Debug Assembly**: `./gradlew :app:assembleDebug`
- **Device Deployment**: `adb -s <device_id> install -r app/build/outputs/apk/debug/app-debug.apk`
- **C/C++ Native Compilation**: CMake via Gradle Android NDK toolchain (`arm64-v8a`, `x86_64`).
