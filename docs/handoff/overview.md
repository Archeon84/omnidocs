# OmniDocs — Handoff Overview

> **Document Role**: Primary entrypoint for engineering handoff and executive status.  
> **Target Audience**: Incoming technical leads, auditing engineers, and contributing developers.  
> **Last Updated**: October 3, 2026  
> **Canonical Branch**: `release/handoff-2026-10`

---

## 1. Executive Summary

**OmniDocs** is a privacy-first, fully offline personal knowledge base and document intelligence application for Android. It operates as a local "Second Brain", executing all text generation, audio transcription, vector search, document OCR, and spaced repetition entirely on the user's mobile device without cloud telemetry.

### Core Value Proposition
* **Zero Cloud Dependency**: AI inference runs on device via Google's official **LiteRT-LM SDK** (Gemma 4 E2B/E4B), Sherpa-ONNX (Whisper/Moonshine), and Google ML Kit.
* **Encrypted at Rest**: User knowledge is stored in a 24-entity SQLite database encrypted with **SQLCipher AES-256**, keyed through Android KeyStore.
* **Hybrid Lexical-Vector Retrieval**: Sub-millisecond search combining SQLite FTS4 BM25 and native C++ USearch HNSW vector indices over 384-dimensional embeddings.
* **Tri-Mode Document Authoring**: Markdown, rich visual HTML contenteditable, and rendered preview with automated revision snapshotting.

---

## 2. Current Project State Matrix

| Feature Cluster | User-Visible Capabilities | Implementation Status | Verification Level | Primary Code Entrypoints |
| :--- | :--- | :---: | :---: | :--- |
| **First-Run Onboarding** | 5-page carousel, model setup, permission requests | **Complete** | **Verified Working** (Unit tested + device verified) | [WelcomeScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/welcome/WelcomeScreen.kt), [WelcomeViewModel.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/welcome/WelcomeViewModel.kt) |
| **Note Editor** | Tri-mode (Rich/Raw MD/Preview), word counter, auto-save | **Complete** | **Verified Working** (Unit tested) | [EditorScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt), [MarkdownCodec.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/util/MarkdownCodec.kt) |
| **Version History** | Automated snapshots, rollback, revision diffs | **Complete** | **Verified Working** (Room transaction verified) | [VersionHistorySheet.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/editor/VersionHistorySheet.kt), [NoteVersionDao.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/NoteVersionDao.kt) |
| **Document Ingestion** | Import PDF, DOCX, XLSX, PPTX, HTML, Markdown | **Complete** | **Verified Working** (Unit tested) | [DocumentConverterFactory.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/docimport/DocumentConverterFactory.kt), [ImportPipelineCoordinator.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/agent/ImportPipelineCoordinator.kt) |
| **Camera & Document OCR** | CameraX preview, live bounding boxes, Latin/CJK OCR | **Complete** | **Verified Working** (Physical device verified) | [OcrScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt), [MlKitOcrEngine.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ocr/MlKitOcrEngine.kt) |
| **Speech-to-Text** | 16kHz PCM audio capture, Sherpa-ONNX streaming | **Complete** | **Verified Working** (Unit tested) | [VoiceCaptureOverlay.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/voice/VoiceCaptureOverlay.kt), [SherpaOnnxSttEngine.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/stt/SherpaOnnxSttEngine.kt) |
| **On-Device LLM** | Summarize, proofread, auto-tag via LiteRT-LM | **Complete** | **Verified Working** (Unit tested + device verified) | [LiteRtLmService.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ai/LiteRtLmService.kt), [ThermalBudgetManager.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ai/ThermalBudgetManager.kt) |
| **Offline Persona Chat** | Multi-turn chat (General, Engineer, Analyst, etc.) | **Complete** | **Verified Working** (Room v18 encrypted persistence) | [LocalChatScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/localchat/LocalChatScreen.kt), [LocalChatStorage.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/LocalChatStorage.kt) |
| **RAG Q&A Engine** | "Talk with your Notes", reciprocal rank fusion, citations | **Complete** | **Verified Working** (Unit tested) | [AskNotesScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/ask/AskNotesScreen.kt), [ResearchCoordinator.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/agent/ResearchCoordinator.kt) |
| **Hybrid Vector Search** | FTS4 BM25 + USearch HNSW + E5 embeddings | **Complete** | **Verified Working** (Unit tested + C++ compiled) | [VectorSearch.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/search/VectorSearch.kt), [USearchNative.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/search/ann/USearchNative.kt) |
| **Knowledge Graph** | Wikilinks (`[[ ]]`), co-occurrence, vis.js WebView | **Complete** | **Verified Working** (Unit tested) | [GraphScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/graph/GraphScreen.kt), [GraphEngine.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/graph/GraphEngine.kt) |
| **Spaced Repetition** | SM-2 flashcard deck generation, flip animation | **Complete** | **Verified Working** (Unit tested) | [StudyScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/study/StudyScreen.kt), [SpacedRepetitionScheduler.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/study/SpacedRepetitionScheduler.kt) |
| **Task Management** | Action items extraction from notes/audio, completion | **Complete** | **Verified Working** (Reactive Room queries) | [TaskListScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/tasks/TaskListScreen.kt), [ActionItemDao.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/ActionItemDao.kt) |
| **Local Backup** | Password-protected AES-256-GCM ZIP export/import | **Complete** | **Verified Working** (Unit tested) | [LocalBackupService.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/backup/LocalBackupService.kt) |
| **Cloud Sync** | Google Drive REST API via OkHttp (no Firebase) | **Implemented** | **Implemented Not Verified** (OAuth setup required) | [DriveService.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/remote/DriveService.kt), [SyncQueueManager.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/sync/SyncQueueManager.kt) |
| **Biometric Lock** | BiometricPrompt authentication on app resume | **Complete** | **Verified Working** (Unit tested) | [BiometricAuthManager.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/security/BiometricAuthManager.kt), [MainActivity.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/MainActivity.kt) |

---

## 3. What is Complete vs. What Needs Next Action

### What is Fully Proven
1. **Repository Hygiene**: Root directory cleaned of all temporary logs and dumps; C++ submodule state cleanly pinned; `.gitignore` configured.
2. **Persistence & Security**: SQLCipher database schema v18 contains 24 tables with full hardware KeyStore encryption. Local chat threads are fully incorporated into the database and encrypted backup system.
3. **Core AI Inference**: LiteRT-LM runtime configuration for Gemma 4 and Multilingual E5 Small embeddings.
4. **Automated Test Coverage**: 54 test suites under `app/src/test/` passing cleanly; `app/src/androidTest/` established with SQLCipher and Compose UI smoke tests.

### What Needs Validation Next
1. **Google Drive End-to-End Sync**: Requires testing against a live Google Cloud Project OAuth Client ID with real Drive appDataFolder scopes.
2. **Long-Running Device Thermal Testing**: Run continuous 100+ prompt stress-tests on lower-tier Android devices (6GB RAM) to observe LiteRT-LM under thermal throttle.
3. **Automated CI/CD**: Hook `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` into GitHub Actions.

---

## 4. Handoff Document Index

* **[Architecture Guide](file:///h:/Work/OmniDocs/docs/handoff/architecture.md)**: Tri-layer mental model, data flows, and technical decisions.
* **[Build & Verification Record](file:///h:/Work/OmniDocs/docs/handoff/build-status.md)**: Exact tooling versions, executed commands, test outputs, and limitations.
* **[Developer Onboarding](file:///h:/Work/OmniDocs/docs/handoff/onboarding.md)**: Prerequisites, first commands, app launch flow, and key files to read first.
* **[Risk Register](file:///h:/Work/OmniDocs/docs/handoff/risk-register.md)**: Explicit severity-ranked risk matrix, evidence, mitigations, and owners.
* **[Technical Appendix](file:///h:/Work/OmniDocs/docs/handoff/technical-appendix.md)**: Exhaustive as-built engineering audit dossier.
