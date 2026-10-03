# OmniDocs

A privacy-first, offline-centric Android document and personal knowledge intelligence application with on-device LLM, neural OCR, offline speech recognition, hybrid semantic search, and encrypted backup.

---

## Current Status

| Feature Cluster | Implementation Status | Verification Evidence |
| :--- | :---: | :--- |
| **Tri-Mode Note Editor & Versioning** | **Complete** | Verified via unit tests (`MarkdownCodecTest`, `EditorTextMetricsTest`) |
| **Encrypted Local Persistence** | **Complete** | Room v18 (24 tables, including chats) + SQLCipher AES-256 |
| **On-Device LLM & Persona Chat** | **Complete** | Google LiteRT-LM (Gemma 4) + `ChatDao` Room persistence |
| **Camera & Document OCR** | **Complete** | CameraX + Google ML Kit Latin/CJK, verified on physical hardware |
| **Offline Speech-to-Text** | **Complete** | Sherpa-ONNX (Whisper / Moonshine), verified via unit tests |
| **Hybrid Semantic Search & ANN** | **Complete** | FTS4 BM25 + C++ USearch HNSW (NDK r27), verified via unit tests |
| **Encrypted Local Backup** | **Complete** | Password-protected AES-256-GCM ZIP export/import, verified via unit tests |
| **Google Drive Cloud Sync** | **Implemented** | REST API via OkHttp in code; live OAuth GCP client configuration required |

---

## Engineering Handoff Documentation

Full technical documentation and audit dossiers are maintained in [`docs/handoff/`](file:///h:/Work/OmniDocs/docs/handoff/):

* **[Handoff Overview](file:///h:/Work/OmniDocs/docs/handoff/overview.md)**: Executive summary, current feature matrix, and roadmap.
* **[Architecture Guide](file:///h:/Work/OmniDocs/docs/handoff/architecture.md)**: Tri-layer mental model, component boundaries, and Architectural Decision Records.
* **[Developer Onboarding](file:///h:/Work/OmniDocs/docs/handoff/onboarding.md)**: Workstation prerequisites, first setup commands, and step-by-step verification checklist.
* **[Build & Verification Status](file:///h:/Work/OmniDocs/docs/handoff/build-status.md)**: Exact SDK/NDK requirements, executed test commands, and hardware verification records.
* **[Risk Register](file:///h:/Work/OmniDocs/docs/handoff/risk-register.md)**: Severity-ranked risk tracking, affected components, and mitigations.
* **[Technical Appendix](file:///h:/Work/OmniDocs/docs/handoff/technical-appendix.md)**: Comprehensive as-built audit dossier.

---

## Key Features

- **Tri-Mode Note Editor**: Rich text (WebView contenteditable), raw Markdown, and rendered Preview modes with bidirectional conversion via Flexmark.
- **On-Device LLM**: Local neural reasoning powered by Google LiteRT-LM running Gemma 4 (E2B lightweight and E4B reasoning models) with zero telemetry.
- **Offline Speech-to-Text**: Real-time microphone capture and transcription using Sherpa-ONNX (Whisper Small, Whisper Large V3, Moonshine).
- **Neural OCR**: Multi-script text extraction (Latin, Chinese, Japanese, Korean) via Google ML Kit Text Recognition v2 with live bounding box overlays.
- **Hybrid Semantic Search**: FTS4 BM25 lexical search fused with USearch HNSW vector similarity over Multilingual E5 embeddings (with Reciprocal Rank Fusion and N-gram fallback).
- **Knowledge Graph**: Interactive visualization of inter-note wikilinks (`[[Note Title]]`), tag clusters, and contradiction detection via vis.js WebView.
- **Spaced Repetition (Study)**: SuperMemo-2 (SM-2) flashcard engine with 3D flip card review interface.
- **Encrypted Local Backup**: AES-256-GCM ZIP export/import with PBKDF2 passphrase protection.
- **Cloud Backup**: Optional cloud backup and synchronization to Google Drive via Google Sign-In and Drive REST API.
- **Biometric Security**: Hardware-backed biometric authentication (fingerprint/face) protecting app launch and SQLCipher AES-256 encrypted database.
- **Multi-Theme Support**: 9 customizable themes (Light, Dark, AMOLED, Sepia, Ocean, Forest, Lavender, System, Dynamic Material You).

---

## Tech Stack

- **Platform**: Android (Min SDK 26, Target/Compile SDK 35)
- **Language**: Kotlin 2.1.0 + Jetpack Compose (Material Design 3)
- **On-Device LLM**: Google LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android`)
- **Speech Recognition**: Sherpa-ONNX 1.13.5 (Whisper / Moonshine)
- **Vector Search**: USearch (C++17 HNSW via JNI) + SQLite FTS4
- **OCR**: Google ML Kit (Text Recognition v2) + CameraX
- **Database**: Room 2.6.1 + SQLCipher 4.5.3 (AES-256, Schema v18, 24 entities)
- **Dependency Injection**: Hilt 2.51.1
- **Native Toolchain**: Android NDK 27.0.12077973 + CMake 3.22.1

---

## Quickstart & Build

1. Clone the repository:
   ```bash
   git clone https://github.com/Archeon84/omnidocs.git
   cd omnidocs
   ```
2. Open in Android Studio (Ladybug 2024.2+ recommended).
3. Ensure Android SDK 35, NDK 27.0.12077973, and CMake 3.22.1 are installed.
4. Run unit tests:
   ```bash
   ./gradlew testDebugUnitTest
   ```
5. Build the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```

---

## License

MIT License
