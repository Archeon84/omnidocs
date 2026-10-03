<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# im not building a new app but rather to improve the existing one. revise the mvp.

Below is a **revised MVP spec** explicitly written as an **improvement plan for your existing Android app** (Kotlin + C++ native + Jetpack Compose). You can paste this into your agentic Gemini AI as the “integration brief” to guide it in modifying your current codebase.

______________________________________________________________________

# MVP Brief: Add On‑Device RAG with Qwen3.5‑2B \& Phi‑4‑mini‑3.8B to Existing Android App

**Goal:**\
Enhance your existing notes app (Kotlin, C++ native, Jetpack Compose UI) with an **offline RAG system** and **two optional local LLMs**:

- **Default**: `Qwen3.5‑2B‑Instruct‑Q4_K_M.gguf`
- **Optional “Pro”**: `Phi‑4‑mini‑3.8B‑Instruct‑Q4_K_M.gguf` (downloadable in Settings)

All inference and retrieval must run **on device**, no cloud.

This document describes **what to add/change** in your existing app, not how to create a new one.

______________________________________________________________________

## 1. Assumptions about your existing app

Your current app already has:

- **Kotlin** codebase with **Jetpack Compose** UI.
- Some form of **notes data layer** (Room / DataStore / custom repository).
- A **C++ native module** already integrated via JNI (or you’re open to adding one).
- A **Settings** screen (or at least a place to add model selection).
- Target SDK ≥ 26, with NDK configured for `arm64-v8a`.

If any of these are missing, your agentic Gemini should create the minimal pieces needed.

______________________________________________________________________

## 2. High‑level integration plan

You will **add** the following capabilities to your existing app:

1. **Native LLM engine (C++ + JNI)**
    - Integrate llama.cpp (GGUF) as a native library.
    - Expose JNI functions for:
        - `initEngine()`
        - `loadModel(path, contextSize, threads)`
        - `generate(prompt, callback)` – streaming tokens back to Kotlin.
    - Reuse or adapt your existing C++ module / build setup if possible. [^1][^2][^3]
2. **Kotlin LLM service wrapper**
    - Add a new package/module (e.g. `llm/`) that:
        - Loads the native library.
        - Manages model loading/unloading.
        - Exposes a coroutine‑friendly API (e.g. `Flow<String>` for token streaming).
3. **Model management (download + selection)**
    - Extend your existing **Settings** screen to:
        - Show available models (Qwen3.5‑2B, Phi‑4‑mini‑3.8B).
        - Show download status for each.
        - Allow downloading the optional model from a URL (e.g. HuggingFace direct link).
        - Let the user select the active model.
    - Persist the active model ID in your existing preferences (DataStore / SharedPreferences).
4. **RAG pipeline (Kotlin)**
    - Add a new `rag/` package with:
        - `ChunkingService` – splits notes into ~512‑token chunks with 10–20% overlap. [^4][^5]
        - `RetrievalService` – MVP: keyword/BM25‑style scoring over chunks.
        - `PromptBuilder` – builds system + context + user prompt for the LLM.
    - Hook this into your existing notes repository so that:
        - When notes are created/updated/deleted, chunks are recomputed and re‑indexed.
5. **“Ask about my notes” UI**
    - Add a new Compose screen (or extend an existing one) where:
        - User types a question.
        - App retrieves relevant chunks.
        - App streams an LLM answer using the selected model.
    - Reuse your existing theme, navigation, and components.

All of this should be **incremental**: you’re adding new packages and screens, not rewriting the whole app.

______________________________________________________________________

## 3. Native C++ (llama.cpp) integration into existing app

### 3.1. Add llama.cpp to your existing native module

If you already have a `src/main/cpp` folder with `CMakeLists.txt`:

1. Clone or copy `llama.cpp` into your project (e.g. `app/src/main/cpp/llama.cpp`).
2. In `CMakeLists.txt`, add:

```cmake
add_subdirectory(llama.cpp)

add_library(notesai_llm SHARED
    llama-android.cpp  # your JNI wrapper
)

find_library(log-lib log)

target_link_libraries(notesai_llm
    llama
    ${log-lib}
    android
)
```

3. Ensure `app/build.gradle.kts` has:

```kotlin
android {
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}
```


Use the official `examples/llama.android` as reference for structure and build flags. [^6][^7][^1]

### 3.2. JNI wrapper (C++)

Add a new file `llama-android.cpp` in `src/main/cpp` (or integrate into your existing native code).

Implement these JNI functions (adjust package/class names to match your app):

```cpp
// src/main/cpp/llama-android.cpp

#include <jni.h>
#include <string>
#include <thread>
#include <atomic>
#include "llama.h"

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static std::atomic<bool> g_stop(false);

extern "C"
JNIEXPORT jint JNICALL
Java_com_yourapp_notesai_llm_LlmEngine_nativeInit(JNIEnv* env, jobject thiz) {
    llama_backend_init();
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_yourapp_notesai_llm_LlmEngine_nativeLoadModel(
    JNIEnv* env, jobject thiz, jstring modelPathJ, jint ctx_size, jint n_threads) {

    if (g_model) {
        llama_free_model(g_model);
        g_model = nullptr;
    }
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }

    const char* modelPath = env->GetStringUTFChars(modelPathJ, nullptr);

    llama_model_params model_params = llama_model_default_params();
    g_model = llama_load_model_from_file(modelPath, model_params);
    env->ReleaseStringUTFChars(modelPathJ, modelPath);

    if (!g_model) {
        return -1;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(ctx_size);
    ctx_params.n_threads = static_cast<uint32_t>(n_threads);
    ctx_params.n_threads_batch = ctx_params.n_threads;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        return -2;
    }

    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_yourapp_notesai_llm_LlmEngine_nativeGenerate(
    JNIEnv* env, jobject thiz, jstring promptJ, jobject callback) {

    if (!g_ctx || !g_model) return;

    const char* prompt = env->GetStringUTFChars(promptJ, nullptr);
    std::string promptStr(prompt);
    env->ReleaseStringUTFChars(promptJ, prompt);

    std::vector<llama_token> tokens = ::llama_tokenize(
        llama_model_get_vocab(g_model),
        promptStr.c_str(), promptStr.size(),
        true, true
    );

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    llama_kv_cache_seq_rm(g_ctx, -1, -1, -1);

    if (llama_decode(g_ctx, batch) != 0) {
        return;
    }

    int32_t n_predict = 1024; // can be parameterized later
    for (int i = 0; i < n_predict; ++i) {
        if (g_stop) break;

        llama_token new_token_id = llama_sampler_sample(nullptr, g_ctx, -1);
        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
            break;
        }

        std::string piece = llama_token_to_piece(llama_model_get_vocab(g_model), new_token_id);

        // Callback: onToken(String)
        jclass callbackCls = env->GetObjectClass(callback);
        jmethodID onTokenMethod = env->GetMethodID(callbackCls, "onToken", "(Ljava/lang/String;)V");
        jstring tokenJ = env->NewStringUTF(piece.c_str());
        env->CallVoidMethod(callback, onTokenMethod, tokenJ);
        env->DeleteLocalRef(tokenJ);
        env->DeleteLocalRef(callbackCls);

        llama_batch next_batch = llama_batch_get_one(&new_token_id, 1);
        if (llama_decode(g_ctx, next_batch) != 0) break;
    }

    // Callback: onEnd()
    jclass callbackCls = env->GetObjectClass(callback);
    jmethodID onEndMethod = env->GetMethodID(callbackCls, "onEnd", "()V");
    env->CallVoidMethod(callback, onEndMethod);
    env->DeleteLocalRef(callbackCls);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_yourapp_notesai_llm_LlmEngine_nativeStop(JNIEnv* env, jobject thiz) {
    g_stop = true;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_yourapp_notesai_llm_LlmEngine_nativeRelease(JNIEnv* env, jobject thiz) {
    g_stop = true;
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_free_model(g_model);
        g_model = nullptr;
    }
    llama_backend_free();
}
```

Adjust `Java_com_yourapp_notesai_llm_LlmEngine_*` to match your actual package/class.

______________________________________________________________________

## 4. Kotlin LLM service (add to existing codebase)

Create a new package: `com.yourapp.notesai.llm`.

### 4.1. LlmEngine.kt

```kotlin
// app/src/main/java/com/yourapp/notesai/llm/LlmEngine.kt
package com.yourapp.notesai.llm

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File

class LlmEngine {

    interface TokenCallback {
        fun onToken(token: String)
        fun onEnd()
    }

    init {
        System.loadLibrary("notesai_llm")
        nativeInit()
    }

    external fun nativeInit(): Int
    external fun nativeLoadModel(modelPath: String, ctxSize: Int, nThreads: Int): Int
    external fun nativeGenerate(prompt: String, callback: TokenCallback)
    external fun nativeStop()
    external fun nativeRelease()

    private var isLoaded = false

    fun loadModel(modelPath: String, config: LlmConfig) {
        val file = File(modelPath)
        require(file.exists()) { "Model file not found: $modelPath" }

        val result = nativeLoadModel(modelPath, config.contextSize, config.nThreads)
        if (result != 0) {
            throw IllegalStateException("Failed to load model, code=$result")
        }
        isLoaded = true
    }

    fun generateFlow(prompt: String): Flow<String> {
        check(isLoaded) { "Model not loaded" }
        val channel = Channel<String>(Channel.BUFFERED)

        val callback = object : TokenCallback {
            override fun onToken(token: String) {
                channel.trySend(token)
            }
            override fun onEnd() {
                channel.close()
            }
        }

        Thread {
            try {
                nativeGenerate(prompt, callback)
            } catch (e: Exception) {
                Log.e("LlmEngine", "Generation error", e)
                channel.close(e)
            }
        }.start()

        return channel.receiveAsFlow()
    }

    fun stop() {
        nativeStop()
    }

    fun release() {
        nativeRelease()
    }
}
```


### 4.2. LlmConfig.kt

```kotlin
// app/src/main/java/com/yourapp/notesai/llm/LlmConfig.kt
package com.yourapp.notesai.llm

sealed class ModelId(val id: String) {
    data object Qwen35_2B : ModelId("qwen35_2b")
    data object Phi4Mini_3_8B : ModelId("phi4_mini_3.8b")
}

data class LlmConfig(
    val modelId: ModelId,
    val contextSize: Int,
    val nThreads: Int = 4,
    val maxNewTokens: Int = 1024,
) {
    companion object {
        fun forModel(modelId: ModelId): LlmConfig = when (modelId) {
            ModelId.Qwen35_2B -> LlmConfig(
                modelId = modelId,
                contextSize = 24576, // 24k
                nThreads = 4,
                maxNewTokens = 1024,
            )
            ModelId.Phi4Mini_3_8B -> LlmConfig(
                modelId = modelId,
                contextSize = 20480, // 20k
                nThreads = 4,
                maxNewTokens = 1024,
            )
        }
    }
}
```

Integrate this with your existing DI (Hilt/Koin/manual) as a singleton.

______________________________________________________________________

## 5. Model management (extend existing Settings)

### 5.1. ModelInfo \& ModelRepository

Add:

```kotlin
// app/src/main/java/com/yourapp/notesai/data/ModelInfo.kt
package com.yourapp.notesai.data

import com.yourapp.notesai.llm.ModelId

data class ModelInfo(
    val id: ModelId,
    val name: String,
    val fileName: String,
    val hfRepo: String,
    val hfFile: String,
    val isOptional: Boolean,
) {
    val hfUrl: String
        get() = "https://huggingface.co/$hfRepo/resolve/main/$hfFile"
}
```

```kotlin
// app/src/main/java/com/yourapp/notesai/data/ModelRepository.kt
package com.yourapp.notesai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferences
import androidx.datastore.preferences.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import java.io.File

val Context.modelPrefs: DataStore<SharedPreferences> by preferencesDataStore(name = "model_prefs")

class ModelRepository(private val context: Context) {

    val models = listOf(
        ModelInfo(
            id = ModelId.Qwen35_2B,
            name = "Qwen3.5 2B (Default)",
            fileName = "qwen35_2b_instruct_q4.gguf",
            hfRepo = "unsloth/Qwen3.5-2B-GGUF",
            hfFile = "qwen3.5-2b-instruct-q4_k_m.gguf",
            isOptional = false,
        ),
        ModelInfo(
            id = ModelId.Phi4Mini_3_8B,
            name = "Phi‑4 mini 3.8B (Pro)",
            fileName = "phi4_mini_3.8b_instruct_q4.gguf",
            hfRepo = "YOUR_REPO/Phi-4-mini-GGUF", // replace
            hfFile = "phi-4-mini-instruct-q4_k_m.gguf",
            isOptional = true,
        ),
    )

    private val modelsDir: File by lazy {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    fun getModelPath(modelId: ModelId): File =
        models.first { it.id == modelId }.let { File(modelsDir, it.fileName) }

    fun isModelDownloaded(modelId: ModelId): Boolean =
        getModelPath(modelId).exists()

    suspend fun getActiveModelId(): ModelId? =
        context.modelPrefs.data.map { prefs ->
            val idStr = prefs.getString("active_model_id", null)
            idStr?.let { models.find { it.id.id == it }?.id }
        }

    suspend fun setActiveModelId(modelId: ModelId) {
        context.modelPrefs.edit { prefs ->
            prefs["active_model_id"] = modelId.id
        }
    }

    suspend fun downloadModel(
        modelId: ModelId,
        onProgress: (Float) -> Unit,
    ) {
        val info = models.first { it.id == modelId }
        val file = getModelPath(modelId)
        val url = info.hfUrl

        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.connect()
        val total = connection.contentLength
        var downloaded = 0L

        file.outputStream().use { output ->
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (total > 0) {
                        onProgress(downloaded.toFloat() / total)
                    }
                }
            }
        }
    }
}
```

Wire `ModelRepository` into your existing DI.

### 5.2. Extend Settings screen

In your existing `SettingsScreen.kt` (or equivalent):

- Inject `ModelRepository` and `LlmEngine`.
- Show a list of `modelRepo.models`.
- For each model:
    - Show name.
    - Show “Downloaded” / “Not downloaded”.
    - If optional and not downloaded: show **Download** button with progress.
    - If downloaded: show **Use** button (set active) and optionally **Delete**.
- Highlight the active model.

On “Use”:

```kotlin
viewModelScope.launch {
    modelRepo.setActiveModelId(model.id)
    val config = LlmConfig.forModel(model.id)
    val path = modelRepo.getModelPath(model.id).absolutePath
    llmEngine.loadModel(path, config)
}
```

No need to change other settings logic; just extend the UI.

______________________________________________________________________

## 6. RAG pipeline (add to existing notes logic)

Create a new package: `com.yourapp.notesai.rag`.

### 6.1. ChunkingService.kt

```kotlin
// app/src/main/java/com/yourapp/notesai/rag/ChunkingService.kt
package com.yourapp.notesai.rag

import kotlin.math.max

data class Chunk(
    val noteId: String,
    val title: String,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
)

class ChunkingService {

    private fun estimateTokens(text: String): Int = (text.length / 4.0).ceilToInt()

    fun chunkNote(
        noteId: String,
        title: String,
        content: String,
        targetChunkTokens: Int = 512,
        overlapRatio: Double = 0.15,
    ): List<Chunk> {
        val paragraphs = content
            .split("\n\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val chunks = mutableListOf<Chunk>()
        val targetChunkChars = targetChunkTokens * 4
        val overlapChars = (targetChunkChars * overlapRatio).toInt()

        var buffer = ""
        var startOffset = 0

        for (para in paragraphs) {
            if (buffer.isEmpty()) {
                startOffset = content.indexOf(para)
            }

            buffer += if (buffer.isEmpty()) para else "\n\n$para"

            if (estimateTokens(buffer) >= targetChunkTokens) {
                val endOffset = startOffset + buffer.length
                chunks += Chunk(
                    noteId = noteId,
                    title = title,
                    text = buffer,
                    startOffset = startOffset,
                    endOffset = endOffset,
                )

                val overlapStart = max(0, buffer.length - overlapChars)
                buffer = buffer.substring(overlapStart)
                startOffset = endOffset - buffer.length
            }
        }

        if (buffer.isNotEmpty()) {
            val endOffset = startOffset + buffer.length
            chunks += Chunk(
                noteId = noteId,
                title = title,
                text = buffer,
                startOffset = startOffset,
                endOffset = endOffset,
            )
        }

        return chunks
    }
}
```

Hook this into your existing notes repository:

- When a note is saved/updated:
    - Call `chunkingService.chunkNote(...)`.
    - Store chunks in a new table/collection (e.g. `chunks` table in Room).
- When a note is deleted:
    - Delete associated chunks.


### 6.2. RetrievalService.kt (MVP)

```kotlin
// app/src/main/java/com/yourapp/notesai/rag/RetrievalService.kt
package com.yourapp.notesai.rag

class RetrievalService(private val chunks: List<Chunk>) {

    fun retrieve(query: String, k: Int = 8): List<Chunk> {
        val qTerms = query
            .lowercase()
            .split(Regex("\\W+"))
            .filter { it.isNotEmpty() }

        if (qTerms.isEmpty()) return chunks.take(k)

        val scored = chunks.map { chunk ->
            val text = chunk.text.lowercase()
            val score = qTerms.count { text.contains(it) }
            Pair(chunk, score)
        }

        return scored
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
    }
}
```

Later, replace this with embeddings + vector search. [^4]

### 6.3. PromptBuilder.kt

```kotlin
// app/src/main/java/com/yourapp/notesai/rag/PromptBuilder.kt
package com.yourapp.notesai.rag

class PromptBuilder {

    data class RagPrompt(
        val system: String,
        val contextText: String,
        val userQuery: String,
    )

    fun buildRagPrompt(
        query: String,
        chunks: List<Chunk>,
        maxContextTokens: Int = 20000,
    ): RagPrompt {
        fun estimateTokens(s: String): Int = (s.length / 4.0).ceilToInt()

        val system =
            "You are a note assistant. Use ONLY the retrieved note chunks below to answer the user’s question.\n" +
            "If the answer cannot be found in the retrieved chunks, say so clearly instead of guessing.\n" +
            "Summarize and quote briefly when helpful. Keep answers concise and relevant to the user’s question."

        val sb = StringBuilder()
        var usedTokens = 0

        for (chunk in chunks) {
            val header = "[Chunk | Note: ${chunk.title}]\n"
            val chunkTokens = estimateTokens(header) + estimateTokens(chunk.text)
            if (usedTokens + chunkTokens > maxContextTokens) break

            sb.append(header)
            sb.append(chunk.text)
            sb.append("\n\n")
            usedTokens += chunkTokens
        }

        return RagPrompt(
            system = system,
            contextText = sb.toString(),
            userQuery = query,
        )
    }
}
```


______________________________________________________________________

## 7. “Ask about my notes” screen (new or extended)

Add a new Compose screen (e.g. `AskNotesScreen.kt`) or extend an existing “AI” screen.

Behavior:

1. User types a question.
2. On submit:
    - Fetch all chunks from your existing DB via `ChunkRepository`.
    - Create `RetrievalService(chunks)` and call `retrieve(query, k = 8)`.
    - Get active model ID from `ModelRepository`.
    - Build prompt via `PromptBuilder`, using `maxContextTokens` based on model:
        - Qwen3.5‑2B: ~20,000
        - Phi‑4‑mini: ~16,000–20,000
    - Call `LlmEngine.generateFlow(prompt)` and collect tokens into a `mutableStateOf(String)`.
3. Display streaming response in a chat‑like UI.

Example ViewModel sketch:

```kotlin
class AskNotesViewModel(
    private val llmEngine: LlmEngine,
    private val modelRepo: ModelRepository,
    private val chunkRepo: ChunkRepository,
    private val promptBuilder: PromptBuilder,
) : ViewModel() {

    var response by mutableStateOf("")
        private set

    fun ask(query: String) {
        val chunks = chunkRepo.getAllChunks()
        val retriever = RetrievalService(chunks)
        val top = retriever.retrieve(query, k = 8)

        val activeModelId = runBlocking { modelRepo.getActiveModelId() }
            ?: ModelId.Qwen35_2B

        val config = LlmConfig.forModel(activeModelId)
        val maxContextTokens = when (activeModelId) {
            ModelId.Qwen35_2B -> 20000
            ModelId.Phi4Mini_3_8B -> 16000
        }

        val promptObj = promptBuilder.buildRagPrompt(
            query = query,
            chunks = top,
            maxContextTokens = maxContextTokens,
        )

        val messagesPrompt = """
            SYSTEM:
            ${promptObj.system}

            CONTEXT:
            ${promptObj.contextText}

            USER:
            ${promptObj.userQuery}
        """.trimIndent()

        response = ""
        viewModelScope.launch {
            llmEngine.generateFlow(messagesPrompt).collect { token ->
                response += token
            }
        }
    }
}
```

Compose UI just observes `response` and renders it.

______________________________________________________________________

## 8. Model‑specific RAG \& generation settings (recap)

In `LlmConfig.forModel` and your RAG usage:

- **Qwen3.5‑2B**:
    - `contextSize = 24576`
    - `nThreads = 4`
    - `maxNewTokens = 1024`
    - RAG `maxContextTokens` ≈ 20,000
- **Phi‑4‑mini‑3.8B**:
    - `contextSize = 20480`
    - `nThreads = 4`
    - `maxNewTokens = 1024`
    - RAG `maxContextTokens` ≈ 16,000–20,000

You can later expose these as tunable parameters in Settings (advanced mode).

______________________________________________________________________

## 9. How to use this with your agentic Gemini AI

Give your agentic Gemini:

> “Here is my existing Android app (Kotlin + C++ native + Jetpack Compose).\
> Improve it by adding an offline RAG system with two local LLMs (Qwen3.5‑2B and Phi‑4‑mini‑3.8B), following this MVP brief:
>
> - Add a native llama.cpp module with JNI (use `examples/llama.android` as reference). [^6][^7][^1]
> - Add Kotlin classes: `LlmEngine`, `LlmConfig`, `ModelRepository`, `ChunkingService`, `RetrievalService`, `PromptBuilder`.
> - Extend the existing Settings screen to allow downloading and selecting the optional model.
> - Add or extend an ‘Ask about my notes’ screen that uses RAG + the selected LLM.
> - Keep all changes incremental and compatible with the existing notes data layer and UI theme.”

Then attach this spec as the design document.

If you share your current package structure or a few key files (e.g., existing `SettingsScreen.kt`, native `CMakeLists.txt`), I can further tailor this to show exact file paths and minimal diffs your agentic Gemini should apply.

<span style="display:none">[^10][^11][^12][^8][^9]</span>

<div align="center">⁂</div>

[^1]: https://www.ertas.ai/blog/llama-cpp-android-kotlin-integration-guide

[^2]: https://leeroopedia.com/index.php/Implementation:Ggml_org_Llama_cpp_Android_AI_Chat_JNI

[^3]: https://medium.com/@hello_98300/full-on-device-llama-3-2-inference-on-android-c2e0509787f0

[^4]: https://dev.to/software_mvp-factory/on-device-rag-for-android-4a7g

[^5]: https://medium.com/@hadiyolworld007/rag-mini-projects-a-personal-knowledge-assistant-713ceaa62288

[^6]: https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md

[^7]: https://github.com/ggml-org/llama.cpp/blob/master/examples/llama.android/llama/src/main/cpp/llama-android.cpp

[^8]: https://android-developers.googleblog.com/2026/05/android-ai-intelligence-system.html

[^9]: https://blog.csdn.net/gitblog_00644/article/details/150959036

[^10]: https://dev.to/software_mvp-factory/embedding-local-llms-in-your-mobile-app-544m

[^11]: https://dev.to/software_mvp-factory/on-device-llm-inference-via-kmp-and-llamacpp-4pec

[^12]: https://developer.android.com/jetpack/androidx/releases/compose

