# OmniDocs

A privacy-first, offline-centric Android document and personal knowledge intelligence application with on-device LLM, neural OCR, offline speech recognition, hybrid semantic search, and encrypted backup.

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

## Tech Stack

- **Platform**: Android (Min SDK 26, Target/Compile SDK 35)
- **Language**: Kotlin 2.1.0 + Jetpack Compose (Material Design 3)
- **On-Device LLM**: Google LiteRT-LM (`com.google.ai.edge.litertlm`)
- **Speech Recognition**: Sherpa-ONNX 1.13.5 (Whisper / Moonshine)
- **Vector Search**: USearch (C++17 HNSW via JNI) + FTS4
- **OCR**: Google ML Kit (Text Recognition v2)
- **Database**: Room 2.6.1 + SQLCipher 4.5.3 (AES-256)
- **Dependency Injection**: Hilt 2.51.1
- **Native Toolchain**: Android NDK 27.0.12077973 + CMake 3.22.1

## Project Structure

```
app/src/main/java/com/omnidocs/app/
├── agent/              # Multi-agent coordination (Research, Ingestion, Meeting)
├── ai/                 # LiteRT-LM runtime, prompt builders, model management
├── audit/              # Privacy audit logger
├── calendar/           # iCalendar (RFC 5545) export
├── data/               # Room database (22 entities, v17), DAOs, Drive REST, repositories
├── di/                 # Hilt dependency injection modules
├── docimport/          # Document converters (PDF, DOCX, DOC, XLSX, PPTX, HTML, MD, TXT)
├── domain/             # Core domain models
├── email/              # Meeting summary email draft service
├── export/             # Workspace ZIP packaging
├── graph/              # Knowledge graph engine and export
├── ocr/                # ML Kit OCR engine and CameraX overlays
├── search/             # Vector search, USearch JNI, embeddings, BM25
├── security/           # KeyStore AES-GCM, BiometricAuthManager, app lock
├── stt/                # Sherpa-ONNX speech-to-text engine
├── study/              # SM-2 spaced repetition scheduler
├── sync/               # Drive sync peer, conflict resolver, sync queue
├── ui/                 # Jetpack Compose screens, components, themes
└── util/               # HTML sanitizer, MarkdownCodec (Flexmark)
```

## Setup & Build

1. Clone the repository:
   ```bash
   git clone <repo-url>
   cd OmniDocs
   ```
2. Open in Android Studio (Koala / Ladybug or newer recommended).
3. Ensure Android SDK 35 and NDK 27.0.12077973 are installed.
4. Build the project:
   ```bash
   ./gradlew assembleDebug
   ```
5. Run unit tests:
   ```bash
   ./gradlew testDebugUnitTest
   ```

## License

MIT License
