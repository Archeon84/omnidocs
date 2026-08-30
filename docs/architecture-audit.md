# OmniDocs Architecture Audit

## 1. Architectural Style & Design Principles
OmniDocs is built as a **Local-First, Evidence-First Knowledge & Intelligence Workspace**. The system follows a layered architecture with Unidirectional Data Flow (UDF), dependency injection via Dagger Hilt, and strict separation between UI, domain logic, persistence, and native C++ execution.

```
┌────────────────────────────────────────────────────────┐
│                   Presentation Layer                   │
│   (Jetpack Compose, M3, Navigation, ViewModels)        │
└───────────┬────────────────────────────────┬───────────┘
            │                                │
┌───────────▼────────────────┐   ┌───────────▼───────────┐
│     Domain & App Services  │   │  Multi-Agent Engine   │
│  (Intelligence, Evidence,  │   │  (AnswerAgent,        │
│   STT, Graph, OCR, Import) │   │   ContradictionAgent) │
└───────────┬────────────────┘   └───────────┬───────────┘
            │                                │
┌───────────▼────────────────────────────────▼───────────┐
│              Repository & Data Layer                   │
│  (NotesRepository, EmbeddingService, AudioStorage)     │
└───────────┬────────────────────────────────┬───────────┘
            │                                │
┌───────────▼────────────────┐   ┌───────────▼───────────┐
│  Room + SQLCipher Database │   │ Native C++ Subsystems │
│  (FTS4 BM25, 16 Tables,    │   │ (llama.cpp JNI,       │
│   KeyStore Master Key)     │   │  sherpa-onnx NEON)    │
└────────────────────────────┘   └───────────────────────┘
```

---

## 2. Layer-by-Layer Architectural Audit

### 2.1 Presentation Layer
- **Framework**: Modern declarative Jetpack Compose Material 3.
- **Pattern**: ViewModel-driven UDF with immutable UI State exposed via `StateFlow` and actions triggered via suspendable ViewModel functions.
- **Key Screens**:
  - `HomeScreen`: Fast asynchronous list/grid rendering with reactive Room `Flow` queries.
  - `EditorScreen`: Tri-mode switching between Rich-Text (Quill.js in WebView), Raw Markdown text editor, and parsed Markdown preview. Includes `OcrBoundingBoxInspectorSheet` for provenance inspection.
  - `VoiceScreen` / `VoiceCaptureOverlay`: Live audio amplitude sampling (`liveAudioLevel`) and continuous Whisper streaming transcript rendering.
  - `SearchScreen`: Multi-facet hybrid search results (Lexical BM25, Vector Cosine, Provenance explanation).
  - `GraphScreen`: WebGL/HTML5 Canvas knowledge graph visualization with contradiction hyperedges and GraphML/JSON-LD export menus.

### 2.2 Domain & Application Services
- **`NoteIntelligenceService`**: Orchestrates on-device LLM Q&A, context-aware text explanations, and concept extraction with automatic deduplication against existing notes.
- **`EvidenceExtractor`**: Analyzes note bodies and transcripts to extract structured claims, actions, decisions, and entities with exact character/timestamp offsets.
- **`GraphExportService`**: Standardized serialization to GraphML XML Schema and W3C Linked Data JSON-LD (`@context: https://schema.org`).
- **`DocumentConverterFactory`**: Multi-format document ingestion engine converting PDF, DOCX, XLSX, PPTX, HTML, and Markdown to clean notes with bounding-box content blocks.
- **`VoiceCaptureManager`**: Multi-engine STT coordinator managing Sherpa-ONNX, Moonshine, whisper.cpp JNI, and Android System Recognizer.

### 2.3 Multi-Agent Orchestration Layer
- **Base Abstraction**: `Agent` interface defining `execute(input: AgentInput, context: AgentContext): AgentResult`.
- **Active Agents**:
  - `AnswerAgent`: Grounded knowledge assistant strictly constrained to supplied candidate passages with SHA-256 quote hashes and confidence scoring.
  - `ContradictionDetectionAgent`: Cross-note semantic claim comparison identifying contradictory statements.
  - `IdeaEvolutionAgent`: Temporal idea tracking across note versions and chronological timestamps.
- **Execution Tracing**: All multi-agent invocations, steps, and outcomes are persisted in `agent_jobs` and `agent_events` Room tables for observability.

### 2.4 Persistence & Storage Subsystems
- **Relational Storage**: Room 2.6.1 + SQLCipher 4.5.4. Master encryption key generated and stored inside Android hardware KeyStore (`AndroidKeyStore`).
- **Full-Text Search**: SQLite FTS4 virtual table (`notes_fts`) using custom Porter stemmer and BM25 relevance calculation.
- **Vector Storage**: SQLite `embeddings` table storing binary Float32 384-dimensional embeddings, queried via in-memory SIMD-accelerated cosine distance.
- **Audio & File Storage**: App-private internal storage (`/data/user/0/com.omnidocs.app/files/recordings/`), managed with streaming I/O and SHA-256 verification.
- **Referential Integrity**: Cascading foreign keys (`onDelete = ForeignKey.CASCADE`) linking notes to content blocks, embeddings, recordings, transcripts, and evidence links.

### 2.5 Native Inference Layer (C++ / JNI)
- **Vendored Libraries**: `llama.cpp` (b9976 JNI bridge) and `sherpa-onnx` v1.13.5.
- **Optimizations**: Target architecture ARM64-v8a with ARM NEON SIMD vectorization.
- **Memory Safety**: Direct `ByteBuffer` memory management with explicit native context release in `close()` lifecycle methods to prevent memory leaks in long-running processes.

---

## 3. Data Flow & Communication Patterns
1. **Audio to Knowledge**:
   `Microphone Audio` ➔ `SherpaOnnxSttEngine` ➔ `TranscriptSegmentDao` ➔ `EvidenceExtractor` ➔ `ClaimDao` + `EvidenceLinkDao` ➔ `NotesRepository`.
2. **Document Ingestion**:
   `Document File (PDF/DOCX/OCR)` ➔ `DocumentConverter` ➔ `SourceDocumentEntity` + `ContentBlockEntity (with BoundingBoxes)` ➔ `EmbeddingService` ➔ `NotesDatabase`.
3. **Hybrid Search Flow**:
   `Query` ➔ `BM25 FTS4 Rank` + `E5-Small Embedding Cosine Rank` ➔ `Reciprocal Rank Fusion (RRF)` ➔ `Provenance Annotation` ➔ `Search UI`.
4. **Knowledge Interchange**:
   `Notes & Contradictions` ➔ `GraphExportService` ➔ `GraphML XML / JSON-LD` ➔ `FileProvider` ➔ `Android System Share Sheet`.

---

## 4. Key Strengths & Architectural Highlights
- **100% Privacy Preservation**: Zero user telemetry, zero mandatory cloud dependencies. All embeddings, OCR, speech recognition, and generative AI run locally on the device CPU/NPU.
- **Grounded Provenance Guarantee**: Every AI output is accompanied by an `EvidenceLinkEntity` with start/end offsets, start/end timestamps, and SHA-256 quote hashes.
- **Robust Migration Path**: 11 sequential Room migrations verified with automated schema hashes and zero data loss.
- **Resilient AI Pipeline**: Output processor strips `<think>` tokens, simulated dialogue loops, and self-repetition before reaching UI layers.
