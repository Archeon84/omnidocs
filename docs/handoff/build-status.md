# OmniDocs — Build & Verification Status

> **Document Role**: Formal audit record of build tooling, commands, test executions, and hardware validation results.  
> **Last Verified**: October 3, 2026

---

## 1. Tooling Prerequisites

| Component | Required Version | Verified Source | Notes |
| :--- | :--- | :--- | :--- |
| **Android SDK** | `compileSdk = 35`, `minSdk = 26`, `targetSdk = 34` | [app/build.gradle.kts](file:///h:/Work/OmniDocs/app/build.gradle.kts#L11-L15) | Standard Android 15 developer preview API |
| **Android NDK** | `27.0.12077973` (NDK r27) | [app/build.gradle.kts](file:///h:/Work/OmniDocs/app/build.gradle.kts#L18) | Required for C++17 USearch compilation |
| **CMake** | `3.22.1` | [app/build.gradle.kts](file:///h:/Work/OmniDocs/app/build.gradle.kts#L39) | Bundled in Android SDK CMake tools |
| **Gradle** | `8.6` | `gradle/wrapper/gradle-wrapper.properties` | Android Gradle Plugin (AGP) 8.2.2 |
| **Java JDK** | `JDK 21` (Temurin / Zulu / OpenJDK) | [app/build.gradle.kts](file:///h:/Work/OmniDocs/app/build.gradle.kts#L28-L31) | Required for Gradle 8.6 and AGP 8.2.2 |

---

## 2. Verification Outcomes

### 2.1 Unit Test Suite (`./gradlew testDebugUnitTest`)
* **Status**: **PASSED (100%)**
* **Scope**: 54 test suites in `app/src/test/java/com/omnidocs/app/`
* **Coverage Highlights**:
  * `MarkdownCodecTest.kt` & `EditorTextMetricsTest.kt`: Markdown $\leftrightarrow$ HTML conversion, read-time calculation.
  * `LocalChatStorageTest.kt`: Room v18 `ChatDao` CRUD operations, cascade deletions, message count tracking.
  * `LocalBackupEncryptionTest.kt`: AES-256-GCM ZIP export/import, PBKDF2 salt derivation, corrupt tag rejection.
  * `VectorSearchRankingTest.kt`: Reciprocal Rank Fusion ($k=60$) score fusion between lexical and vector matches.
  * `AiPromptAndOutputTest.kt` & `ThermalBudgetManagerTest.kt`: Prompt budget calculations, throttle backoff states.
  * `WelcomeViewModelTest.kt`: First-run carousel page state, model download status flows.
  * `DocumentConvertersTest.kt`: Multi-format document text extraction pipelines.

### 2.2 Native & Composite Build (`./gradlew assembleDebug`)
* **Status**: **BUILD SUCCESSFUL**
* **Scope**: CMake 3.22.1 compiling `usearch_jni.cpp` into `libusearch-android.so` under NDK r27 across all target ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`).
* **Artifact**: `app/build/outputs/apk/debug/app-debug.apk` successfully generated.

### 2.3 Physical Device Hardware Testing
* **Status**: **VERIFIED ON PHYSICAL DEVICE**
* **Verified Flows**:
  1. **Camera & Document OCR**: Tested on physical Android device with camera hardware. Verified CameraX real-time frame analysis, live green bounding box overlays, and Google ML Kit Latin/CJK character extraction into notes.
  2. **LiteRT Embedding Runtime**: Verified on-device loading and execution of `multilingual-e5-small-q8_0.gguf` under `com.google.ai.edge.litertlm.EmbeddingEngine` without falling back to N-gram hashing.

### 2.4 Android Instrumented Test Suite (`./gradlew connectedAndroidTest`)
* **Status**: **TESTS CREATED & READY FOR CI/DEVICE EXECUTION**
* **Suites in `app/src/androidTest/`**:
  * [NotesDatabaseInstrumentationTest.kt](file:///h:/Work/OmniDocs/app/src/androidTest/java/com/omnidocs/app/data/local/NotesDatabaseInstrumentationTest.kt): Tests SQLCipher database creation on Android runtime, Room schema v18, Note CRUD, Chat thread insertion, and foreign key cascade deletion.
  * [AppSmokeInstrumentationTest.kt](file:///h:/Work/OmniDocs/app/src/androidTest/java/com/omnidocs/app/ui/AppSmokeInstrumentationTest.kt): Verifies that Jetpack Compose runtime, typography, and Material 3 theme render cleanly on Android OS.

### 2.5 Automated CI/CD Pipeline (`.github/workflows/ci.yml`)
* **Status**: **CONFIGURED & ACTIVE**
* **Triggers**: Pushes & PRs to `main`, `release/**`, and manual `workflow_dispatch`.
* **Jobs**:
  1. `unit-tests`: JDK 21 + NDK r27, executes `./gradlew testDebugUnitTest`, publishes test reports.
  2. `assemble-debug`: Builds debug APK with native C++ `usearch-android` library, uploads `app-debug.apk` as downloadable build artifact.
  3. `lint`: Executes `./gradlew lintDebug`, publishes HTML/XML lint diagnostics.


---

## 3. Execution Limitations & Known Caveats

1. **Gradle Dependency Downloads on Restricted Networks**:
   Downloading Android Gradle Plugin dependencies from `dl.google.com` or JitPack requires unproxied HTTPS internet access. If running behind a corporate firewall, configure `gradle.properties` proxy settings.
2. **Model File Placement**:
   AI model files (`gemma-4-e2b.litertlm`, `multilingual-e5-small-q8_0.gguf`, `sherpa-onnx-whisper.tar.bz2`) are not bundled in the git repository to keep repository size small. They are downloaded on-demand by `ModelDownloadManager.kt` or pushed manually via `adb push`.
