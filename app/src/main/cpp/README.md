# Native C++ Architecture

This directory contains the C++ native components of OmniDocs.

## 1. Active Native Targets

### `usearch-android`
* **Source**: `usearch_jni.cpp`, header-only `usearch/` submodule.
* **Build Target**: Declared in [CMakeLists.txt](file:///h:/Work/OmniDocs/app/src/main/cpp/CMakeLists.txt).
* **Purpose**: Provides hardware-accelerated Approximate Nearest Neighbor (ANN) HNSW vector index search.
* **Kotlin JNI Binding**: `com.omnidocs.app.search.ann.USearchNative`.

---

## 2. Decoupled Fallback Assets (Uncompiled)

### `llama.cpp` and `llama_jni.cpp`
* **Status**: Inactive / Decoupled from `CMakeLists.txt`.
* **Rationale**: The production application migrated generative LLM inference and embeddings to Google's official on-device **LiteRT-LM SDK** (`com.google.ai.edge.litertlm:litertlm-android`) executing Gemma 4 and Multilingual E5 Small models.
* **Retention**: `llama_jni.cpp` (42 KB) and the `llama.cpp` submodule are preserved uncompiled as a fallback reference implementation. They do not increase APK size or compile times.

### `paddleocr`
* **Status**: Inactive / Historical reference.
* **Rationale**: Document OCR is handled natively by Google Play Services / Google ML Kit's on-device Text Recognition (`MlKitOcrEngine.kt`) with CameraX.
