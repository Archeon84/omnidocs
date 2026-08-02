# OCR Feature Overhaul Design

## Overview

Replace ML Kit with PaddleOCR (Paddle Lite AAR) as the sole OCR engine, add bounding box overlay for live camera, and polish the OCR UX with copy, batch scan, confidence indicators, and editable results.

## Goals

1. Remove ML Kit dependency entirely
2. Implement PaddleOCR via Paddle Lite AAR
3. Support all PP-OCRv4 scripts (English, Chinese, Japanese, Korean, Arabic, Devanagari, Cyrillic)
4. Add bounding box overlay for live camera OCR
5. Improve OCR UX: copy, batch scan, confidence, editable results
6. Fix NllbTranslationService.loadModel() bug
7. Deduplicate HTML generation into shared utility

## Architecture

### OcrEngine Interface

```kotlin
interface OcrEngine {
    suspend fun recognizeText(uri: Uri, language: String): OcrResult?
    fun close()
}

data class OcrResult(
    val text: String,
    val html: String,
    val blocks: List<OcrBlock>  // for bounding boxes
)

data class OcrBlock(
    val text: String,
    val confidence: Float,
    val boundingBox: RectF  // normalized 0-1 coordinates
)
```

### PaddleOcrEngine

```kotlin
class PaddleOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : OcrEngine {
    private var predictor: PaddlePredictor? = null

    override suspend fun recognizeText(uri: Uri, language: String): OcrResult? {
        // 1. Load appropriate det+rec model pair for language
        // 2. Run text detection
        // 3. Run text recognition on detected regions
        // 4. Return OcrResult with text, html, and bounding boxes
    }

    fun close() { predictor?.destroy() }
}
```

### Model Loading

Paddle Lite AAR bundles pre-built .so files. Model files (det + rec) for each script are stored in assets or downloaded on first use:

| Script | Detection Model | Recognition Model | Size |
|--------|----------------|-------------------|------|
| English | ch_PP-OCRv4_det | en_PP-OCRv4_rec | ~15MB |
| Chinese | ch_PP-OCRv4_det | ch_PP-OCRv4_rec | ~15MB |
| Japanese | ch_PP-OCRv4_det | ja_PP-OCRv4_rec | ~15MB |
| Korean | ch_PP-OCRv4_det | ko_PP-OCRv4_rec | ~15MB |
| Arabic | ch_PP-OCRv4_det | arabic_PP-OCRv4_rec | ~15MB |
| Devanagari | ch_PP-OCRv4_det | devanagari_PP-OCRv4_rec | ~15MB |
| Cyrillic | ch_PP-OCRv4_det | cyrillic_PP-OCRv4_rec | ~15MB |

Detection model is shared across all scripts. Total: ~15MB (det) + 7 x ~15MB (rec) = ~120MB for all scripts. Can be bundled or downloaded on demand.

### OcrEngineFactory

```kotlin
@Singleton
class OcrEngineFactory @Inject constructor(
    private val paddleOcrEngine: PaddleOcrEngine
) {
    fun getEngine(): OcrEngine = paddleOcrEngine
}
```

### OcrHtmlBuilder (deduplicated)

```kotlin
object OcrHtmlBuilder {
    fun fromOcrResult(result: OcrResult): String = result.html

    fun fromPlainText(text: String, grouping: Grouping = Grouping.PARAGRAPH): String {
        // grouping: LINE (one <p> per line), PARAGRAPH (merge consecutive non-empty lines)
    }

    enum class Grouping { LINE, PARAGRAPH }
}
```

### Live Camera Bounding Box Overlay

```kotlin
@Composable
fun LiveCameraOcrOverlay(
    ocrBlocks: List<OcrBlock>,
    previewSize: Size
) {
    // Draw semi-transparent rectangles around detected text
    // Each block shows confidence as small label
    // Tap a block to select/deselect it
    // Selected blocks are highlighted
}
```

Implementation approach:
- CameraX `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`
- PaddleOCR runs detection + recognition on each frame
- Results are mapped to preview coordinates using rotation degrees
- Debounce: only update overlay every 200ms to avoid jitter
- Confidence filtering: hide blocks below 0.5 confidence

### UX Improvements

#### Copy to Clipboard
- Add copy button next to "Use This Text" button
- Uses `ClipboardManager` to copy recognized text
- Shows snackbar confirmation

#### Batch Scan Mode
- Add "Batch Scan" button on OCR screen
- Opens gallery picker in multi-select mode
- Processes images sequentially with progress indicator
- Results concatenated with page breaks
- "Use All Text" inserts combined results

#### Confidence Indicators
- Each recognized block shows confidence as percentage
- Color-coded: green (>0.8), yellow (0.5-0.8), red (<0.5)
- Tap to expand/collapse low-confidence blocks

#### Editable Results
- Recognized text field is already editable (existing feature)
- Add "Reset" button to restore original recognition
- Add word count display below text field

### Translation Service Fix

```kotlin
private suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
    try {
        val model = modelDownloadManager.getDownloadedModels().firstOrNull { it.isDownloaded }
            ?: return@withContext false

        // FIX: Actually load the model via llamaCppService
        val loaded = llamaCppService.loadModel(model.id)
        if (loaded) {
            modelLoaded = true
            true
        } else {
            false
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error loading model", e)
        false
    }
}
```

## Files to Modify

### New Files
- `app/src/main/java/com/omnidocs/app/ocr/OcrEngine.kt` - interface
- `app/src/main/java/com/omnidocs/app/ocr/PaddleOcrEngine.kt` - implementation
- `app/src/main/java/com/omnidocs/app/ocr/OcrEngineFactory.kt` - factory
- `app/src/main/java/com/omnidocs/app/ocr/OcrHtmlBuilder.kt` - deduplicated HTML
- `app/src/main/java/com/omnidocs/app/ocr/OcrResult.kt` - data classes
- `app/src/main/java/com/omnidocs/app/ui/screens/ocr/LiveCameraOverlay.kt` - bounding boxes

### Modified Files
- `app/build.gradle.kts` - remove ML Kit deps, add Paddle Lite AAR
- `app/src/main/java/com/omnidocs/app/ocr/PaddleOcrService.kt` - replace with PaddleOcrEngine
- `app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrViewModel.kt` - use OcrEngine
- `app/src/main/java/com/omnidocs/app/ui/screens/ocr/OcrScreen.kt` - UX improvements
- `app/src/main/java/com/omnidocs/app/ai/NllbTranslationService.kt` - fix loadModel
- `app/src/main/java/com/omnidocs/app/ui/navigation/Navigation.kt` - pass confidence data

### Deleted Files
- `app/src/main/java/com/omnidocs/app/ocr/TextRecognitionService.kt` - dead code
- `app/src/main/java/com/omnidocs/app/ocr/OcrUtils.kt` - replaced by OcrHtmlBuilder

## Risks

1. **APK size increase**: PaddleOCR models are larger than ML Kit. Mitigate by offering download-on-demand for non-primary scripts.
2. **PaddleOCR accuracy**: ML Kit is Google's production OCR. PaddleOCR is comparable but may differ on edge cases. Mitigate by testing on real-world documents before shipping.
3. **Live camera performance**: PaddleOCR may be slower than ML Kit on CameraX frames. Mitigate by reducing analysis resolution and debouncing.
4. **Model download UX**: Users may not want to download 120MB of models. Mitigate by defaulting to English only, with clear upgrade path.

## Testing

1. Unit tests for OcrHtmlBuilder (HTML generation)
2. Unit tests for OcrEngineFactory (engine selection)
3. Integration tests for PaddleOcrEngine (recognize text from test images)
4. UI tests for LiveCameraOverlay (bounding box rendering)
5. Manual testing on Xiaomi 13 Ultra (arm64-v8a only)

## Success Criteria

1. ML Kit dependency removed from build.gradle.kts
2. PaddleOCR recognizes English text from gallery images
3. Live camera shows bounding boxes around detected text
4. All PP-OCRv4 scripts selectable and functional
5. Copy-to-clipboard works
6. Batch scan processes multiple images
7. Translation service loads model correctly
8. No crashes on device
