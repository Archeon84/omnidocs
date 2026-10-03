# OmniDocs — Architecture Guide

> **Document Role**: Structural blueprint and architectural decision records (ADRs).  
> **Source Evidence**: Directly extracted from observed repository code.

---

## 1. System Mental Model

OmniDocs follows a reactive, unidirectional data-flow architecture structured into three primary tiers:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        PRESENTATION LAYER                              │
│         Jetpack Compose (Navigation: 17 Routes, Material 3)            │
│                                                                        │
│   HomeScreen       EditorScreen     LocalChatScreen    AskNotesScreen  │
│   GraphScreen      StudyScreen      OcrScreen          VoiceOverlay    │
│   WelcomeScreen    SettingsScreen   TaskListScreen     AgentJobsScreen │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ UI Events / State Collection
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        VIEWMODEL & AGENT TIER                          │
│          Hilt @HiltViewModel + Kotlin Coroutine StateFlows             │
│                                                                        │
│   EditorViewModel     LocalChatViewModel   AskNotesViewModel           │
│   OcrViewModel        StudyViewModel       WelcomeViewModel            │
│  ────────────────────────────────────────────────────────────────────  │
│   Orchestrators & Knowledge Agents:                                    │
│   • ResearchCoordinator (Multi-turn RRF + VerificationAgent)           │
│   • ImportPipelineCoordinator (Document text & task extraction)        │
│   • MeetingPipelineCoordinator (Speech diarization + action items)     │
│   • AgentCoordinator + PolicyGuard (Background job queue)              │
└───────────────┬───────────────────┬───────────────────┬────────────────┘
                │                   │                   │
                ▼                   ▼                   ▼
┌─────────────────────────┐ ┌───────────────────┐ ┌──────────────────────┐
│    ON-DEVICE AI / ML    │ │ RETRIEVAL & GRAPH │ │    DATA & STORAGE    │
│                         │ │                   │ │                      │
│ • LiteRtLmService       │ │ • VectorSearch    │ │ • NotesDatabase      │
│   (Gemma 4 E2B/E4B)     │ │   (RRF k=60)      │ │   (Room v18, 24 tabs)│
│ • SherpaOnnxSttEngine   │ │ • AnnIndexManager │ │ • SQLCipher AES-256  │
│   (Whisper/Moonshine)   │ │   (USearch HNSW)  │ │   (KeyStore Master)  │
│ • MlKitOcrEngine        │ │ • GraphEngine     │ │ • LocalChatStorage   │
│   (Latin/CJK CameraX)   │ │   (vis.js WebView)│ │   (Room ChatDao)     │
│ • EmbeddingEngine       │ │ • PassageChunker  │ │ • LocalBackupService │
│   (LiteRT E5 Small)     │ │   (Sliding window)│ │   (AES-256-GCM ZIP)  │
│ • ThermalBudgetManager  │ │                   │ │ • DriveService       │
│                         │ │                   │ │   (OkHttp REST)      │
└─────────────────────────┘ └───────────────────┘ └──────────────────────┘
```

---

## 2. Key Architectural Decisions (ADRs)

### ADR-01: Generative LLM Inference Engine
* **Decision**: Adopt Google's official **LiteRT-LM SDK** (`com.google.ai.edge.litertlm:litertlm-android`) running quantized Gemma 4 (`.litertlm`).
* **Superseded**: Legacy JNI bindings (`llama_jni.cpp` / `llama.cpp`).
* **Rationale**: LiteRT-LM provides production-grade Google Play Services acceleration (GPU/NPU delegates), hardware thermal awareness, and robust lifecycle hooks on modern Android runtimes.
* **Fallback Status**: `llama_jni.cpp` (42 KB) and `llama.cpp` are retained decoupled in `app/src/main/cpp/` as inactive fallback reference assets.

### ADR-02: Cloud Sync Architecture
* **Decision**: Direct Google Drive REST API via OkHttp (`DriveService.kt`) targeting the user's private `appDataFolder`.
* **Superseded**: Firebase Firestore / Firebase Auth.
* **Rationale**: Preserves user privacy and zero-knowledge architecture. OmniDocs requires no centralized backend servers or Firebase subscriptions; users own their storage on their personal Google Drive accounts.

### ADR-03: Local Persistence & Storage Consolidation
* **Decision**: Unified SQLite database via Android Room, Schema Version 18 (24 entity tables), encrypted with SQLCipher AES-256.
* **Consolidation**: Local Chat sessions and messages were originally stored in unencrypted JSON files under `filesDir/local_chats/`. In Schema v18, they were migrated into `chat_sessions` and `chat_messages` tables via `ChatDao`, ensuring identical cryptographic guarantees and inclusion in `LocalBackupService`.

### ADR-04: Approximate Nearest Neighbor (ANN) Indexing
* **Decision**: C++ native **USearch** HNSW vector index (`usearch-android` compiled via `CMakeLists.txt`).
* **Rationale**: Standard Room SQLite does not support native vector cosine operations. USearch executes sub-millisecond HNSW searches over 384-dimensional embeddings in memory with minimal memory footprint.

---

## 3. Data Flow Pathways

### Pathway A: Note Authoring & Auto-Save
1. User enters text in [EditorScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt).
2. Debounced input (1500 ms) dispatches to [EditorViewModel.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorViewModel.kt).
3. [NotesRepository.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/repository/NotesRepository.kt) persists note in Room, writes a snapshot to `note_versions`, and updates SQLite `notes_fts` table.
4. `EmbeddingService` generates 384-d passage embeddings via `EmbeddingEngine` and commits vectors to `AnnIndexManager`.

### Pathway B: Grounded RAG Q&A ("Talk with your Notes")
1. User asks question in [AskNotesScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/ask/AskNotesScreen.kt).
2. [ResearchCoordinator.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/agent/ResearchCoordinator.kt) executes Reciprocal Rank Fusion ($k=60$) across SQLite FTS4 (BM25) and USearch HNSW (vector distance).
3. Top passages are bundled into a prompt budget managed by `PromptBudgetManager.kt`.
4. [LiteRtLmService.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ai/LiteRtLmService.kt) streams response tokens through `ThinkingStreamFilter`.
5. [VerificationAgent.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/agent/VerificationAgent.kt) cross-examines generated claims against source text before UI renders interactive citation chips.
