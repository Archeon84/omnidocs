# OmniDocs — Developer Onboarding Guide

> **Document Role**: First-day walkthrough and verification checklist for incoming engineers.  
> **Estimated Ramp-Up Time**: 1–2 hours from clone to running on device/emulator.

---

## 1. Environment Prerequisites

Before opening the project, ensure your workstation has the following installed:

1. **Android Studio**: Android Studio Ladybug (2024.2+) or newer.
2. **Java Development Kit**: JDK 21 (Eclipse Temurin, Azul Zulu, or OpenJDK 21). Set `JAVA_HOME` to this JDK.
3. **Android SDK Components** (via Android Studio SDK Manager):
   * **SDK Platforms**: Android 15 (API 35) SDK platform.
   * **SDK Tools**:
     * Android SDK Build-Tools `35.0.0`
     * NDK (Side by side) `27.0.12077973` (NDK r27)
     * CMake `3.22.1`

---

## 2. First Setup Steps & Commands

### Step 1: Clone and Inspect
```bash
git clone https://github.com/Archeon84/omnidocs.git
cd omnidocs
git checkout main
```

### Step 2: Configure Local Environment
Ensure `local.properties` specifies your Android SDK directory:
```properties
sdk.dir=C:\\Users\\<YourUsername>\\AppData\\Local\\Android\\Sdk
# Or on macOS/Linux: sdk.dir=/Users/<YourUsername>/Library/Android/sdk
```

### Step 3: Run the Unit Test Suite
Verify that all 54 test suites pass in your environment:
```bash
./gradlew testDebugUnitTest
```

### Step 4: Build the Debug APK
Verify that the Android Gradle Plugin and CMake compile the native C++ libraries without error:
```bash
./gradlew assembleDebug
```
The output APK is generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 3. High-Value Files to Read First

To quickly build a solid mental model of the codebase, read these 7 files in order:

1. **[MainActivity.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/MainActivity.kt)**: Application entry point, biometric lock lifecycle handling, and top-level theme container.
2. **[Navigation.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ui/navigation/Navigation.kt)**: The complete Jetpack Compose navigation graph covering all 17 app destinations.
3. **[NotesDatabase.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/data/local/NotesDatabase.kt)**: The Room database definition (Schema v18, 24 entities), SQLCipher AES-256 encryption factory, and KeyStore key derivation.
4. **[LiteRtLmService.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ai/LiteRtLmService.kt)**: Google LiteRT-LM SDK generative LLM wrapper, token streaming, and stop-string filters.
5. **[ModelDownloadManager.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/ai/ModelDownloadManager.kt)**: Offline AI model discovery, file hashing, and background download management.
6. **[VectorSearch.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/search/VectorSearch.kt)**: Reciprocal Rank Fusion ($k=60$) search engine combining SQLite FTS4 BM25 and USearch HNSW.
7. **[ResearchCoordinator.kt](file:///h:/Work/OmniDocs/app/src/main/java/com/omnidocs/app/agent/ResearchCoordinator.kt)**: Multi-step RAG retrieval and citation grounding agent pipeline.

---

## 4. First-Week Validation Checklist

When testing on an Android device or emulator, execute this step-by-step verification checklist:

- [ ] **First-Run Onboarding Flow**:
  * Launch the app for the first time.
  * Verify the 5-page Welcome carousel displays smoothly.
  * Tap "Get Started" and ensure `OnboardingPreferences` marks onboarding complete, routing you to `HomeScreen`.
- [ ] **Note Creation & Tri-Mode Editor**:
  * Tap the `+` FAB to create a new note.
  * Toggle between **Rich Text**, **Markdown**, and **Preview** modes.
  * Type text and ensure auto-save triggers after 1500 ms debounce without freezing the UI.
- [ ] **Version History Snapshots**:
  * Make edits to a note, wait 2 seconds, and open Version History from the top bar.
  * Verify historical revision snapshots appear and can be viewed.
- [ ] **Camera & Document OCR**:
  * Open the OCR scanner screen (`OcrScreen`).
  * Point the camera at printed text and observe live green bounding boxes tracking text lines.
  * Tap "Insert to Note" and verify the recognized text populates a new note.
- [ ] **Offline LLM Chat (Personas)**:
  * Select a persona (General, Software Engineer, Deep Analyst).
  * Send a message and verify streaming token responses.
  * Verify conversations persist across app relaunches via Room `ChatDao`.
- [ ] **Encrypted Backup & Restore**:
  * Navigate to Settings $\rightarrow$ Export Backup.
  * Enter a password and save the `.zip` archive.
  * Delete a test note, then import the backup file with your password to confirm restoration.
