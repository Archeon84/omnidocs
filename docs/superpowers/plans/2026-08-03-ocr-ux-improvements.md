# OCR UX Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add copy-to-clipboard, batch scan mode, confidence indicators, and editable results with word count.

**Architecture:** Enhance OcrScreen with new UI elements and functionality. Batch scan uses multi-select gallery picker and processes images sequentially.

**Tech Stack:** Kotlin, Compose, CameraX, PaddleOCR (Paddle Lite JNI)

## Global Constraints

- PaddleOCR is the sole OCR engine (ML Kit fully removed)
- All code follows existing patterns in the codebase
- No new external dependencies beyond what's already in build.gradle.kts
- Target device: Xiaomi 13 Ultra (arm64-v8a only)

---

### Task 1: Add Copy to Clipboard

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt`

**Interfaces:**
- Consumes: ClipboardManager
- Produces: Copy button with snackbar confirmation

- [ ] **Step 1: Add clipboard manager import**

```kotlin
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
```

- [ ] **Step 2: Add copy button next to "Use This Text"**

```kotlin
OutlinedButton(
    onClick = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("OCR Text", recognizedText)
        clipboard.setPrimaryClip(clip)
        // Show snackbar
    },
    modifier = Modifier.weight(1f)
) {
    Icon(Icons.Default.ContentCopy, null)
    Spacer(modifier = Modifier.width(4.dp))
    Text("Copy")
}
```

- [ ] **Step 3: Add snackbar host to Scaffold**

- [ ] **Step 4: Verify compilation**

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt
git commit -m "feat(ocr): add copy-to-clipboard with snackbar confirmation"
```

---

### Task 2: Add Batch Scan Mode

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt`
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrViewModel.kt`

**Interfaces:**
- Consumes: ActivityResultContracts.PickMultipleVisualMedia
- Produces: Batch scan functionality with progress indicator

- [ ] **Step 1: Add multi-select gallery launcher**

```kotlin
val batchGalleryLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickMultipleVisualMedia(10)
) { uris: List<Uri> ->
    if (uris.isNotEmpty()) {
        viewModel.batchRecognizeText(uris)
    }
}
```

- [ ] **Step 2: Add batchRecognizeText to OcrViewModel**

```kotlin
private val _batchProgress = MutableStateFlow<Pair<Int, Int>?>(null)
val batchProgress: StateFlow<Pair<Int, Int>?> = _batchProgress.asStateFlow()

fun batchRecognizeText(uris: List<Uri>) {
    viewModelScope.launch {
        _isLoading.value = true
        _batchProgress.value = 0 to uris.size
        val allText = mutableListOf<String>()
        
        for ((index, uri) in uris.withIndex()) {
            _batchProgress.value = index + 1 to uris.size
            val engine = engineFactory.getEngine()
            val result = engine.recognizeText(uri, _ocrLanguage.value)
            result?.let { allText.add(it.text) }
        }
        
        _recognizedText.value = allText.joinToString("\n\n---\n\n")
        _recognizedHtml.value = allText.joinToString("\n\n<hr>\n\n") { 
            OcrHtmlBuilder.fromPlainText(it) 
        }
        _batchProgress.value = null
        _isLoading.value = false
    }
}
```

- [ ] **Step 3: Add Batch Scan button to UI**

- [ ] **Step 4: Show progress indicator during batch scan**

- [ ] **Step 5: Verify compilation**

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrViewModel.kt
git commit -m "feat(ocr): add batch scan mode for multiple images"
```

---

### Task 3: Add Confidence Indicators

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt`

**Interfaces:**
- Consumes: OcrResult.blocks
- Produces: Color-coded confidence display

- [ ] **Step 1: Add confidence indicator composable**

```kotlin
@Composable
fun ConfidenceIndicator(confidence: Float) {
    val color = when {
        confidence > 0.8f -> MaterialTheme.colorScheme.primary
        confidence > 0.5f -> Color(0xFFFFC107) // Amber
        else -> MaterialTheme.colorScheme.error
    }
    
    Text(
        text = "${(confidence * 100).toInt()}%",
        color = color,
        style = MaterialTheme.typography.labelSmall
    )
}
```

- [ ] **Step 2: Show average confidence below recognized text**

- [ ] **Step 3: Verify compilation**

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt
git commit -m "feat(ocr): add confidence indicators with color coding"
```

---

### Task 4: Add Word Count and Reset Button

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt`
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrViewModel.kt`

**Interfaces:**
- Consumes: recognizedText
- Produces: Word count display, reset functionality

- [ ] **Step 1: Add originalText state to ViewModel**

```kotlin
private val _originalText = MutableStateFlow("")
val originalText: StateFlow<String> = _originalText.asStateFlow()

fun resetToOriginal() {
    _recognizedText.value = _originalText.value
}
```

- [ ] **Step 2: Store original text when recognition completes**

- [ ] **Step 3: Add word count display**

```kotlin
val wordCount = recognizedText.split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
Text(
    text = "$wordCount words",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)
```

- [ ] **Step 4: Add Reset button**

```kotlin
if (recognizedText != originalText) {
    OutlinedButton(onClick = { viewModel.resetToOriginal() }) {
        Icon(Icons.Default.Refresh, null)
        Spacer(modifier = Modifier.width(4.dp))
        Text("Reset")
    }
}
```

- [ ] **Step 5: Verify compilation**

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrViewModel.kt
git commit -m "feat(ocr): add word count and reset button for editable results"
```

---

### Task 5: Integration Test

**Files:**
- No new files

**Interfaces:**
- Consumes: All previous tasks

- [ ] **Step 1: Verify full build compiles**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 2: Document results**

---
