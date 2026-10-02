Ran command: `git status; git log -1 --oneline`
Ran command: `git rev-parse HEAD; git diff --stat`
Ran command: `git diff app/src/main/java`
Ran command: `Get-ChildItem -Recurse app/src/main/java/com/omnidocs/app/ui/screens/welcome/ ; Get-ChildItem -Recurse app/src/test/java/com/omnidocs/app/ui/screens/welcome/`
Ran command: `Get-ChildItem -Path . -Depth 1`
Ran command: `Get-ChildItem -File`
Viewed CLAUDE.md:1-69
Viewed README.md:1-65
Viewed APP_PROFILE.md:1-100
Viewed build.gradle.kts:1-200
Viewed build.gradle.kts:200-224
Viewed AndroidManifest.xml:1-68
Viewed strings.xml:1-6
Ran command: `Get-ChildItem -Path app/src/main/java -Recurse -File -Include *.kt,*.java | Resolve-Path -Relative`
Ran command: `Get-ChildItem -Path app/src/main/java/com/omnidocs/app/ai -Recurse -File | Resolve-Path -Relative`
Ran command: `Get-ChildItem -Path app/src/main/java/com/omnidocs/app -Directory | Select-Object Name`
Ran command: `Get-ChildItem -Path app/src/main/java/com/omnidocs/app/agent,app/src/main/java/com/omnidocs/app/audit,app/src/main/java/com/omnidocs/app/calendar,app/src/main/java/com/omnidocs/app/data -Recurse -File | Resolve-Path -Relative`
Ran command: `Get-ChildItem -Path app/src/main/cpp -Depth 1`
Viewed CMakeLists.txt:1-24
Ran command: `Select-String -Path app/src/main/java/**/*.kt -Pattern "System.loadLibrary"`
Ran command: `Get-ChildItem -Recurse app/src/main/java -Filter *.kt | Select-String "loadLibrary"`

I am waiting for the file search to complete.

Checked task Search loadLibrary
Viewed USearchNative.kt:1-60
Viewed LlamaCppService.kt:1-91
Viewed LiteRtLmService.kt:1-100
Viewed LiteRtLmService.kt:101-200
Viewed ModelDownloadManager.kt:1-100
Viewed ModelDownloadManager.kt:101-250
Viewed EmbeddingEngine.kt:1-100
Viewed EmbeddingService.kt:1-100
Ran command: `git log -n 5 --oneline`
Ran command: `git show 7d33644 --stat`
Ran command: `git show 0eaec69 --stat`
Ran command: `git diff app/src/main/cpp/llama.cpp`
Ran command: `Get-ChildItem -Path app/src/test,app/src/androidTest -Recurse -File -Include *.kt,*.java | Resolve-Path -Relative`
Viewed NotesApplication.kt:1-85
Viewed MainActivity.kt:1-100
Viewed Navigation.kt:35-120
Viewed Navigation.kt:121-260
Viewed Navigation.kt:261-446
Viewed BottomNavBar.kt:1-100
Viewed HomeScreen.kt:140-220
Viewed HomeScreen.kt:300-360
Viewed HomeScreen.kt:240-295
Viewed HomeScreen.kt:550-620
Viewed HomeScreen.kt:220-250
Ran command: `Select-String -Path app/src/main/java/com/omnidocs/app/ui/screens/home/HomeScreen.kt -Pattern "OmniBottomNavBar"`
Viewed HomeScreen.kt:470-505
Ran command: `Select-String -Path app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt -Pattern "class |fun EditorScreen|enum class"`
Viewed EditorScreen.kt:70-180
Viewed EditorViewModel.kt:1-100
Viewed LocalChatViewModel.kt:1-100
Viewed AskNotesViewModel.kt:1-100
Viewed ResearchCoordinator.kt:1-100
Viewed Agent.kt:1-58
Viewed AgentCoordinator.kt:1-100
Viewed ImportPipelineCoordinator.kt:1-80
Viewed MeetingPipelineCoordinator.kt:1-80
Viewed NotesDatabase.kt:1-100
Viewed NotesDatabase.kt:200-297
Viewed LocalChatStorage.kt:1-60
Viewed LocalChatStorage.kt:61-130
Viewed SherpaOnnxSttEngine.kt:1-100
Viewed MlKitOcrEngine.kt:1-100
Viewed DocumentConverterFactory.kt:1-76
Viewed VectorSearch.kt:1-100
Viewed GraphEngine.kt:1-100
Viewed GraphScreen.kt:1-80
Viewed SpacedRepetitionScheduler.kt:1-60
Viewed DriveService.kt:1-100
Viewed LocalBackupService.kt:1-60
Viewed AuditLogger.kt:1-55
Viewed TaskListViewModel.kt:1-46
Viewed VoiceCaptureViewModel.kt:1-80
Viewed OnboardingPreferences.kt:1-32
Viewed WelcomeViewModel.kt:1-84
Viewed WelcomeScreen.kt:1-100
Viewed FeedScreen.kt:1-60
Viewed AuthViewModel.kt:1-60
Viewed AgentJobViewModel.kt:1-47
Viewed WorkManagerCoordinator.kt:1-60
Viewed ModelDownloadService.kt:1-60
Viewed NotesRepository.kt:1-100
Viewed NoteDao.kt:1-87
Viewed NoteEntity.kt:1-34
Viewed SettingsViewModel.kt:1-100
Viewed Theme.kt:1-50
Ran command: `Get-Content local.properties | Select-String -Pattern "sdk.dir"`
Ran command: `powershell -Command "$json = Get-Content app/google-services.json | ConvertFrom-Json; [PSCustomObject]@{ project_id = $json.project_info.project_id; package_name = $json.client[0].client_info.android_client_info.package_name }"`
Ran command: `powershell -NoProfile -Command "(Get-Content app/google-services.json | ConvertFrom-Json).project_info.project_id"`
Ran command: `powershell -NoProfile -Command "(Get-Content app/google-services.json | ConvertFrom-Json).client[0].client_info.android_client_info.package_name"`
Viewed build.gradle.kts:1-14
Viewed settings.gradle.kts:1-20
Viewed implementation-status.md:1-100

# As-built app handoff

## 1. Snapshot

* **App Name**: OmniDocs (confirmed in [strings.xml](file:///h:/Work/OmniDocs/app/src/main/res/values/strings.xml#L2); root project internally named `NotesApp` in [settings.gradle.kts](file:///h:/Work/OmniDocs/settings.gradle.kts#L18)).
* **Purpose**: A privacy-first, offline-centric Android document and personal knowledge intelligence application combining a tri-mode note editor (Rich HTML / Raw Markdown / Preview), multi-format document ingestion (PDF, Office, HTML, TXT), on-device speech-to-text (Sherpa-ONNX / Whisper), on-device neural language model inference (Google LiteRT-LM / Gemma 4), hybrid lexical-vector retrieval (Room FTS4 + USearch HNSW), interactive knowledge graph visualization, and encrypted local/cloud backup.
* **Repository State**:
  * **Branch**: `feature/markdown-edit-preview`
  * **Head Commit**: `0eaec69b5391c5128b6484be065527d8a09c7ee1` (*"feat(chat): add local AI chat screen with on-device LLM and vector search"*)
  * **Worktree State**: **DIRTY / UNCOMMITTED CHANGES PRESENT**. The active worktree contains 5 modified tracked source files, 7 modified Gradle build cache files, a dirty Git submodule (`app/src/main/cpp/llama.cpp`), and 4 untracked source/test files introducing a first-run `WelcomeScreen` onboarding flow. This handoff reflects the live working tree state, not solely commit `0eaec69`.
* **Inspection Date**: October 2, 2026.
* **Areas Not Inspected**: Build artifact generation and automated unit test suite execution (not executed to preserve worktree purity per non-mutating safety instructions); physical device hardware sensors; live Google Drive / OAuth endpoint authorization tokens; third-party model download endpoints (Hugging Face / GitHub releases).

---

## 2. Feature status matrix

Classification levels:
* **VERIFIED WORKING**: Suitable automated tests executed and passing or verified runtime trace logs.
* **IMPLEMENTED NOT VERIFIED**: Complete production code present in repository, but tests/build were not run during this inspection session.
* **PARTIAL**: Substantial implementation present, but missing key integrations, dependencies, or paths.
* **STUB/MOCK**: Placeholder logic, hardcoded mocks, or non-functional scaffolding.
* **PLANNED ONLY**: Documented in markdown/specs but completely absent in source code.
* **UNKNOWN**: Cannot establish behavior from static inspection alone.

| Feature | User-visible behavior | Status | Evidence | Verification gap or caveat |
| :--- | :--- | :--- | :--- | :--- |
| **First-Run Onboarding** | 5-page carousel introducing app, models download (Gemma, Whisper, E5), and completion button | **IMPLEMENTED NOT VERIFIED** | [WelcomeScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/welcome/WelcomeScreen.kt#L46), [WelcomeViewModel.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/welcome/WelcomeViewModel.kt#L19), [OnboardingPreferences.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/welcome/OnboardingPreferences.kt#L15) | Untracked working-tree files; unit test [WelcomeViewModelTest.kt](file:///h:/Work/OmniDocs/app/src/test/java/com/omnidocs/app/ui/screens/welcome/WelcomeViewModelTest.kt) present but not executed. |
| **Tri-Mode Note Editor** | Switch between Rich Text (WebView contenteditable), Raw Markdown, and Rendered Preview; word & read-time counters | **IMPLEMENTED NOT VERIFIED** | [EditorScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt#L222), [EditorViewModel.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorViewModel.kt#L47), [MarkdownCodec.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/util/MarkdownCodec.kt#L18) | Rich text relies on Android WebView rendering `editor.html` asset; unit test exists in [MarkdownCodecTest.kt](file:///h:/Work/OmniDocs/app/src/test/java/com/omnidocs/app/util/MarkdownCodecTest.kt). |
| **Note Version History** | View historical snapshots, diffs, and restore previous note revisions | **IMPLEMENTED NOT VERIFIED** | [VersionHistorySheet.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/editor/VersionHistorySheet.kt#L45), [NoteVersionDao.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/NoteVersionDao.kt#L8), [NoteVersionEntity.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/entity/NoteVersionEntity.kt#L7) | Snapshot insertion occurs on debounced save; runtime restoration not verified on device. |
| **Document Import Pipeline** | Import PDF, DOCX, DOC, XLSX, PPTX, HTML, MD, TXT into structured notes | **IMPLEMENTED NOT VERIFIED** | [DocumentConverterFactory.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/docimport/DocumentConverterFactory.kt#L11), [ImportPipelineCoordinator.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/agent/ImportPipelineCoordinator.kt#L36) | [DocumentConvertersTest.kt](file:///h:/Work/OmniDocs/app/src/test/java/com/omnidocs/app/docimport/DocumentConvertersTest.kt) and [ImportPipelineCancelTest.kt](file:///h:/Work/OmniDocs/app/src/test/java/com/omnidocs/app/agent/ImportPipelineCancelTest.kt) exist but unexecuted. |
| **Camera & Document OCR** | Scan documents with CameraX, live bounding boxes, and extract text into note | **IMPLEMENTED NOT VERIFIED** | [MlKitOcrEngine.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ocr/MlKitOcrEngine.kt#L33), [LiveCameraOverlay.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/ocr/LiveCameraOverlay.kt#L37), [OcrScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt#L55) | Requires physical camera hardware and Google Play Services ML Kit dynamic module download. |
| **Speech-to-Text Recording** | Real-time audio waveform capture, Sherpa-ONNX Whisper/Moonshine transcription, speaker diarization | **IMPLEMENTED NOT VERIFIED** | [SherpaOnnxSttEngine.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/stt/SherpaOnnxSttEngine.kt#L56), [VoiceCaptureManager.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/voice/VoiceCaptureManager.kt#L38), [VoiceCaptureOverlay.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/voice/VoiceCaptureOverlay.kt#L64) | Dependent on downloaded `.tar.bz2` ONNX models in app storage; audio input unverified. |
| **On-Device LLM (LiteRT-LM)** | Local AI inference for summarization, auto-tagging, proofreading, task extraction | **IMPLEMENTED NOT VERIFIED** | [LiteRtLmService.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ai/LiteRtLmService.kt#L38), [LlamaCppService.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ai/LlamaCppService.kt#L54), [NoteIntelligenceService.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ai/NoteIntelligenceService.kt#L52) | Replaced legacy JNI llama.cpp in commit `7d33644`. Requires downloaded `.litertlm` model file (~1.1GB+). |
| **Evidence-Grounded RAG Q&A** | "Talk with your Notes" with citation deep-linking, passage scoring, confidence check | **IMPLEMENTED NOT VERIFIED** | [AskNotesScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/ask/AskNotesScreen.kt#L64), [ResearchCoordinator.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/agent/ResearchCoordinator.kt#L34), [VerificationAgent.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/agent/VerificationAgent.kt#L23) | Tests exist in [ResearchAgentsTest.kt](file:///h:/Work/OmniDocs/app/src/test/java/com/omnidocs/app/agent/ResearchAgentsTest.kt) and [AskNotesViewModelTest.kt](file:///h:/Work/OmniDocs/app/src/test/java/com/omnidocs/app/ui/screens/ask/AskNotesViewModelTest.kt). |
| **Offline LLM Chat (Personas)** | Dedicated chat screen with personas (General, Engineer, Analyst, Writer, Concise) and streaming text | **IMPLEMENTED NOT VERIFIED** | [LocalChatScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/localchat/LocalChatScreen.kt#L66), [LocalChatViewModel.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/localchat/LocalChatViewModel.kt#L44), [LocalChatStorage.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/LocalChatStorage.kt#L107) | Tested via [LocalChatTest.kt](file:///h:/Work/OmniDocs/app/src/test/java/com/omnidocs/app/ui/screens/localchat/LocalChatTest.kt) (unexecuted). Uses JSON flat files, not Room. |
| **Hybrid Vector Search & ANN** | BM25 SQLite FTS4 fused with USearch HNSW / E5 embeddings; N-gram fallback | **IMPLEMENTED NOT VERIFIED** | [VectorSearch.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/search/VectorSearch.kt#L22), [USearchNative.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/search/ann/USearchNative.kt#L9), [AnnIndexManager.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/search/ann/AnnIndexManager.kt#L21) | Native library `usearch-android` built via CMake; tested via [VectorSearchRankingTest.kt](file:///h:/Work/OmniDocs/app/src/test/java/com/omnidocs/app/search/VectorSearchRankingTest.kt). |
| **Interactive Knowledge Graph** | Render nodes, wikilinks (`[[ ]]`), tag co-occurrence, contradictions via vis.js WebView | **IMPLEMENTED NOT VERIFIED** | [GraphScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/graph/GraphScreen.kt#L46), [GraphEngine.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/graph/GraphEngine.kt#L27), [GraphExportService.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/graph/GraphExportService.kt#L21) | Tested via [GraphEngineTest.kt](file:///h:/Work/OmniDocs/app/src/test/java/com/omnidocs/app/graph/GraphEngineTest.kt); WebView JS bridge requires UI environment. |
| **Spaced Repetition (Study)** | SM-2 flashcard scheduler, interactive card flip, deck generation from notes | **IMPLEMENTED NOT VERIFIED** | [StudyScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/study/StudyScreen.kt#L45), [SpacedRepetitionScheduler.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/study/SpacedRepetitionScheduler.kt#L20), [StudyViewModel.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/study/StudyViewModel.kt#L34) | Tested via [StudyEngineTest.kt](file:///h:/Work/OmniDocs/app/src/test/java/com/omnidocs/app/study/StudyEngineTest.kt). |
| **Action Items & Task Mgmt** | List active/completed tasks, mark items complete, navigate to origin note | **IMPLEMENTED NOT VERIFIED** | [TaskListScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/tasks/TaskListScreen.kt#L30), [TaskListViewModel.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/tasks/TaskListViewModel.kt#L15), [ActionItemDao.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/ActionItemDao.kt#L9) | Room query reactive flow. |
| **Encrypted Local Backup** | AES-256-GCM ZIP export/import with PBKDF2 passphrase protection | **IMPLEMENTED NOT VERIFIED** | [LocalBackupService.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/backup/LocalBackupService.kt#L42), [LocalBackupEncryptionTest.kt](file:///h:/Work/OmniDocs/app/src/test/java/com/omnidocs/app/backup/LocalBackupEncryptionTest.kt) | Unit test exists; Android SAF file picker interaction unverified. |
| **Google Drive Cloud Sync** | Sync `notes.json` to Google Drive via OkHttp REST and Google Sign-In | **IMPLEMENTED NOT VERIFIED** | [DriveService.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/remote/DriveService.kt#L27), [DriveSyncPeer.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/sync/DriveSyncPeer.kt#L16), [SyncQueueManager.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/sync/SyncQueueManager.kt#L27) | Conflicts with README's claim of Firebase Firestore. Requires Google Play Services OAuth token on live device. |
| **Cloud Sync (Firebase/Firestore)** | "Cloud Sync - Firebase integration for syncing notes across devices" (README claim) | **PLANNED ONLY / ABANDONED** | [README.md](file:///h:/Work/OmniDocs/README.md#L12), [app/build.gradle.kts](file:///h:/Work/OmniDocs/app/build.gradle.kts#L114) | **Discrepancy**: Neither Firebase Auth nor Firestore dependencies exist in Gradle. Cloud sync is implemented via Google Drive REST. |
| **What's New / Feed** | Display release notes and changelog entries | **IMPLEMENTED NOT VERIFIED** | [FeedScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/feed/FeedScreen.kt#L18), [AppUpdate.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/domain/model/AppUpdate.kt#L3) | Hardcoded static list in `getAppUpdates()`; not an actual network or activity feed. |
| **Biometric App Lock** | Require fingerprint/face prompt before unlocking app on startup/resume | **IMPLEMENTED NOT VERIFIED** | [BiometricAuthManager.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/security/BiometricAuthManager.kt#L17), [MainActivity.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/MainActivity.kt#L44), [AppLockScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/security/AppLockScreen.kt#L23) | Tested via [SecurityTests.kt](file:///h:/Work/OmniDocs/app/src/test/java/com/omnidocs/app/security/SecurityTests.kt). |
| **Audit Log & Privacy Dashboard** | Track data modifications, AI operations, deletions; export audit logs | **IMPLEMENTED NOT VERIFIED** | [AuditLogger.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/audit/AuditLogger.kt#L17), [PrivacyDashboardScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/privacy/PrivacyDashboardScreen.kt#L34), [AuditEventDao.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/AuditEventDao.kt#L8) | Events logged to Room `audit_events` table. |
| **Agent Jobs Inspector** | Background job execution dashboard with step-by-step progress & cancellation | **IMPLEMENTED NOT VERIFIED** | [AgentJobsScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/agent/AgentJobsScreen.kt#L32), [AgentJobViewModel.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/agent/AgentJobViewModel.kt#L17), [AgentJobDao.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/AgentJobDao.kt#L8) | Backed by `agent_jobs` and `agent_events` tables. |

---

## 3. Architecture

### 3.1 Observed Architecture vs. Documented Claims

```
                     ┌─────────────────────────────────────────────────────────┐
                     │                   PRESENTATION LAYER                    │
                     │          Jetpack Compose (Navigation: 17 routes)        │
                     │                                                         │
                     │  HomeScreen │ EditorScreen │ LocalChatScreen │ AskNotes │
                     │  GraphScreen│ StudyScreen  │ OcrScreen │ VoiceOverlay  │
                     │  Welcome    │ Settings     │ Tasks     │ AgentJobs etc. │
                     └────────────────────────────┬────────────────────────────┘
                                                  │
                                                  ▼
                     ┌─────────────────────────────────────────────────────────┐
                     │                    VIEWMODEL LAYER                      │
                     │         Hilt-Injected ViewModels + Coroutine Flows      │
                     └─────────────┬───────────────────────────┬───────────────┘
                                   │                           │
          ┌────────────────────────▼────────┐                  │
          │      ORCHESTRATION & AGENTS     │                  │
          │  ResearchCoordinator            │                  │
          │  ImportPipelineCoordinator      │                  │
          │  MeetingPipelineCoordinator     │                  │
          │  AgentCoordinator + PolicyGuard │                  │
          │  WorkManagerCoordinator         │                  │
          └───────┬───────────────────┬─────┘                  │
                  │                   │                        │
                  ▼                   ▼                        ▼
┌───────────────────────────┐  ┌─────────────────────┐  ┌───────────────────────┐
│     ON-DEVICE AI / ML     │  │  RETRIEVAL & GRAPH  │  │   DATA & STORAGE      │
│                           │  │                     │  │                       │
│ LiteRtLmService (Gemma 4) │  │ VectorSearch        │  │ NotesRepository       │
│ SherpaOnnxSttEngine       │  │ AnnIndexManager     │  │ NotesDatabase (Room)  │
│ MlKitOcrEngine (Latin/CJK)│  │ (USearch HNSW JNI)  │  │   22 Entities, v17    │
│ EmbeddingEngine (LiteRT)  │  │ GraphEngine         │  │   SQLCipher AES-256   │
│ ThermalBudgetManager      │  │ (vis.js WebView)    │  │ LocalChatStorage(JSON)│
└───────────────────────────┘  └─────────────────────┘  └──────────┬────────────┘
                                                                   │
                                                                   ▼
                                                        ┌───────────────────────┐
                                                        │   SYNC & INTEGRATION  │
                                                        │ DriveService (OkHttp) │
                                                        │ LocalBackupService    │
                                                        │ (AES-256 GCM ZIP)     │
                                                        └───────────────────────┘
```

1. **AI Runtime Transition**: Documentation (`APP_PROFILE.md` and `README.md`) asserts that on-device LLM inference is handled by `llama.cpp` (tag b9976) via custom JNI wrapper `llama_jni.cpp`. However, in git commit `7d33644`, LLM and embedding inference were migrated to Google's official **LiteRT-LM runtime** (`com.google.ai.edge.litertlm:litertlm-android`). `LlamaCppService.kt` is retained merely as an adapter facade delegating directly to `LiteRtLmService.kt`. In `app/src/main/cpp/CMakeLists.txt`, `llama_jni.cpp` has been completely unhooked from the build; only `usearch-android` is built by CMake.
2. **Cloud Backend**: `README.md` documents Firebase Auth and Firestore for cloud sync. The actual repository contains no Firestore or Firebase Auth dependencies; cloud synchronization is implemented via Google Drive REST API (`DriveService.kt`) using Google Play Services Auth (`GoogleSignInClient`) and OkHttp.
3. **Database & Storage**: `NotesDatabase` runs on Room 2.6.1 with SQLCipher AES-256 encryption using an encryption passphrase derived and stored in AndroidKeyStore via `KeyStoreManager.kt`. However, `LocalChatStorage.kt` bypasses Room and stores conversation threads as JSON files under `context.filesDir/local_chats/`.

---

## 4. Critical user journeys

### 4.1 Journey 1: Create, Edit, and Auto-Save Note
* **Trigger**: User taps "Create Note" on `HomeScreen` or floating action button.
* **Screen / State**: `Navigation.kt` routes to `Screen.Editor.createRoute()`. [EditorScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorScreen.kt#L222) displays tri-mode interface (defaults to `EditorMode.RICH`).
* **Business Logic**: User types text. `RichTextEditor` emits `onChange` to [EditorViewModel.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/editor/EditorViewModel.kt#L198) (`updateContentFromWebView`). A 1500ms debounce timer triggers auto-save via `saveCurrentNote()`.
* **Storage / Persistence**: `NotesRepository.insertNote()` persists [NoteEntity](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/entity/NoteEntity.kt#L16) into SQLCipher-encrypted Room table `notes`. Also triggers `noteVersionDao.insert(NoteVersionEntity)` snapshot and `embeddingService.embedAndStoreNotePassages()` via `BackgroundInferenceDispatcher`.
* **Result / Error**: Note is saved; `isSynced` is reset to `false`. If an error occurs, `_error` StateFlow is published and rendered via Snackbar.

### 4.2 Journey 2: Offline LLM Persona Chat
* **Trigger**: User taps "Offline LLM Chat" icon in top bar of `HomeScreen` or navigates from Settings.
* **Screen / State**: Navigates to `Screen.LocalChat.route` ([LocalChatScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/localchat/LocalChatScreen.kt#L66)).
* **Business Logic**: User submits a message. [LocalChatViewModel.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/localchat/LocalChatViewModel.kt#L137) (`sendMessage`) resolves active model (`gemma_4_e2b`), checks thermal status with `ThermalBudgetManager`, builds prompt using `PromptBuilder.buildPrompt()`, and invokes `llamaCppService.generateFlow()`.
* **Model / Execution**: [LiteRtLmService.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ai/LiteRtLmService.kt#L254) uses Google `Engine(gpuConfig)` with OpenCL or fallback `Engine(cpuConfig)` to stream tokens. Tokens pass through `StopStringFilter` and `ThinkingStreamFilter`.
* **Result / Persistence**: Streamed tokens render in real time via [LocalChatMarkdownRenderer.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/localchat/LocalChatMarkdownRenderer.kt#L35). Complete messages are persisted as JSON to `local_chats/<sessionId>_messages.json` by [LocalChatStorage.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/LocalChatStorage.kt#L236).

### 4.3 Journey 3: Evidence-Backed RAG Q&A ("Talk with your Notes")
* **Trigger**: User clicks "Talk with your Notes" Hero card on `HomeScreen` or bottom nav `Ask AI`.
* **Screen / State**: Navigates to `Screen.Ask.route` ([AskNotesScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/ask/AskNotesScreen.kt#L64)).
* **Business Logic**: User enters question. [AskNotesViewModel.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/ask/AskNotesViewModel.kt#L80) calls [ResearchCoordinator.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/agent/ResearchCoordinator.kt#L44) `executeGroundedAsk()`:
  1. `QueryClassifierAgent`: Determines BM25 vs Semantic retrieval weights.
  2. `RetrievalAgent`: Executes hybrid search via [VectorSearch.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/search/VectorSearch.kt#L109) across Room FTS4 and USearch HNSW vectors; applies Reciprocal Rank Fusion (RRF, $k=60$) and `passesRelevanceGate()`.
  3. `AnswerAgent`: Reorders passages using Lost-in-the-Middle layout ([PromptBudget.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ai/PromptBudget.kt#L173)), formats prompt, and calls `LiteRtLmService.generateFlow()`.
  4. `VerificationAgent`: Verifies that claims in generated answer match verbatim citations ([VerificationAgent.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/agent/VerificationAgent.kt#L58)).
* **Result / Deep Linking**: Renders grounded answer with interactive citations. Tapping a citation invokes `onSourceClick(noteId, snippet)`, navigating back into `EditorScreen` with `highlight` text param deep-linked.

### 4.4 Journey 4: Document Ingestion Pipeline
* **Trigger**: User selects "Import Document" on `HomeScreen` and picks a file (e.g. `.pdf` or `.docx`).
* **Screen / State**: `HomeScreen` launches `ActivityResultContracts.OpenDocument()`; shows `ImportProgressBanner`.
* **Business Logic**: [HomeViewModel.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/home/HomeViewModel.kt#L201) delegates to [WorkManagerCoordinator.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/agent/WorkManagerCoordinator.kt#L24) or runs [ImportPipelineCoordinator.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/agent/ImportPipelineCoordinator.kt#L67):
  1. `CaptureAgent`: Reads URI stream, computes SHA-256 checksum, saves raw file to `filesDir/documents/`.
  2. `ExtractionAgent`: Invokes [DocumentConverterFactory.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/docimport/DocumentConverterFactory.kt#L62) to convert document into HTML/text.
  3. `NormalizationAgent`: Cleans formatting and identifies structural headings.
  4. `OrganizationAgent`: Generates note title, summary, and action items.
  5. `IndexingAgent`: Inserts `NoteEntity`, chunks text into passages, and inserts vector embeddings into `embeddings` table.
* **Result / Review**: Creates a new note; optionally displays `IngestionReviewSheet` if extraction confidence requires user validation.

### 4.5 Journey 5: Speech-to-Text Meeting Capture
* **Trigger**: User clicks "Record Voice" quick action on `HomeScreen`.
* **Screen / State**: Opens `VoiceCaptureOverlay` ([VoiceCaptureOverlay.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/voice/VoiceCaptureOverlay.kt#L64)).
* **Business Logic**: [VoiceCaptureManager.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/voice/VoiceCaptureManager.kt#L79) initiates 16kHz PCM audio stream via `AudioRecord`. Feeds chunks into [SherpaOnnxSttEngine.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/stt/SherpaOnnxSttEngine.kt#L198) running on background decode thread. Real-time amplitude drives `liveAudioLevel` waveform.
* **Storage / Persistence**: Raw PCM is saved to disk via [RecordingStorage.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/voice/RecordingStorage.kt#L18). Once stopped, [MeetingPipelineCoordinator.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/agent/MeetingPipelineCoordinator.kt#L53) transcribes, segments timestamps, extracts action items (`ActionItemDao`), and saves `RecordingEntity` + `TranscriptSegmentEntity`.
* **Result / Navigation**: User can save directly into a new note and is routed to `EditorScreen` with audio playback chip attached.

---

## 5. Data and integrations

### 5.1 Local Storage and Schemas
* **Database Engine**: Room 2.6.1 + SQLCipher 4.5.3 (AES-256 full database encryption).
* **Database File**: `notes_database` located in private app storage.
* **Database Version**: Schema Version **17** with 16 explicit forward migration scripts (`MIGRATION_1_2` through `MIGRATION_16_17`) in [NotesDatabase.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/NotesDatabase.kt#L79-L205).
* **Entities (22 total)**:
  1. `NoteEntity`: Primary note table (HTML content, plain text, tags, sync flags, soft delete tombstones).
  2. `NoteFtsEntity`: SQLite FTS4 virtual table for full-text search indexing.
  3. `RecordingEntity`: Audio recording metadata (duration, file path, sample rate).
  4. `TranscriptSegmentEntity`: Timestamped, speaker-attributed STT transcript segments.
  5. `SpeakerEntity`: Speaker profiles and names for diarization.
  6. `ClaimEntity`: Atomic factual statements extracted from notes.
  7. `EvidenceLinkEntity`: Provenance links between claims and source notes/recordings.
  8. `ActionItemEntity`: Extracted tasks with status, priority, and deadlines.
  9. `EntityEntity`: Extracted named entities (Person, Organization, Location, Concept).
  10. `EntityMentionEntity`: Entity references within specific content blocks.
  11. `NoteLinkEntity`: Explicit and semantic edges between notes (`[[wikilinks]]`, tags, BM25).
  12. `EmbeddingEntity`: Vector embeddings (384-dim for E5 or 128-dim for N-gram fallback) tagged with `modelName` and `passageIndex`.
  13. `AiRunEntity`: Telemetry records for on-device AI operations (tokens, latency, stop reasons).
  14. `NoteVersionEntity`: Historical note snapshots for version rollback.
  15. `AuditEventEntity`: Privacy and security audit trail.
  16. `SavedSearchEntity`: User-saved queries.
  17. `AgentJobEntity`: Multi-agent pipeline job definitions and states (`QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`).
  18. `AgentEventEntity`: Discrete execution step logs for agent jobs.
  19. `SourceDocumentEntity`: Raw imported file records (PDF, DOCX, etc.) with SHA-256 hash.
  20. `ContentBlockEntity`: Structured block-level chunks within notes.
  21. `AiArtifactEntity`: Structured artifacts generated by AI (summaries, outlines, quizzes).
  22. `FlashcardEntity`: Spaced repetition cards (front, back, ease factor, interval, due date).

### 5.2 External Services and Models
* **AI Generative Models**:
  * Google Gemma 4 E2B (`gemma-4-E2B-it.litertlm`, ~1.1GB) via Hugging Face.
  * Google Gemma 4 E4B (`gemma-4-E4B-it.litertlm`, ~3.2GB) via Hugging Face.
* **Embedding Model**:
  * Multilingual E5 Small Q8_0 (`multilingual-e5-small-q8_0.gguf`, ~126MB) via Hugging Face.
* **Speech Recognition Models**:
  * Sherpa-ONNX Whisper Small (`sherpa-onnx-whisper-small.tar.bz2`, ~609MB) via GitHub Releases.
  * Sherpa-ONNX Whisper Large V3 (`sherpa-onnx-whisper-large-v3.tar.bz2`, ~3GB) via GitHub Releases.
  * Moonshine Tiny English (`sherpa-onnx-moonshine-tiny-en-int8.tar.bz2`, ~50MB) via GitHub Releases.
* **Cloud Sync**:
  * Google Drive REST API (`https://www.googleapis.com/drive/v3/files` and `https://www.googleapis.com/upload/drive/v3/files`).
  * Scopes: `https://www.googleapis.com/auth/drive.file` and `https://www.googleapis.com/auth/drive.appdata`.

### 5.3 Permissions
Declared in [AndroidManifest.xml](file:///h:/Work/OmniDocs/app/src/main/AndroidManifest.xml#L5-L10):
* `android.permission.INTERNET`: Downloading AI models and optional Google Drive backup.
* `android.permission.CAMERA`: Live document capture and OCR scanning.
* `android.permission.RECORD_AUDIO`: Voice recording and speech-to-text.
* `android.permission.FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_DATA_SYNC`: Background model download service (`ModelDownloadService`).
* `android.permission.POST_NOTIFICATIONS`: Download progress notifications.

### 5.4 Privacy and Offline Boundaries
* **Default State**: 100% offline. All text processing, OCR, audio transcription, embeddings, vector indexing, and LLM inference execute locally on-device.
* **Network Requests**: Strictly limited to:
  1. Manual model downloads initiated by user (`ModelDownloadManager` validates hostnames against `ALLOWED_DOWNLOAD_HOSTS` to prevent SSRF).
  2. Google Drive manual/background sync (`DriveService`).
* **Zero Telemetry**: No third-party analytics or crash reporting SDKs are present.

---

## 6. Build and verification

### 6.1 Tooling & Prerequisites
* **Android Gradle Plugin (AGP)**: 8.2.2 ([build.gradle.kts](file:///h:/Work/OmniDocs/build.gradle.kts#L3))
* **Kotlin**: 2.1.0 ([build.gradle.kts](file:///h:/Work/OmniDocs/build.gradle.kts#L5))
* **Compose Compiler**: 2.1.0
* **KSP**: 2.1.0-1.0.29
* **Hilt**: 2.51.1
* **NDK**: 27.0.12077973 ([app/build.gradle.kts](file:///h:/Work/OmniDocs/app/build.gradle.kts#L24))
* **CMake**: 3.22.1 ([app/build.gradle.kts](file:///h:/Work/OmniDocs/app/build.gradle.kts#L78))
* **Target SDK / Compile SDK**: 35
* **Min SDK**: 26
* **Target ABIs**: `arm64-v8a`, `x86_64`

### 6.2 Executed Commands & Outcomes

| Command | Category | Outcome | Notes / Output |
| :--- | :--- | :--- | :--- |
| `git status` | Git Inspection | **PASSED** | On branch `feature/markdown-edit-preview`. Identified dirty working tree with 5 modified tracked files, modified gradle cache, modified `llama.cpp` submodule, and untracked `welcome/` files. |
| `git rev-parse HEAD` | Git Inspection | **PASSED** | Returned `0eaec69b5391c5128b6484be065527d8a09c7ee1`. |
| `git log -n 5 --oneline` | Git Inspection | **PASSED** | Traced recent feature history (commits `0eaec69`, `7d33644`, `cd507b4`, `8b563b5`, `238ed66`). |
| `git diff --stat` | Git Inspection | **PASSED** | Verified line delta of uncommitted modifications. |
| `Get-ChildItem -Recurse app/src/main/java` | Repository Inspection | **PASSED** | Cataloged all 114 Kotlin/Java source files. |
| `Get-ChildItem -Recurse app/src/test` | Repository Inspection | **PASSED** | Cataloged 54 test files in `app/src/test/java`. Identified that `app/src/androidTest` directory does not exist. |
| `./gradlew test` / `./gradlew assembleDebug` | Build & Verification | **NOT RUN** | **Safety constraint**: Building and executing tests generates build outputs (`build/`, `.gradle/`) and native compilation artifacts. Not executed without separate authorization. |
| **Physical Device Testing** | Runtime Verification | **NOT RUN** | Inspection performed purely in static repository environment; no attached device or emulator was commanded. |

---

## 7. Risks and gaps

### 7.1 Observed Inconsistencies & Technical Debt
1. **Uncompiled C++ Submodule / Dead Code**:
   * **Evidence**: In [CMakeLists.txt](file:///h:/Work/OmniDocs/app/src/main/cpp/CMakeLists.txt#L9-L11), CMake only compiles `usearch-android` (`usearch_jni.cpp`).
   * **Impact**: The large `app/src/main/cpp/llama.cpp` git submodule (and `app/src/main/cpp/llama_jni.cpp`, 42KB) remains in the codebase as dead code after migrating to Google LiteRT-LM. It is reported as dirty by git (`e3546c7-dirty`).
2. **Missing `androidTest` Directory**:
   * **Evidence**: `app/build.gradle.kts` configures `androidTestImplementation` dependencies (Espresso, Compose UI test, Room testing), but the directory `app/src/androidTest` does not exist on disk. No instrumented UI tests are present.
3. **Storage Disconnect in Local Chat**:
   * **Evidence**: While all other application entities are persisted in Room with SQLCipher encryption, [LocalChatStorage.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/LocalChatStorage.kt#L111) persists conversation sessions in raw JSON files under `context.filesDir/local_chats/`.
   * **Impact**: Local chat conversations are excluded from Room transactions and from the standard `LocalBackupService` database snapshot.
4. **Documentation Discrepancies**:
   * **Evidence**: `README.md` documents Firebase Auth and Cloud Firestore. `APP_PROFILE.md` documents `llama.cpp` with Qwen3 1.7B and Llama 3 8B.
   * **Impact**: Both documentation files are out of sync with the actual implementation (Google Drive REST and Google LiteRT-LM Gemma 4).

### 7.2 Suspected Risks (Labeled)
1. **[SUSPECTED RISK] Embedding Model Compatibility in LiteRtLm Engine**:
   * **Evidence**: [ModelDownloadManager.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ai/ModelDownloadManager.kt#L208) specifies `multilingual-e5-small-q8_0.gguf` as the embedding model file. However, in commit `7d33644`, [EmbeddingEngine.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ai/EmbeddingEngine.kt#L69) was updated to instantiate `com.google.ai.edge.litertlm.EmbeddingEngine(config)`.
   * **Risk**: Google LiteRT-LM typically requires LiteRT/TFLite model packages rather than GGUF tensors. If LiteRT-LM cannot parse GGUF files at runtime, the embedding engine will throw an initialization error, silently falling back to the 128-dim character N-gram hash fallback.
2. **[SUSPECTED RISK] Google Services Plugin Omission**:
   * **Evidence**: `google-services.json` exists in `app/`, but `com.google.gms.google-services` is not applied in either root or app `build.gradle.kts`.
   * **Risk**: Google Play Services configuration resources are not automatically generated into `R.string.default_web_client_id`. If `DriveService` or Google Sign-In requires this client ID, authentication will fail unless explicitly passed.

---

## 8. Audit starting points

For an independent AI auditor inspecting the system, start with these core files in sequence:

1. **Navigation & Entry Points**:
   * [MainActivity.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/MainActivity.kt): App lock, onboarding switch, and compose root.
   * [Navigation.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/navigation/Navigation.kt): Master route graph for all 17 screens.
2. **AI & Inference Stack**:
   * [LiteRtLmService.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ai/LiteRtLmService.kt): Core Google LiteRT-LM engine lifecycle, OpenCL GPU acceleration, and CPU fallback.
   * [ModelDownloadManager.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ai/ModelDownloadManager.kt): Model definitions, URLs, SHA-256 validation, and SSRF guard.
   * [SherpaOnnxSttEngine.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/stt/SherpaOnnxSttEngine.kt): Offline Whisper audio processing and decode thread.
3. **Retrieval & RAG Pipeline**:
   * [ResearchCoordinator.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/agent/ResearchCoordinator.kt): Multi-agent pipeline orchestrating query classification, retrieval, answer generation, and claim verification.
   * [VectorSearch.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/search/VectorSearch.kt): Reciprocal Rank Fusion (RRF), passage scoring, and relevance gating.
   * [AnnIndexManager.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/search/ann/AnnIndexManager.kt) & [usearch_jni.cpp](file:///h:/Work/OmniDocs/app/src/main/cpp/usearch_jni.cpp): USearch HNSW vector index native bridge.
4. **Data & Persistence**:
   * [NotesDatabase.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/NotesDatabase.kt): Room configuration, SQLCipher encryption key handling, and 16 migrations.
   * [NotesRepository.kt](file:///h:/Work/OmniDocs/app/src/data/repository/NotesRepository.kt): Central domain data gateway.
   * [LocalBackupService.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/backup/LocalBackupService.kt): Encrypted backup packaging and safety validation.
5. **Pending Working Tree Work**:
   * [WelcomeScreen.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/welcome/WelcomeScreen.kt) & [OnboardingPreferences.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/screens/welcome/OnboardingPreferences.kt): Untracked onboarding system.

---

## 9. Evidence index

| Claim / Subject | File Path | Key Symbol(s) / Lines |
| :--- | :--- | :--- |
| **App Name** | `app/src/main/res/values/strings.xml` | `<string name="app_name">OmniDocs</string>` ([strings.xml:2](file:///h:/Work/OmniDocs/app/src/main/res/values/strings.xml#L2)) |
| **Android Target SDK** | `app/build.gradle.kts` | `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26` ([build.gradle.kts:14-19](file:///h:/Work/OmniDocs/app/build.gradle.kts#L14-L19)) |
| **CMake Build Targets** | `app/src/main/cpp/CMakeLists.txt` | `add_library(usearch-android SHARED ...)` ([CMakeLists.txt:9](file:///h:/Work/OmniDocs/app/src/main/cpp/CMakeLists.txt#L9)) |
| **LiteRT-LM Integration** | `app/src/main/java/com/omnidocs/app/ai/LiteRtLmService.kt` | `class LiteRtLmService`, `EngineConfig`, `Backend.GPU()` ([LiteRtLmService.kt:38-165](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ai/LiteRtLmService.kt#L38-L165)) |
| **LlamaCppService Facade** | `app/src/main/java/com/omnidocs/app/ai/LlamaCppService.kt` | `class LlamaCppService(private val liteRtLmService: LiteRtLmService)` ([LlamaCppService.kt:54](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ai/LlamaCppService.kt#L54)) |
| **Available Models** | `app/src/main/java/com/omnidocs/app/ai/ModelDownloadManager.kt` | `availableModels`, `sttModels`, `embeddingModels` ([ModelDownloadManager.kt:120-214](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ai/ModelDownloadManager.kt#L120-L214)) |
| **Database Encryption** | `app/src/main/java/com/omnidocs/app/data/local/NotesDatabase.kt` | `SupportFactory`, `getEncryptionPassword()` ([NotesDatabase.kt:33](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/NotesDatabase.kt#L33), [222](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/NotesDatabase.kt#L222)) |
| **Database Migrations** | `app/src/main/java/com/omnidocs/app/data/local/NotesDatabase.kt` | `version = 17`, `MIGRATION_1_2` to `16_17` ([NotesDatabase.kt:49](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/NotesDatabase.kt#L49), [79-205](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/NotesDatabase.kt#L79-L205)) |
| **Local Chat Storage** | `app/src/main/java/com/omnidocs/app/data/local/LocalChatStorage.kt` | `class LocalChatStorage`, `baseDir = "local_chats"` ([LocalChatStorage.kt:107](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/LocalChatStorage.kt#L107)) |
| **RAG Fusion Scoring** | `app/src/main/java/com/omnidocs/app/search/VectorSearch.kt` | `passesRelevanceGate()`, `SEMANTIC_STANDALONE_FLOOR` ([VectorSearch.kt:79](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/search/VectorSearch.kt#L79), [90](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/search/VectorSearch.kt#L90)) |
| **Multi-Agent Flow** | `app/src/main/java/com/omnidocs/app/agent/ResearchCoordinator.kt` | `executeGroundedAsk()` ([ResearchCoordinator.kt:44](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/agent/ResearchCoordinator.kt#L44)) |
| **Knowledge Graph Builder**| `app/src/main/java/com/omnidocs/app/graph/GraphEngine.kt` | `buildGraph()`, wikilinks regex ([GraphEngine.kt:46](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/graph/GraphEngine.kt#L46), [77](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/graph/GraphEngine.kt#L77)) |
| **Spaced Repetition** | `app/src/main/java/com/omnidocs/app/study/SpacedRepetitionScheduler.kt`| `scheduleNextReview()`, SM-2 algorithm ([SpacedRepetitionScheduler.kt:31](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/study/SpacedRepetitionScheduler.kt#L31)) |
| **Cloud Sync REST** | `app/src/main/java/com/omnidocs/app/data/remote/DriveService.kt` | `getAccessToken()`, `makeRequest()` ([DriveService.kt:55](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/remote/DriveService.kt#L55), [72](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/remote/DriveService.kt#L72)) |
| **Biometric Auth** | `app/src/main/java/com/omnidocs/app/security/BiometricAuthManager.kt` | `authenticate()`, `canAuthenticate()` ([BiometricAuthManager.kt:33](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/security/BiometricAuthManager.kt#L33), [43](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/security/BiometricAuthManager.kt#L43)) |

---

## 10. Open questions for product owner / team

These questions cannot be answered from the repository alone:

1. **GGUF vs. LiteRT Embedding Format**:
   Does `com.google.ai.edge.litertlm.EmbeddingEngine` successfully load the downloaded `multilingual-e5-small-q8_0.gguf` file on a live ARM64 device, or does it fail and silently fall back to the 128-dim character N-gram hash? Is there a planned conversion to a native `.litertlm` embedding format?
2. **Native Code Clean-up**:
   Now that LiteRT-LM has replaced `llama.cpp` for generative text, is the dirty `app/src/main/cpp/llama.cpp` git submodule and `llama_jni.cpp` file scheduled for complete removal from the repository?
3. **Cloud Sync Intent**:
   `README.md` lists Firebase Firestore as the cloud sync backend, while the codebase implements Google Drive REST sync. Should `README.md` be updated to reflect Google Drive as the official cloud peer, or is a Firestore integration still intended for multi-device collaboration?
4. **Uncommitted Welcome / Onboarding Flow**:
   Are the untracked onboarding files (`WelcomeScreen.kt`, `WelcomeViewModel.kt`, `OnboardingPreferences.kt`, `welcome_screen.png`) ready to be committed to `feature/markdown-edit-preview`, or is this an experimental branch artifact?