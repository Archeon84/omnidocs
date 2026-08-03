# Live Camera Bounding Boxes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add real-time bounding box overlay for live camera OCR with confidence filtering and debouncing.

**Architecture:** CameraX ImageAnalysis runs PaddleOCR on each frame, results are mapped to preview coordinates and rendered as semi-transparent rectangles with confidence labels. Debouncing at 200ms prevents jitter.

**Tech Stack:** Kotlin, Compose, CameraX, PaddleOCR (Paddle Lite JNI)

## Global Constraints

- PaddleOCR is the sole OCR engine (ML Kit fully removed)
- All code follows existing patterns in the codebase
- No new external dependencies beyond what's already in build.gradle.kts
- Target device: Xiaomi 13 Ultra (arm64-v8a only)
- Performance: must maintain 15+ FPS on camera preview

---

### Task 1: Update OcrEngine Interface for Bounding Boxes

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ocr/OcrEngine.kt`

**Interfaces:**
- Consumes: None
- Produces: Updated OcrResult with blocks containing bounding boxes

- [ ] **Step 1: Read current OcrEngine.kt**

```bash
cat app/src/main/java/com/omnidocs/app/ocr/OcrEngine.kt
```

- [ ] **Step 2: Update OcrBlock to include normalized bounding box**

The OcrBlock already has boundingBox: RectF. Verify it's normalized (0-1 coordinates).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ocr/OcrEngine.kt
git commit -m "chore(ocr): verify OcrBlock bounding box is normalized"
```

---

### Task 2: Create LiveCameraOverlay Composable

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/ui/screens/ocr/LiveCameraOverlay.kt`

**Interfaces:**
- Consumes: List<OcrBlock>, previewSize: Size
- Produces: Composable overlay with bounding boxes

- [ ] **Step 1: Create LiveCameraOverlay.kt**

```kotlin
package com.omnidocs.app.ui.screens.ocr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.omnidocs.app.ocr.OcrBlock

@Composable
fun LiveCameraOverlay(
    blocks: List<OcrBlock>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        blocks.forEach { block ->
            val bbox = block.boundingBox
            val left = bbox.left * size.width
            val top = bbox.top * size.height
            val right = bbox.right * size.width
            val bottom = bbox.bottom * size.height

            // Color based on confidence
            val color = when {
                block.confidence > 0.8f -> Color.Green.copy(alpha = 0.6f)
                block.confidence > 0.5f -> Color.Yellow.copy(alpha = 0.6f)
                else -> Color.Red.copy(alpha = 0.4f)
            }

            // Draw bounding box
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/ocr/LiveCameraOverlay.kt
git commit -m "feat(ocr): add LiveCameraOverlay composable for bounding boxes"
```

---

### Task 3: Implement Live OCR Analysis with Debouncing

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt`

**Interfaces:**
- Consumes: LiveCameraOverlay, PaddleNative
- Produces: Updated LiveCameraOcrView with real OCR processing

- [ ] **Step 1: Update LiveCameraOcrView to run PaddleOCR on frames**

Add ImageAnalysis.Analyzer that:
1. Converts ImageProxy to bitmap
2. Saves to temp file
3. Calls PaddleNative.recognize()
4. Maps results to OcrBlock list
5. Debounces updates to 200ms

- [ ] **Step 2: Add confidence filtering**

Filter out blocks with confidence < 0.5

- [ ] **Step 3: Overlay bounding boxes on camera preview**

Use Box with LiveCameraOverlay on top of PreviewView

- [ ] **Step 4: Verify compilation**

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt
git commit -m "feat(ocr): implement live OCR with bounding boxes and debouncing"
```

---

### Task 4: Integration Test

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
