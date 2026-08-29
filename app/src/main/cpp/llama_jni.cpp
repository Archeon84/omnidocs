#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <chrono>
#include <thread>
#include <cmath>
#include <android/log.h>
#include "llama.h"

#define TAG "LlamaCpp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model* model = nullptr;
static llama_context* ctx = nullptr;
static const llama_vocab* vocab = nullptr;
static bool is_initialized = false;
static bool backend_initialized = false;
static bool g_add_bos = false; // Set per model: Qwen3=false, Llama3=true

// Embedding model/context — a SEPARATE GGUF from the generative model above.
// Both share the process-wide llama backend (llama_backend_init once), so the
// backend is only freed when BOTH contexts are gone (see nativeFree and
// nativeEmbeddingFree). All four embedding natives also take g_llama_mutex so
// embedding and generation serialize (embedding decode is fast, so the cost is
// negligible and avoids oversubscribing CPU threads).
static llama_model* embedding_model = nullptr;
static llama_context* embedding_ctx = nullptr;
static const llama_vocab* embedding_vocab = nullptr;
static bool embedding_initialized = false;
static int32_t embedding_n_embd = 0;

// Timed mutex protecting all access to the shared model/context/vocab globals.
// llama.cpp is NOT thread-safe for concurrent inference on the same context,
// and nativeInit / nativeFree must not race with generate calls.
//
// Using std::timed_mutex prevents indefinite blocking when a previous JNI call
// was cancelled by Kotlin's withTimeoutOrNull but the native thread is still
// running (Kotlin cannot cancel blocking JNI calls — the IO thread is leaked).
static std::timed_mutex g_llama_mutex;

/**
 * Acquire the timed mutex with a timeout. Returns false if the lock could not
 * be acquired within the timeout, meaning a previous JNI call is likely stuck.
 * The caller should return an error gracefully rather than blocking forever.
 */
static bool acquire_mutex(std::unique_lock<std::timed_mutex>& lock,
                           std::chrono::seconds timeout) {
    if (!lock.try_lock_for(timeout)) {
        LOGE("Mutex acquire timed out after %llds — previous call stuck?",
             (long long)timeout.count());
        return false;
    }
    return true;
}

extern "C" {

    JNIEXPORT jboolean JNICALL
    Java_com_omnidocs_app_ai_LlamaCppService_nativeInit(
        JNIEnv* env,
        jobject thiz,
        jstring modelPath,
        jboolean addBos
    ) {
        const char* path = env->GetStringUTFChars(modelPath, nullptr);
        LOGI("Loading model from: %s (add_bos=%d)", path, addBos);
        g_add_bos = addBos;

        try {
            // Acquire timed mutex — if a previous nativeInit call is still stuck
            // (e.g., Kotlin withTimeoutOrNull fired but couldn't cancel the JNI
            // thread), bail out after 30s instead of blocking forever.
            std::unique_lock<std::timed_mutex> lock(g_llama_mutex, std::defer_lock);
            if (!acquire_mutex(lock, std::chrono::seconds(30))) {
                LOGE("nativeInit: mutex timeout, previous call stuck");
                env->ReleaseStringUTFChars(modelPath, path);
                return JNI_FALSE;
            }

            // Clear stale thread-interrupt flag left by a previous
            // withTimeoutOrNull cancellation on this thread.
            // JNI calls do not check Thread.interrupted(), so the flag persists
            // across thread-pool reuse. We attempt to detect this and clear it.
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }

            // Initialize llama backend (only once per process)
            if (!backend_initialized) {
                llama_backend_init();
                backend_initialized = true;
            }

            // Clean up any stale state from a previous partial init
            if (ctx) {
                llama_free(ctx);
                ctx = nullptr;
            }
            if (model) {
                llama_model_free(model);
                model = nullptr;
            }
            vocab = nullptr;
            is_initialized = false;

            // Set model parameters
            auto model_params = llama_model_default_params();
            model_params.n_gpu_layers = 0; // CPU only for now

            // Load model
            model = llama_model_load_from_file(path, model_params);
            if (!model) {
                LOGE("Failed to load model");
                env->ReleaseStringUTFChars(modelPath, path);
                return JNI_FALSE;
            }

            // Get vocabulary
            vocab = llama_model_get_vocab(model);

            // Create context
            auto ctx_params = llama_context_default_params();
            ctx_params.n_ctx = 2048;
            ctx_params.n_batch = 512;
            ctx_params.n_ubatch = 512;
            // Auto-detect CPU cores; leave 1-2 free for the OS/UI thread.
            // hardware_concurrency() returns 0 on failure — fall back to 4.
            unsigned int hw_threads = std::thread::hardware_concurrency();
            int n_threads = hw_threads > 2 ? hw_threads - 2 : (hw_threads > 0 ? hw_threads : 4);
            LOGI("Using %d threads (hw=%u)", n_threads, hw_threads);
            ctx_params.n_threads = n_threads;

            ctx = llama_init_from_model(model, ctx_params);
            if (!ctx) {
                LOGE("Failed to create context");
                llama_model_free(model);
                model = nullptr;
                env->ReleaseStringUTFChars(modelPath, path);
                return JNI_FALSE;
            }

            is_initialized = true;
            LOGI("Model loaded successfully");
            env->ReleaseStringUTFChars(modelPath, path);
            return JNI_TRUE;

        } catch (const std::exception& e) {
            LOGE("Exception loading model: %s", e.what());
            env->ReleaseStringUTFChars(modelPath, path);
            return JNI_FALSE;
        }
    }

    JNIEXPORT jstring JNICALL
    Java_com_omnidocs_app_ai_LlamaCppService_nativeGenerate(
        JNIEnv* env,
        jobject thiz,
        jstring prompt,
        jint maxTokens
    ) {
        std::unique_lock<std::timed_mutex> lock(g_llama_mutex, std::defer_lock);
        if (!acquire_mutex(lock, std::chrono::seconds(10))) {
            LOGE("nativeGenerate: mutex timeout, previous call stuck");
            return env->NewStringUTF("");
        }

        if (!is_initialized || !model || !ctx || !vocab) {
            LOGE("Model not initialized");
            return env->NewStringUTF("");
        }

        const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
        LOGI("Generating response for prompt: %.80s...", promptStr);

        // Declared outside try so the catch block can free them on exception
        llama_sampler* sampler = nullptr;
        struct llama_batch gen_batch = {};

        try {
            // BOS token: Qwen3 ChatML does NOT want it (self-delimiting tokens),
            // but Llama 3.2 REQUIRES it. The value is set per-model via nativeInit.
            const bool add_bos = g_add_bos;

            // ── Tokenize prompt ──────────────────────────────────────────────
            // In b9976, llama_tokenize with nullptr/0 returns the NEGATED
            // required token count when the buffer is too small. Negate it
            // to get the actual number of tokens needed.
            int n_tokens = llama_tokenize(
                vocab, promptStr, strlen(promptStr), nullptr, 0, add_bos, true);
            if (n_tokens < 0) {
                n_tokens = -n_tokens; // negate to get required size
            }
            LOGI("Tokenized prompt: %d tokens", n_tokens);
            if (n_tokens == 0) {
                LOGE("Failed to tokenize prompt (first pass)");
                env->ReleaseStringUTFChars(prompt, promptStr);
                return env->NewStringUTF("");
            }

            auto tokens = std::vector<llama_token>(n_tokens);
            int n_tokenized = llama_tokenize(
                vocab, promptStr, strlen(promptStr),
                tokens.data(), tokens.size(), add_bos, true);
            if (n_tokenized < 0) {
                LOGE("Failed to tokenize prompt (second pass): %d", n_tokenized);
                env->ReleaseStringUTFChars(prompt, promptStr);
                return env->NewStringUTF("");
            }

            // Context limit guard (n_ctx = 2048): truncate prompt if too long to prevent decode crash
            const int max_ctx = 2048;
            const int max_prompt_tokens = max_ctx - 128;
            if (n_tokenized > max_prompt_tokens) {
                LOGI("Prompt tokens (%d) exceed safe limit (%d), truncating tokens", n_tokenized, max_prompt_tokens);
                n_tokenized = max_prompt_tokens;
                tokens.resize(n_tokenized);
            }

            // ── Evaluate prompt ──────────────────────────────────────────────
            // Clear KV cache so each generate() call starts fresh — otherwise
            // residual KV entries from a prior call pollute the new inference.
            llama_memory_clear(llama_get_memory(ctx), true);

            // Decode the prompt in chunks to avoid long stalls on large prompts.
            // A single llama_decode with 600+ tokens on a phone CPU can take
            // minutes; splitting into small chunks keeps each decode under a
            // few seconds and prevents the C++ timeout from firing.
            // NOTE: On mid-range phones, even 256 tokens can stall for 60s+.
            // 64 tokens keeps each llama_decode call under ~5s on most devices.
            constexpr int PROMPT_CHUNK_SIZE = 64;
            {
                int n_past = 0;
                auto t_decode_start = std::chrono::steady_clock::now();

                while (n_past < n_tokenized) {
                    int chunk_size = n_tokenized - n_past;
                    if (chunk_size > PROMPT_CHUNK_SIZE) {
                        chunk_size = PROMPT_CHUNK_SIZE;
                    }

                    auto batch = llama_batch_init(chunk_size, 0, 1);
                    if (!batch.token || !batch.pos || !batch.n_seq_id || !batch.seq_id || !batch.logits) {
                        LOGE("Failed to allocate batch for %d tokens", chunk_size);
                        llama_batch_free(batch);
                        env->ReleaseStringUTFChars(prompt, promptStr);
                        return env->NewStringUTF("");
                    }
                    batch.n_tokens = chunk_size;
                    for (int i = 0; i < chunk_size; i++) {
                        batch.token[i] = tokens[n_past + i];
                        batch.pos[i] = n_past + i;
                        batch.n_seq_id[i] = 1;
                        batch.seq_id[i][0] = 0;
                        batch.logits[i] = 0;
                    }
                    // Set logits flag on the LAST token of the LAST chunk
                    // only — that's the token whose logits we sample from.
                    if (n_past + chunk_size == n_tokenized) {
                        batch.logits[chunk_size - 1] = 1;
                    }

                    LOGI("Decoding chunk %d-%d (%d tokens)...",
                         n_past, n_past + chunk_size - 1, chunk_size);
                    auto t0 = std::chrono::steady_clock::now();
                    int32_t ret = llama_decode(ctx, batch);
                    auto t1 = std::chrono::steady_clock::now();
                    auto chunk_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
                    auto total_decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                        t1 - t_decode_start).count();

                    if (ret) {
                        LOGE("Failed to decode chunk at offset %d (%d tokens, ret=%d)",
                             n_past, chunk_size, ret);
                        llama_batch_free(batch);
                        env->ReleaseStringUTFChars(prompt, promptStr);
                        return env->NewStringUTF("");
                    }

                    LOGI("Chunk decoded: offset %d, %lldms", n_past, chunk_ms);
                    n_past += chunk_size;
                    llama_batch_free(batch);

                    // Safety: if prompt decode exceeds 120s total, abort to
                    // prevent the Kotlin coroutine from being stuck forever.
                    if (total_decode_ms > 120000) {
                        LOGE("Prompt decode timed out after %lldms (%d/%d tokens)",
                             total_decode_ms, n_past, n_tokenized);
                        env->ReleaseStringUTFChars(prompt, promptStr);
                        return env->NewStringUTF("");
                    }
                }

                auto t_decode_end = std::chrono::steady_clock::now();
                auto final_decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                    t_decode_end - t_decode_start).count();
                LOGI("Prompt decoded: %d tokens in %lldms", n_tokenized, final_decode_ms);
            }

            LOGI("Prompt decoded, generating response (max %d tokens)...", maxTokens);

            // ── Sampler chain ────────────────────────────────────────────────
            // Order: top_k → top_p → temp → dist matches the official
            // llama.cpp sampling chain. The dist (distribution) sampler is
            // REQUIRED — it performs the actual token sampling from the
            // probability distribution. Without it, llama_sampler_sample() aborts.
            auto sparams = llama_sampler_chain_default_params();
            sparams.no_perf = true; // we don't read the internal performance counters
            sampler = llama_sampler_chain_init(sparams);
            if (!sampler) {
                LOGE("Failed to create sampler chain");
                env->ReleaseStringUTFChars(prompt, promptStr);
                return env->NewStringUTF("");
            }
            llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
            llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
            llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
            llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

            // ── Generation loop ──────────────────────────────────────────────
            std::string response;
            gen_batch = llama_batch_init(1, 0, 1);
            auto t_start = std::chrono::steady_clock::now();
            int tokens_generated = 0;
            int n_past = n_tokenized;

            for (int i = 0; i < maxTokens; i++) {
                // Ensure we never exceed context size (2048) to prevent assertion / memory corruption
                if (n_past >= max_ctx - 2) {
                    LOGI("Generation reached context ceiling (%d tokens)", n_past);
                    break;
                }

                // C++-side safety timeout: 180 seconds total generation time
                // (increased from 90s because Qwen3 generates thinking tokens
                // that eat into the time budget before the actual answer)
                auto t_now = std::chrono::steady_clock::now();
                auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(t_now - t_start).count();
                if (elapsed > 180000) {
                    LOGI("Generation timed out at %d tokens (%lldms)", tokens_generated, elapsed);
                    break;
                }

                // Sample next token from logits at the last decoded position
                llama_token new_token = llama_sampler_sample(sampler, ctx, -1);

                // Check for end-of-generation (e.g. <|im_end|> or <|endoftext|>)
                if (llama_vocab_is_eog(vocab, new_token)) {
                    LOGI("Hit EOG after %d tokens (%lldms)", tokens_generated, elapsed);
                    break;
                }

                // Convert token to text — use the fixed buffer pattern:
                //   n < 0  → buffer too small, required size is -n
                //   n == 0 → empty piece (skip silently)
                //   n > 0  → valid text of length n
                char buf[256];
                int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
                if (n < 0) {
                    // Buffer too small — allocate exact size
                    std::vector<char> big_buf(static_cast<size_t>(-n) + 1);
                    n = llama_token_to_piece(vocab, new_token, big_buf.data(), big_buf.size(), 0, true);
                    if (n > 0) {
                        response.append(big_buf.data(), static_cast<size_t>(n));
                    }
                } else if (n > 0) {
                    response.append(buf, static_cast<size_t>(n));
                }
                tokens_generated++;

                // Decode this token to advance the KV cache for the next iteration.
                // Explicitly set pos/n_seq_id/seq_id — llama_batch_init uses malloc
                // and the batch pre-processor skips auto-generation when pointers are non-null.
                gen_batch.n_tokens = 1;
                gen_batch.token[0] = new_token;
                gen_batch.pos[0] = n_past;
                gen_batch.n_seq_id[0] = 1;
                gen_batch.seq_id[0][0] = 0;
                gen_batch.logits[0] = 1;
                n_past++;

                if (llama_decode(ctx, gen_batch)) {
                    LOGE("Failed to decode token at position %d", i);
                    break;
                }

                // Log progress every 32 tokens so logcat shows we're alive
                if ((i + 1) % 32 == 0) {
                    auto t_progress = std::chrono::steady_clock::now();
                    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_progress - t_start).count();
                    LOGI("Progress: %d/%d tokens, %zu chars, %lldms elapsed",
                         i + 1, maxTokens, response.length(), ms);
                }
            }

            auto t_end = std::chrono::steady_clock::now();
            auto total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_end - t_start).count();

            llama_batch_free(gen_batch);
            llama_sampler_free(sampler);
            env->ReleaseStringUTFChars(prompt, promptStr);

            LOGI("=== Generation complete: %d tokens in %lldms (%lldms/tok), %zu chars ===",
                 tokens_generated, total_ms,
                 tokens_generated > 0 ? total_ms / tokens_generated : 0,
                 response.length());
            if (!response.empty()) {
                LOGI("Response preview: %.120s", response.c_str());
            }

            return env->NewStringUTF(response.c_str());

        } catch (const std::exception& e) {
            LOGE("Exception generating response: %s", e.what());
            // Free resources that may have been allocated before the exception
            if (gen_batch.token) llama_batch_free(gen_batch);
            if (sampler)         llama_sampler_free(sampler);
            env->ReleaseStringUTFChars(prompt, promptStr);
            return env->NewStringUTF("");
        }
    }

    JNIEXPORT void JNICALL
    Java_com_omnidocs_app_ai_LlamaCppService_nativeFree(
        JNIEnv* env,
        jobject thiz
    ) {
        std::unique_lock<std::timed_mutex> lock(g_llama_mutex, std::defer_lock);
        if (!acquire_mutex(lock, std::chrono::seconds(5))) {
            LOGE("nativeFree: mutex timeout, cannot free");
            return;
        }

        try {
            if (ctx) {
                llama_free(ctx);
                ctx = nullptr;
            }
            if (model) {
                llama_model_free(model);
                model = nullptr;
            }
            vocab = nullptr;
            is_initialized = false;
            // The llama backend is process-wide and shared with the embedding
            // context. Only free it when the embedding context is also gone,
            // otherwise freeing would invalidate a live embedding model.
            if (embedding_ctx == nullptr) {
                llama_backend_free();
                backend_initialized = false;
            }
            LOGI("Model freed");
        } catch (const std::exception& e) {
            LOGE("Exception freeing model: %s", e.what());
        }
    }

    JNIEXPORT jlong JNICALL
    Java_com_omnidocs_app_ai_LlamaCppService_nativeGetMemoryUsage(
        JNIEnv* env,
        jobject thiz
    ) {
        std::unique_lock<std::timed_mutex> lock(g_llama_mutex, std::defer_lock);
        if (!acquire_mutex(lock, std::chrono::seconds(5))) {
            LOGE("nativeGetMemoryUsage: mutex timeout");
            return 0;
        }
        if (!model) return 0;
        return llama_model_size(model);
    }

    // ── Embedding natives (class com.omnidocs.app.ai.EmbeddingEngine) ─────────
    // A separate GGUF embedding model (e.g. multilingual-e5-small) loaded into
    // its own context. Uses mean pooling to produce one L2-normalized vector per
    // text. Mirrors the official llama.cpp examples/embedding/embedding.cpp.

    JNIEXPORT jboolean JNICALL
    Java_com_omnidocs_app_ai_EmbeddingEngine_nativeEmbeddingInit(
        JNIEnv* env,
        jobject thiz,
        jstring modelPath
    ) {
        const char* path = env->GetStringUTFChars(modelPath, nullptr);
        LOGI("Loading embedding model from: %s", path);

        try {
            std::unique_lock<std::timed_mutex> lock(g_llama_mutex, std::defer_lock);
            if (!acquire_mutex(lock, std::chrono::seconds(30))) {
                LOGE("nativeEmbeddingInit: mutex timeout, previous call stuck");
                env->ReleaseStringUTFChars(modelPath, path);
                return JNI_FALSE;
            }

            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }

            // Initialize the backend once per process (shared with generative model).
            if (!backend_initialized) {
                llama_backend_init();
                backend_initialized = true;
            }

            // Clean up any stale embedding state from a previous partial init.
            // Do NOT touch the generative model/ctx globals — they are separate.
            if (embedding_ctx) {
                llama_free(embedding_ctx);
                embedding_ctx = nullptr;
            }
            if (embedding_model) {
                llama_model_free(embedding_model);
                embedding_model = nullptr;
            }
            embedding_vocab = nullptr;
            embedding_initialized = false;
            embedding_n_embd = 0;

            auto model_params = llama_model_default_params();
            model_params.n_gpu_layers = 0; // CPU only

            embedding_model = llama_model_load_from_file(path, model_params);
            if (!embedding_model) {
                LOGE("Failed to load embedding model");
                env->ReleaseStringUTFChars(modelPath, path);
                return JNI_FALSE;
            }

            embedding_vocab = llama_model_get_vocab(embedding_model);

            auto ctx_params = llama_context_default_params();
            ctx_params.n_ctx = 512;   // e5-small max context; notes are far shorter
            ctx_params.n_batch = 512;
            ctx_params.n_ubatch = 512;
            // Mean pooling over the sequence produces the sentence embedding.
            ctx_params.pooling_type = LLAMA_POOLING_TYPE_MEAN;
            ctx_params.embeddings = true;
            unsigned int hw_threads = std::thread::hardware_concurrency();
            int n_threads = hw_threads > 2 ? hw_threads - 2 : (hw_threads > 0 ? hw_threads : 4);
            ctx_params.n_threads = n_threads;

            embedding_ctx = llama_init_from_model(embedding_model, ctx_params);
            if (!embedding_ctx) {
                LOGE("Failed to create embedding context");
                llama_model_free(embedding_model);
                embedding_model = nullptr;
                env->ReleaseStringUTFChars(modelPath, path);
                return JNI_FALSE;
            }

            embedding_n_embd = llama_model_n_embd_out(embedding_model);
            embedding_initialized = true;
            LOGI("Embedding model loaded (dim=%d)", embedding_n_embd);
            env->ReleaseStringUTFChars(modelPath, path);
            return JNI_TRUE;

        } catch (const std::exception& e) {
            LOGE("Exception loading embedding model: %s", e.what());
            env->ReleaseStringUTFChars(modelPath, path);
            return JNI_FALSE;
        }
    }

    JNIEXPORT jint JNICALL
    Java_com_omnidocs_app_ai_EmbeddingEngine_nativeEmbeddingDim(
        JNIEnv* env,
        jobject thiz
    ) {
        return embedding_n_embd;
    }

    JNIEXPORT jfloatArray JNICALL
    Java_com_omnidocs_app_ai_EmbeddingEngine_nativeEmbed(
        JNIEnv* env,
        jobject thiz,
        jstring text
    ) {
        LOGI("nativeEmbed: called");
        std::unique_lock<std::timed_mutex> lock(g_llama_mutex, std::defer_lock);
        if (!acquire_mutex(lock, std::chrono::seconds(10))) {
            LOGE("nativeEmbed: mutex timeout, previous call stuck");
            return env->NewFloatArray(0);
        }
        LOGI("nativeEmbed: mutex acquired");

        if (!embedding_initialized || !embedding_model || !embedding_ctx || !embedding_vocab) {
            LOGE("Embedding model not initialized (init=%d model=%p ctx=%p vocab=%p)",
                 embedding_initialized, embedding_model, embedding_ctx, embedding_vocab);
            return env->NewFloatArray(0);
        }
        LOGI("nativeEmbed: model initialized OK");

        const char* textStr = env->GetStringUTFChars(text, nullptr);
        const int textLen = static_cast<int>(strlen(textStr));
        LOGI("nativeEmbed: input text length=%d", textLen);

        try {
            // Tokenize with add_special=true (adds BOS + EOS as configured in the
            // GGUF tokenizer) — the canonical embedding.cpp recipe. Two-pass:
            // first with nullptr to get the required token count.
            int n_tokens = llama_tokenize(
                embedding_vocab, textStr, textLen, nullptr, 0, true, true);
            if (n_tokens < 0) {
                n_tokens = -n_tokens;
            }
            if (n_tokens == 0) {
                LOGE("Failed to tokenize embedding input (first pass returned 0)");
                env->ReleaseStringUTFChars(text, textStr);
                return env->NewFloatArray(0);
            }
            LOGI("nativeEmbed: tokenize first pass n_tokens=%d", n_tokens);

            // Guard against context overflow: truncate to n_ctx - 1 tokens.
            const int max_tokens = 512 - 1;
            if (n_tokens > max_tokens) {
                n_tokens = max_tokens;
            }

            auto tokens = std::vector<llama_token>(n_tokens + 1); // +1 for possible EOS append
            int n_tokenized = llama_tokenize(
                embedding_vocab, textStr, textLen,
                tokens.data(), n_tokens, true, true);
            if (n_tokenized < 0) {
                LOGE("Failed to tokenize embedding input (second pass): %d", n_tokenized);
                env->ReleaseStringUTFChars(text, textStr);
                return env->NewFloatArray(0);
            }
            LOGI("nativeEmbed: tokenize second pass n_tokenized=%d", n_tokenized);

            // Ensure the last token is EOS/SEP (embedding.cpp warns if not).
            if (n_tokenized > 0 && !llama_vocab_is_eog(embedding_vocab, tokens[n_tokenized - 1])) {
                llama_token eos = llama_vocab_eos(embedding_vocab);
                if (eos != LLAMA_TOKEN_NULL) {
                    tokens[n_tokenized] = eos;
                    n_tokenized++;
                }
            }

            // Build the batch with logits=1 on every token so embeddings are
            // extracted for the whole sequence.
            auto batch = llama_batch_init(n_tokenized, 0, 1);
            if (!batch.token || !batch.pos || !batch.n_seq_id || !batch.seq_id || !batch.logits) {
                LOGE("Failed to allocate embedding batch for %d tokens", n_tokenized);
                llama_batch_free(batch);
                env->ReleaseStringUTFChars(text, textStr);
                return env->NewFloatArray(0);
            }
            LOGI("nativeEmbed: batch allocated OK for %d tokens", n_tokenized);
            batch.n_tokens = n_tokenized;
            for (int i = 0; i < n_tokenized; i++) {
                batch.token[i] = tokens[i];
                batch.pos[i] = i;
                batch.n_seq_id[i] = 1;
                batch.seq_id[i][0] = 0;
                batch.logits[i] = 1;
            }

            // Clear KV cache (irrelevant for embeddings but prevents residue).
            llama_memory_clear(llama_get_memory(embedding_ctx), true);

            int ret = llama_decode(embedding_ctx, batch);
            if (ret != 0) {
                LOGE("Failed to decode embedding batch (ret=%d)", ret);
                llama_batch_free(batch);
                env->ReleaseStringUTFChars(text, textStr);
                return env->NewFloatArray(0);
            }
            LOGI("nativeEmbed: decode OK (ret=%d)", ret);

            const float* embd = llama_get_embeddings_seq(embedding_ctx, 0);
            if (!embd) {
                LOGE("llama_get_embeddings_seq returned NULL (pooling type NONE?)");
                llama_batch_free(batch);
                env->ReleaseStringUTFChars(text, textStr);
                return env->NewFloatArray(0);
            }
            LOGI("nativeEmbed: embeddings extracted OK, dim=%d", embedding_n_embd);

            // L2-normalize into the output jfloatArray.
            const int dim = embedding_n_embd;
            float norm = 0.0f;
            for (int i = 0; i < dim; i++) {
                norm += embd[i] * embd[i];
            }
            norm = sqrtf(norm);
            if (norm < 1e-8f) norm = 1.0f;

            jfloatArray result = env->NewFloatArray(dim);
            if (result == nullptr) {
                llama_batch_free(batch);
                env->ReleaseStringUTFChars(text, textStr);
                return nullptr;
            }
            std::vector<float> normalized(dim);
            for (int i = 0; i < dim; i++) {
                normalized[i] = embd[i] / norm;
            }
            env->SetFloatArrayRegion(result, 0, dim, normalized.data());

            llama_batch_free(batch);
            env->ReleaseStringUTFChars(text, textStr);
            LOGI("nativeEmbed: success, returned %d-dim embedding (norm=%.4f)", dim, norm);
            return result;

        } catch (const std::exception& e) {
            LOGE("Exception embedding text: %s", e.what());
            env->ReleaseStringUTFChars(text, textStr);
            return env->NewFloatArray(0);
        }
    }

    JNIEXPORT void JNICALL
    Java_com_omnidocs_app_ai_EmbeddingEngine_nativeEmbeddingFree(
        JNIEnv* env,
        jobject thiz
    ) {
        std::unique_lock<std::timed_mutex> lock(g_llama_mutex, std::defer_lock);
        if (!acquire_mutex(lock, std::chrono::seconds(5))) {
            LOGE("nativeEmbeddingFree: mutex timeout, cannot free");
            return;
        }

        try {
            if (embedding_ctx) {
                llama_free(embedding_ctx);
                embedding_ctx = nullptr;
            }
            if (embedding_model) {
                llama_model_free(embedding_model);
                embedding_model = nullptr;
            }
            embedding_vocab = nullptr;
            embedding_initialized = false;
            embedding_n_embd = 0;
            // Only free the process-wide backend when the generative context is
            // also gone (mirrors the guard in nativeFree).
            if (ctx == nullptr) {
                llama_backend_free();
                backend_initialized = false;
            }
            LOGI("Embedding model freed");
        } catch (const std::exception& e) {
            LOGE("Exception freeing embedding model: %s", e.what());
        }
    }

} // extern "C"
