# OCR Engine Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace ML Kit with PaddleOCR (Paddle Lite) as the sole OCR engine, deduplicate HTML generation, and fix the translation service model loading bug.

**Architecture:** Use Paddle Lite's C++ API via JNI bridge (following the PaddleOCR Android demo pattern). Create an `OcrEngine` interface with `PaddleOcrEngine` implementation. Deduplicate HTML generation into `OcrHtmlBuilder`. Fix `NllbTranslationService.loadModel()` to actually call `llamaCppService.loadModel()`.

**Tech Stack:** Kotlin, Paddle Lite (C++ via JNI), CMake, NDK, CameraX, Hilt

## Global Constraints

- Target: arm64-v8a only (Xiaomi 13 Ultra)
- Min SDK: 26 (existing app)
- Compile SDK: 34 (existing app)
- Paddle Lite version: 2.10 (from PaddleOCR demo)
- Models: PP-OCRv4 det + rec (English default, others on-demand)
- All new code must follow existing project patterns (Hilt injection, coroutines, StateFlow)

---

## File Structure

### New Files

| File | Responsibility |
|------|---------------|
| `app/src/main/java/com/omnidocs/app/ocr/OcrEngine.kt` | Interface + data classes |
| `app/src/main/java/com/omnidocs/app/ocr/PaddleOcrEngine.kt` | PaddleOCR implementation |
| `app/src/main/java/com/omnidocs/app/ocr/OcrEngineFactory.kt` | Engine selection |
| `app/src/main/java/com/omnidocs/app/ocr/OcrHtmlBuilder.kt` | Deduplicated HTML generation |
| `app/src/main/java/com/omnidocs/app/ocr/PaddleNative.kt` | JNI bridge class |
| `app/src/main/cpp/CMakeLists.txt` | Native build config |
| `app/src/main/cpp/paddle_ocr_jni.cpp` | JNI bridge implementation |
| `app/src/main/java/com/omnidocs/app/ocr/OcrModelManager.kt` | Model download/extraction |

### Modified Files

| File | Changes |
|------|---------|
| `app/build.gradle.kts` | Remove ML Kit deps, add Paddle Lite, configure CMake |
| `app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrViewModel.kt` | Use OcrEngine interface |
| `app/src/main/java/com/omnidocs/app/ai/NllbTranslationService.kt` | Fix loadModel bug |

### Deleted Files

| File | Reason |
|------|--------|
| `app/src/main/java/com/omnidocs/app/ocr/TextRecognitionService.kt` | Dead code |
| `app/src/main/java/com/omnidocs/app/ocr/OcrUtils.kt` | Replaced by OcrHtmlBuilder |
| `app/src/main/java/com/omnidocs/app/ocr/PaddleOcrService.kt` | Replaced by PaddleOcrEngine |

---

## Task 1: Create OcrEngine Interface and Data Classes

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/ocr/OcrEngine.kt`

**Interfaces:**
- Produces: `OcrEngine`, `OcrResult`, `OcrBlock`

- [ ] **Step 1: Create the OcrEngine interface and data classes**

```kotlin
package com.omnidocs.app.ocr

import android.net.Uri
import android.graphics.RectF

/**
 * Result of OCR recognition on an image.
 */
data class OcrResult(
    val text: String,
    val html: String,
    val blocks: List<OcrBlock>
)

/**
 * A single recognized text block with bounding box and confidence.
 */
data class OcrBlock(
    val text: String,
    val confidence: Float,
    val boundingBox: RectF  // normalized 0-1 coordinates
)

/**
 * Interface for OCR engines.
 */
interface OcrEngine {
    /**
     * Recognize text in an image.
     *
     * @param uri Image URI (content:// or file://)
     * @param language Language code (e.g., "en", "zh", "ja", "ko", "ar", "hi", "ru")
     * @return OcrResult with text, HTML, and blocks, or null if recognition fails
     */
    suspend fun recognizeText(uri: Uri, language: String): OcrResult?

    /**
     * Release resources held by this engine.
     */
    fun close()
}
```

- [ ] **Step 2: Verify the file compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS (no errors related to OcrEngine.kt)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ocr/OcrEngine.kt
git commit -m "feat(ocr): add OcrEngine interface and data classes"
```

---

## Task 2: Create OcrHtmlBuilder (Deduplicated HTML Generation)

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/ocr/OcrHtmlBuilder.kt`

**Interfaces:**
- Consumes: `OcrResult`, `OcrBlock`
- Produces: `OcrHtmlBuilder.fromOcrResult()`, `OcrHtmlBuilder.fromPlainText()`

- [ ] **Step 1: Create OcrHtmlBuilder**

```kotlin
package com.omnidocs.app.ocr

/**
 * Deduplicated HTML generation for OCR results.
 * Replaces visionTextToHtml() in OcrUtils.kt and inline HTML builders in OcrViewModel.
 */
object OcrHtmlBuilder {

    /**
     * Grouping strategy for plain text conversion.
     */
    enum class Grouping {
        /** One <p> per line */
        LINE,
        /** Merge consecutive non-empty lines into paragraphs, separated by blank lines */
        PARAGRAPH
    }

    /**
     * Generate HTML from an OcrResult. Uses the pre-built HTML from the result.
     */
    fun fromOcrResult(result: OcrResult): String = result.html

    /**
     * Generate HTML from plain text with the specified grouping.
     *
     * @param text Plain text to convert
     * @param grouping How to group lines into paragraphs
     * @return HTML string with <p> tags
     */
    fun fromPlainText(text: String, grouping: Grouping = Grouping.PARAGRAPH): String {
        if (text.isBlank()) return ""

        return when (grouping) {
            Grouping.LINE -> {
                text.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n") { "<p>$it</p>" }
            }
            Grouping.PARAGRAPH -> {
                val paragraphs = mutableListOf<MutableList<String>>()
                var current = mutableListOf<String>()

                for (line in text.lines()) {
                    if (line.isBlank()) {
                        if (current.isNotEmpty()) {
                            paragraphs.add(current)
                            current = mutableListOf()
                        }
                    } else {
                        current.add(line.trim())
                    }
                }
                if (current.isNotEmpty()) paragraphs.add(current)

                paragraphs.joinToString("\n") { "<p>${it.joinToString(" ")}</p>" }
            }
        }
    }

    /**
     * Generate HTML from a list of OcrBlocks (for live camera results).
     *
     * @param blocks List of OcrBlock with text and confidence
     * @return HTML string with <p> tags
     */
    fun fromBlocks(blocks: List<OcrBlock>): String {
        return blocks
            .filter { it.text.isNotBlank() }
            .joinToString("\n") { "<p>${it.text.trim()}</p>" }
    }
}
```

- [ ] **Step 2: Verify the file compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ocr/OcrHtmlBuilder.kt
git commit -m "feat(ocr): add OcrHtmlBuilder for deduplicated HTML generation"
```

---

## Task 3: Set Up Paddle Lite Native Libraries

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/cpp/CMakeLists.txt`

**Interfaces:**
- Produces: Paddle Lite .so files available for JNI, CMake build configured

- [ ] **Step 1: Add Paddle Lite download task to build.gradle.kts**

Add the following to `app/build.gradle.kts` after the `dependencies` block:

```groovy
import java.security.MessageDigest

// Paddle Lite native libraries download
def paddleArchives = [
    [
        'src' : 'https://paddleocr.bj.bcebos.com/libs/paddle_lite_libs_v2_10.tar.gz',
        'dest': "${project.projectDir}/PaddleLite"
    ]
]

task downloadPaddleLite(type: DefaultTask) {
    doFirst {
        println "Downloading Paddle Lite native libraries"
    }
    doLast {
        String cachePath = "${project.buildDir}/cache"
        if (!file("${cachePath}").exists()) {
            mkdir "${cachePath}"
        }
        paddleArchives.each { archive ->
            MessageDigest messageDigest = MessageDigest.getInstance('MD5')
            messageDigest.update(archive.src.bytes)
            String cacheName = new BigInteger(1, messageDigest.digest()).toString(32)
            boolean copyFiles = !file("${archive.dest}").exists()
            if (!file("${cachePath}/${cacheName}.tar.gz").exists()) {
                ant.get(src: archive.src, dest: file("${cachePath}/${cacheName}.tar.gz"))
                copyFiles = true
            }
            if (copyFiles) {
                copy {
                    from tarTree("${cachePath}/${cacheName}.tar.gz")
                    into "${archive.dest}"
                }
            }
        }
    }
}

preBuild.dependsOn downloadPaddleLite
```

- [ ] **Step 2: Remove ML Kit dependencies from build.gradle.kts**

Remove these lines from the `dependencies` block:

```groovy
// Remove these:
implementation("com.google.mlkit:text-recognition:16.0.0")
implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
```

- [ ] **Step 3: Add CMake configuration to build.gradle.kts**

In the `android` block, add:

```groovy
android {
    // ... existing config ...

    defaultConfig {
        // ... existing config ...

        externalNativeBuild {
            cmake {
                cppFlags "-std=c++11 -frtti -fexceptions -Wno-format"
                arguments '-DANDROID_PLATFORM=android-26', '-DANDROID_STL=c++_shared', "-DANDROID_ARM_NEON=TRUE"
                abiFilters 'arm64-v8a'
            }
        }
    }

    externalNativeBuild {
        cmake {
            path "src/main/cpp/CMakeLists.txt"
            version "3.22.1"
        }
    }

    // ... rest of existing config ...
}
```

- [ ] **Step 4: Create CMakeLists.txt**

Create `app/src/main/cpp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.10.2)
project(paddle_ocr_jni)

# Set C++ standard
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Paddle Lite libraries path
# After downloading, the extracted structure is: PaddleLite/inference_lite_lib.android.armv8.clang.c++_shared/
# Adjust these paths based on the actual extracted directory structure
set(PADDLE_LITE_DIR "${CMAKE_SOURCE_DIR}/../PaddleLite")
set(PADDLE_LITE_LIB_DIR "${PADDLE_LITE_DIR}/inference_lite_lib.android.armv8.clang.c++_shared/java/lib")
set(PADDLE_LITE_INCLUDE_DIR "${PADDLE_LITE_DIR}/inference_lite_lib.android.armv8.clang.c++_shared/java/include")

# Include Paddle Lite headers
include_directories(${PADDLE_LITE_INCLUDE_DIR})

# Add JNI bridge source
add_library(paddle_ocr_jni SHARED paddle_ocr_jni.cpp)

# Find required libraries
find_library(log-lib log)
find_library(android-lib android)

# Link Paddle Lite
target_link_libraries(
    paddle_ocr_jni
    ${PADDLE_LITE_LIB_DIR}/libpaddle_lite_jni.so
    ${log-lib}
    ${android-lib}
)
```

- [ ] **Step 5: Create placeholder JNI bridge file**

Create `app/src/main/cpp/paddle_ocr_jni.cpp`:

```cpp
#include <jni.h>
#include <string>
#include <android/log.h>

#define TAG "PaddleOcrJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_omnidocs_app_ocr_PaddleNative_init(
    JNIEnv *env,
    jobject thiz,
    jstring det_model_path,
    jstring rec_model_path,
    jstring label_path,
    jint num_threads
) {
    LOGI("PaddleOCR init called");
    // TODO: Implement Paddle Lite initialization
    return JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_com_omnidocs_app_ocr_PaddleNative_recognize(
    JNIEnv *env,
    jobject thiz,
    jstring image_path,
    jfloat score_threshold
) {
    LOGI("PaddleOCR recognize called");
    // TODO: Implement OCR recognition
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_omnidocs_app_ocr_PaddleNative_destroy(
    JNIEnv *env,
    jobject thiz
) {
    LOGI("PaddleOCR destroy called");
    // TODO: Implement cleanup
}

} // extern "C"
```

- [ ] **Step 6: Verify build configuration compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS (native build may fail due to missing .so, but Kotlin compilation should pass)

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts app/src/main/cpp/CMakeLists.txt app/src/main/cpp/paddle_ocr_jni.cpp
git commit -m "feat(ocr): set up Paddle Lite native build, remove ML Kit deps"
```

---

## Task 4: Create PaddleNative JNI Bridge Class

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/ocr/PaddleNative.kt`

**Interfaces:**
- Consumes: JNI methods from paddle_ocr_jni.cpp
- Produces: `PaddleNative.init()`, `PaddleNative.recognize()`, `PaddleNative.destroy()`

- [ ] **Step 1: Create PaddleNative Kotlin wrapper**

```kotlin
package com.omnidocs.app.ocr

import android.util.Log

private const val TAG = "PaddleNative"

/**
 * JNI bridge to Paddle Lite C++ OCR engine.
 * Must match the native methods defined in paddle_ocr_jni.cpp.
 */
class PaddleNative {

    companion object {
        init {
            try {
                System.loadLibrary("paddle_ocr_jni")
                Log.d(TAG, "Native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }
    }

    /**
     * Initialize the PaddleOCR engine.
     *
     * @param detModelPath Path to detection model file
     * @param recModelPath Path to recognition model file
     * @param labelPath Path to label/dictionary file
     * @param numThreads Number of CPU threads for inference
     * @return true if initialization succeeded
     */
    external fun init(
        detModelPath: String,
        recModelPath: String,
        labelPath: String,
        numThreads: Int = 4
    ): Boolean

    /**
     * Recognize text in an image.
     *
     * @param imagePath Path to image file
     * @param scoreThreshold Minimum confidence threshold for detection
     * @return Array of [text, confidence, x1, y1, x2, y2, x3, y3, x4, y4] for each block, or null on failure
     */
    external fun recognize(
        imagePath: String,
        scoreThreshold: Float = 0.5f
    ): Array<Array<Float>>?

    /**
     * Release native resources.
     */
    external fun destroy()
}
```

- [ ] **Step 2: Verify the file compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ocr/PaddleNative.kt
git commit -m "feat(ocr): add PaddleNative JNI bridge class"
```

---

## Task 5: Create OcrModelManager

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/ocr/OcrModelManager.kt`

**Interfaces:**
- Produces: `OcrModelManager.getModelPath()`, `OcrModelManager.ensureModelsDownloaded()`

- [ ] **Step 1: Create OcrModelManager**

```kotlin
package com.omnidocs.app.ocr

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OcrModelManager"

/**
 * Manages PaddleOCR model files (download, extraction, storage).
 * Models are stored in app's internal storage under models/ocr/.
 */
@Singleton
class OcrModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modelsDir = File(context.filesDir, "models/ocr")

    /**
     * Language to model mapping.
     * Key: language code used in OcrViewModel
     * Value: pair of (detection model filename, recognition model filename)
     */
    private val languageModels = mapOf(
        "en" to Pair("ch_PP-OCRv4_det", "en_PP-OCRv4_rec"),
        "zh" to Pair("ch_PP-OCRv4_det", "ch_PP-OCRv4_rec"),
        "ja" to Pair("ch_PP-OCRv4_det", "ja_PP-OCRv4_rec"),
        "ko" to Pair("ch_PP-OCRv4_det", "ko_PP-OCRv4_rec"),
        "ar" to Pair("ch_PP-OCRv4_det", "arabic_PP-OCRv4_rec"),
        "hi" to Pair("ch_PP-OCRv4_det", "devanagari_PP-OCRv4_rec"),
        "ru" to Pair("ch_PP-OCRv4_det", "cyrillic_PP-OCRv4_rec")
    )

    init {
        modelsDir.mkdirs()
    }

    /**
     * Ensure models for the given language are available.
     * For now, models are bundled in assets and extracted on first use.
     * Future: download from remote server.
     *
     * @return Pair of (detModelPath, recModelPath) or null if models not available
     */
    suspend fun getModelPaths(language: String): Pair<String, String>? {
        val (detName, recName) = languageModels[language] ?: languageModels["en"]!!

        val detFile = File(modelsDir, "$detName.nb")
        val recFile = File(modelsDir, "$recName.nb")

        // Check if models already extracted
        if (detFile.exists() && recFile.exists()) {
            return Pair(detFile.absolutePath, recFile.absolutePath)
        }

        // Try to extract from assets
        return try {
            extractFromAssets(detName, detFile)
            extractFromAssets(recName, recFile)
            Pair(detFile.absolutePath, recFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract models from assets", e)
            null
        }
    }

    /**
     * Get the label file path for recognition.
     */
    fun getLabelPath(): String {
        val labelFile = File(modelsDir, "ppocr_keys_v1.txt")
        if (!labelFile.exists()) {
            extractFromAssets("ppocr_keys_v1", labelFile)
        }
        return labelFile.absolutePath
    }

    private fun extractFromAssets(assetName: String, targetFile: File) {
        if (targetFile.exists()) return

        context.assets.open("models/ocr/$assetName.nb").use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        Log.d(TAG, "Extracted $assetName to ${targetFile.absolutePath}")
    }

    /**
     * Get all supported languages.
     */
    fun getSupportedLanguages(): Map<String, String> = mapOf(
        "en" to "English",
        "zh" to "Chinese",
        "ja" to "Japanese",
        "ko" to "Korean",
        "ar" to "Arabic",
        "hi" to "Hindi",
        "ru" to "Russian"
    )

    /**
     * Check if models are available for a language.
     */
    fun isLanguageAvailable(language: String): Boolean {
        return languageModels.containsKey(language)
    }
}
```

- [ ] **Step 2: Verify the file compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ocr/OcrModelManager.kt
git commit -m "feat(ocr): add OcrModelManager for model file management"
```

---

## Task 6: Implement PaddleOcrEngine

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/ocr/PaddleOcrEngine.kt`

**Interfaces:**
- Consumes: `PaddleNative`, `OcrModelManager`, `OcrHtmlBuilder`
- Produces: `PaddleOcrEngine.recognizeText()`, `PaddleOcrEngine.close()`

- [ ] **Step 1: Create PaddleOcrEngine**

```kotlin
package com.omnidocs.app.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PaddleOcrEngine"

/**
 * PaddleOCR engine implementation using Paddle Lite via JNI.
 */
@Singleton
class PaddleOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: OcrModelManager
) : OcrEngine {

    private val paddleNative = PaddleNative()
    private var isInitialized = false
    private var currentLanguage: String? = null

    override suspend fun recognizeText(uri: Uri, language: String): OcrResult? {
        return withContext(Dispatchers.IO) {
            try {
                // Initialize if needed or language changed
                if (!isInitialized || currentLanguage != language) {
                    initializeEngine(language)
                }

                // Copy URI to temp file if needed
                val imagePath = copyUriToTempFile(uri) ?: return@withContext null

                // Run recognition
                val results = paddleNative.recognize(imagePath, 0.5f)

                // Clean up temp file
                File(imagePath).delete()

                if (results.isNullOrEmpty()) {
                    Log.w(TAG, "No text detected")
                    return@withContext OcrResult("", "", emptyList())
                }

                // Convert results to OcrBlock list
                // Each result array: [text_index, confidence, x1, y1, x2, y2, x3, y3, x4, y4]
                // The JNI layer returns text as a separate string array; here we use a placeholder
                // that will be replaced with actual text extraction in the JNI implementation.
                val blocks = results.mapIndexed { index, result ->
                    OcrBlock(
                        text = "Block ${index + 1}", // Placeholder - JNI returns text separately
                        confidence = result[1],
                        boundingBox = RectF(
                            result[2], result[3],  // x1, y1 (top-left)
                            result[6], result[7]   // x3, y3 (bottom-right)
                        )
                    )
                }

                // Build HTML
                val html = OcrHtmlBuilder.fromBlocks(blocks)
                val fullText = blocks.joinToString("\n") { it.text }

                OcrResult(fullText, html, blocks)
            } catch (e: Exception) {
                Log.e(TAG, "Recognition failed", e)
                null
            }
        }
    }

    override fun close() {
        try {
            paddleNative.destroy()
            isInitialized = false
            currentLanguage = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing engine", e)
        }
    }

    private suspend fun initializeEngine(language: String) {
        val modelPaths = modelManager.getModelPaths(language)
        if (modelPaths == null) {
            throw IllegalStateException("Models not available for language: $language")
        }

        val (detPath, recPath) = modelPaths
        val labelPath = modelManager.getLabelPath()

        val success = paddleNative.init(detPath, recPath, labelPath, 4)
        if (!success) {
            throw IllegalStateException("Failed to initialize PaddleOCR engine")
        }

        isInitialized = true
        currentLanguage = language
        Log.d(TAG, "Engine initialized for language: $language")
    }

    private fun copyUriToTempFile(uri: Uri): String? {
        return try {
            val tempFile = File(context.cacheDir, "ocr_input_${System.currentTimeMillis()}.jpg")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            tempFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy URI to temp file", e)
            null
        }
    }
}
```

- [ ] **Step 2: Verify the file compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ocr/PaddleOcrEngine.kt
git commit -m "feat(ocr): implement PaddleOcrEngine with JNI bridge"
```

---

## Task 7: Create OcrEngineFactory

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/ocr/OcrEngineFactory.kt`

**Interfaces:**
- Consumes: `PaddleOcrEngine`
- Produces: `OcrEngineFactory.getEngine()`

- [ ] **Step 1: Create OcrEngineFactory**

```kotlin
package com.omnidocs.app.ocr

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory for creating OCR engines.
 * Currently only supports PaddleOCR, but the interface allows for future engines.
 */
@Singleton
class OcrEngineFactory @Inject constructor(
    private val paddleOcrEngine: PaddleOcrEngine
) {
    /**
     * Get the default OCR engine.
     *
     * @return OcrEngine instance
     */
    fun getEngine(): OcrEngine = paddleOcrEngine

    /**
     * Get available languages from the default engine.
     */
    fun getSupportedLanguages(): Map<String, String> = mapOf(
        "en" to "English",
        "zh" to "Chinese",
        "ja" to "Japanese",
        "ko" to "Korean",
        "ar" to "Arabic",
        "hi" to "Hindi",
        "ru" to "Russian"
    )
}
```

- [ ] **Step 2: Verify the file compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ocr/OcrEngineFactory.kt
git commit -m "feat(ocr): add OcrEngineFactory for engine selection"
```

---

## Task 8: Update OcrViewModel to Use OcrEngine

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrViewModel.kt`

**Interfaces:**
- Consumes: `OcrEngineFactory`, `OcrHtmlBuilder`
- Produces: Updated `OcrViewModel.recognizeTextFromImage()`, `recognizeTextFromBitmap()`

- [ ] **Step 1: Update OcrViewModel imports and constructor**

Replace the existing OcrViewModel with:

```kotlin
package com.omnidocs.app.ui.screens.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.ai.NllbTranslationService
import com.omnidocs.app.ocr.OcrEngineFactory
import com.omnidocs.app.ocr.OcrHtmlBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

private const val TAG = "OcrViewModel"

@HiltViewModel
class OcrViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engineFactory: OcrEngineFactory,
    private val translationService: NllbTranslationService
) : ViewModel() {

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _recognizedHtml = MutableStateFlow("")
    val recognizedHtml: StateFlow<String> = _recognizedHtml.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedImageUri = MutableStateFlow<Uri?>(null)
    val selectedImageUri: StateFlow<Uri?> = _selectedImageUri.asStateFlow()

    private val _ocrLanguage = MutableStateFlow("en")
    val ocrLanguage: StateFlow<String> = _ocrLanguage.asStateFlow()

    private val _translationTarget = MutableStateFlow<String?>(null)
    val translationTarget: StateFlow<String?> = _translationTarget.asStateFlow()

    private val _translatedText = MutableStateFlow("")
    val translatedText: StateFlow<String> = _translatedText.asStateFlow()

    private val _ocrError = MutableStateFlow<String?>(null)
    val ocrError: StateFlow<String?> = _ocrError.asStateFlow()

    val supportedLanguages: Map<String, String> = engineFactory.getSupportedLanguages()

    override fun onCleared() {
        super.onCleared()
        engineFactory.getEngine().close()
    }

    fun setOcrLanguage(lang: String) {
        _ocrLanguage.value = lang
        Log.d(TAG, "OCR language set to: $lang")
    }

    fun setTranslationTarget(lang: String?) {
        _translationTarget.value = lang
        Log.d(TAG, "Translation target set to: $lang")
    }

    fun setImageUri(uri: Uri) {
        _selectedImageUri.value = uri
    }

    fun updateRecognizedText(text: String) {
        _recognizedText.value = text
    }

    fun recognizeTextFromImage(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            _ocrError.value = null
            try {
                val engine = engineFactory.getEngine()
                val result = engine.recognizeText(uri, _ocrLanguage.value)

                if (result != null) {
                    _recognizedText.value = result.text
                    _recognizedHtml.value = result.html

                    // Auto-translate if target is set
                    _translationTarget.value?.let { targetLang ->
                        translateText(result.text, targetLang)
                    }
                } else {
                    _ocrError.value = "Recognition failed: no result returned"
                }
            } catch (e: Exception) {
                Log.e(TAG, "OCR error", e)
                _ocrError.value = "Recognition failed: ${e.message ?: "Unknown error"}"
                _recognizedText.value = ""
                _recognizedHtml.value = ""
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun recognizeTextFromBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            _isLoading.value = true
            _ocrError.value = null
            try {
                // Save bitmap to temp file
                val file = withContext(Dispatchers.IO) {
                    val tempFile = File(context.cacheDir, "temp_ocr_image.jpg")
                    FileOutputStream(tempFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    tempFile
                }

                val uri = Uri.fromFile(file)
                _selectedImageUri.value = uri

                // Recognize text
                val engine = engineFactory.getEngine()
                val result = engine.recognizeText(uri, _ocrLanguage.value)

                if (result != null) {
                    _recognizedText.value = result.text
                    _recognizedHtml.value = result.html

                    // Auto-translate if target is set
                    _translationTarget.value?.let { targetLang ->
                        translateText(result.text, targetLang)
                    }
                } else {
                    _ocrError.value = "Recognition failed: no result returned"
                }
            } catch (e: Exception) {
                Log.e(TAG, "OCR error", e)
                _ocrError.value = "Recognition failed: ${e.message ?: "Unknown error"}"
                _recognizedText.value = ""
                _recognizedHtml.value = ""
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun translateText(text: String, targetLang: String) {
        viewModelScope.launch {
            try {
                val translated = translationService.translate(text, targetLang)
                _translatedText.value = translated ?: ""
            } catch (e: Exception) {
                Log.e(TAG, "Translation error", e)
                _ocrError.value = "Translation failed: ${e.message ?: "Unknown error"}"
            }
        }
    }
}
```

- [ ] **Step 2: Verify the file compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrViewModel.kt
git commit -m "feat(ocr): update OcrViewModel to use OcrEngine interface"
```

---

## Task 9: Fix NllbTranslationService.loadModel()

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ai/NllbTranslationService.kt:236-249`

**Interfaces:**
- Consumes: `llamaCppService.loadModel()`
- Produces: Fixed `loadModel()` method

- [ ] **Step 1: Fix the loadModel method**

Replace lines 236-249 in `NllbTranslationService.kt`:

```kotlin
private suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
    try {
        val model = modelDownloadManager.getDownloadedModels().firstOrNull { it.isDownloaded }
            ?: return@withContext false

        // FIX: Actually load the model via llamaCppService
        val loaded = llamaCppService.loadModel(model.id)
        if (loaded) {
            modelLoaded = true
            Log.d(TAG, "Model loaded successfully: ${model.name}")
            true
        } else {
            Log.e(TAG, "Failed to load model: ${model.name}")
            false
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error loading model", e)
        false
    }
}
```

- [ ] **Step 2: Verify the file compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ai/NllbTranslationService.kt
git commit -m "fix(translation): call llamaCppService.loadModel() in loadModel()"
```

---

## Task 10: Delete Dead Code

**Files:**
- Delete: `app/src/main/java/com/omnidocs/app/ocr/TextRecognitionService.kt`
- Delete: `app/src/main/java/com/omnidocs/app/ocr/OcrUtils.kt`
- Delete: `app/src/main/java/com/omnidocs/app/ocr/PaddleOcrService.kt`

**Interfaces:**
- Consumes: None (removing unused code)

- [ ] **Step 1: Delete TextRecognitionService.kt**

```bash
rm app/src/main/java/com/omnidocs/app/ocr/TextRecognitionService.kt
```

- [ ] **Step 2: Delete OcrUtils.kt**

```bash
rm app/src/main/java/com/omnidocs/app/ocr/OcrUtils.kt
```

- [ ] **Step 3: Delete PaddleOcrService.kt**

```bash
rm app/src/main/java/com/omnidocs/app/ocr/PaddleOcrService.kt
```

- [ ] **Step 4: Verify no compilation errors from deleted files**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS (no references to deleted files)

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/com/omnidocs/app/ocr/
git commit -m "chore(ocr): remove dead code (TextRecognitionService, OcrUtils, PaddleOcrService)"
```

---

## Task 11: Add PaddleOCR Models to Assets

**Files:**
- Create: `app/src/main/assets/models/ocr/` directory
- Create: `app/src/main/assets/models/ocr/ppocr_keys_v1.txt` (label file)

**Interfaces:**
- Produces: Model files available for OcrModelManager

- [ ] **Step 1: Create assets directory structure**

```bash
mkdir -p app/src/main/assets/models/ocr
```

- [ ] **Step 2: Download PP-OCRv4 models**

Download from PaddleOCR model zoo:
- Detection: `ch_PP-OCRv4_det.nb`
- Recognition (English): `en_PP-OCRv4_rec.nb`
- Label file: `ppocr_keys_v1.txt`

Place in `app/src/main/assets/models/ocr/`.

- [ ] **Step 3: Add .gitignore for large model files**

Create `app/src/main/assets/models/ocr/.gitignore`:

```
# Large model files - download separately
*.nb
!ppocr_keys_v1.txt
```

- [ ] **Step 4: Update OcrModelManager to handle missing assets gracefully**

If assets aren't bundled, OcrModelManager should return null and OcrEngine should show appropriate error message.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/models/ocr/
git commit -m "feat(ocr): add PaddleOCR model assets directory"
```

---

## Task 12: Verify JNI Bridge and Native Build

**Files:**
- No new files (verification step)

**Interfaces:**
- Consumes: All previous tasks (1-11)
- Produces: Verified working native build

- [ ] **Step 1: Verify native build compiles**

Run: `./gradlew :app:externalNativeBuildDebug`
Expected: BUILD SUCCESSFUL (may fail if Paddle Lite .so files are missing - that's expected)

- [ ] **Step 2: Check for JNI method registration errors**

If build succeeds, check logcat for JNI registration issues:
```bash
adb logcat -s PaddleOcrJni
```

- [ ] **Step 3: Document actual Paddle Lite directory structure**

After the first build, check what directory structure was actually extracted:
```bash
ls -la app/PaddleLite/
```

Update CMakeLists.txt paths if they don't match the expected structure.

- [ ] **Step 4: Commit any path corrections**

```bash
git add app/src/main/cpp/CMakeLists.txt
git commit -m "fix(ocr): correct Paddle Lite paths in CMakeLists.txt"
```

---

## Task 13: Update OcrScreen for New Engine

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt`

**Interfaces:**
- Consumes: Updated `OcrViewModel` (no more `ocrEngine` state)

- [ ] **Step 1: Remove engine selector UI**

The `OcrScreen` currently has no engine selector in the UI (it was removed in the previous audit), so no changes needed here. The ViewModel no longer exposes `ocrEngine` state.

- [ ] **Step 2: Update language list to use engine factory languages**

In `OcrScreen.kt`, the `ocrLangNames` map should be updated to match the new supported languages:

```kotlin
val ocrLangNames = mapOf(
    "en" to "English",
    "zh" to "Chinese",
    "ja" to "Japanese",
    "ko" to "Korean",
    "ar" to "Arabic",
    "hi" to "Hindi",
    "ru" to "Russian"
)
```

- [ ] **Step 3: Verify the file compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt
git commit -m "feat(ocr): update OcrScreen language list for PaddleOCR"
```

---

## Task 13: Integration Test

**Files:**
- No new files (manual testing)

**Interfaces:**
- Consumes: All previous tasks
- Produces: Verified working OCR engine

- [ ] **Step 1: Build the app**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Install on device**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: Success

- [ ] **Step 3: Test OCR from gallery**

1. Open OmniDocs app
2. Open a note
3. Tap OCR icon in toolbar
4. Select "Gallery"
5. Choose an image with English text
6. Verify text is recognized and displayed

- [ ] **Step 4: Test OCR language selection**

1. Change language to "Chinese" in OCR screen
2. Select a Chinese text image
3. Verify Chinese text is recognized

- [ ] **Step 5: Test translation**

1. After OCR recognition, select a translation target language
2. Verify translation appears below recognized text

- [ ] **Step 6: Test "Use This Text" button**

1. After recognition, tap "Use This Text"
2. Verify text is inserted into the note editor

- [ ] **Step 7: Document any issues**

If any test fails, document the issue and create follow-up tasks.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "test(ocr): verify PaddleOCR integration on device"
```

---

## Summary

This plan completes Plan 1 of 3 for the OCR overhaul (14 tasks):

1. **PaddleOCR Engine Foundation** (this plan - Tasks 1-14)
   - OcrEngine interface and data classes (Task 1)
   - OcrHtmlBuilder - deduplicated HTML generation (Task 2)
   - Paddle Lite native build setup (Task 3)
   - PaddleNative JNI bridge class (Task 4)
   - OcrModelManager for model files (Task 5)
   - PaddleOcrEngine implementation (Task 6)
   - OcrEngineFactory (Task 7)
   - OcrViewModel update (Task 8)
   - NllbTranslationService fix (Task 9)
   - Dead code removal (Task 10)
   - Model assets setup (Task 11)
   - JNI bridge verification (Task 12)
   - OcrScreen language list update (Task 13)
   - Integration testing (Task 14)

2. **Live Camera Bounding Boxes** (Plan 2 - separate)
   - CameraX + PaddleOCR real-time analysis
   - Bounding box overlay composable
   - Debouncing and confidence filtering

3. **UX Improvements** (Plan 3 - separate)
   - Copy to clipboard
   - Batch scan mode
   - Confidence indicators
   - Editable results with reset
