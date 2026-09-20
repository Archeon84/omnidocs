#include <jni.h>
#include <android/log.h>
#include <vector>
#include <unordered_map>
#include <cmath>
#include <string>
#include <mutex>
#include <fstream>
#include <algorithm>

#include "usearch/usearch.hpp"

#define TAG "USearchNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct USearchIndexContext {
    using index_t = unum::usearch::index_gt<float, int64_t>;

    size_t dimensions = 384;
    int connectivity = 16;
    int expansion_add = 64;
    int expansion_search = 32;

    index_t index;
    std::unordered_map<int64_t, std::vector<float>> vectors;
    std::mutex mutex;

    struct CosineMetric {
        size_t dim;
        const USearchIndexContext* ctx;

        template <typename T>
        static inline auto extract_key_impl(const T& x, int) -> decltype(x.key()) {
            return x.key();
        }

        template <typename T>
        static inline auto extract_key_impl(const T& x, long) -> decltype(x.key) {
            return x.key;
        }

        template <typename T>
        static inline int64_t extract_key(const T& x) noexcept {
            return static_cast<int64_t>(extract_key_impl(x, 0));
        }

        const float* get_vector(int64_t key) const noexcept {
            auto it = ctx->vectors.find(key);
            if (it == ctx->vectors.end()) return nullptr;
            return it->second.data();
        }

        template <typename EntryT>
        float operator()(float* query, const EntryT& member) const noexcept {
            const float* vec = get_vector(extract_key(member));
            if (!vec) return 2.0f;
            return compute_cosine_distance(query, vec, dim);
        }

        template <typename EntryT>
        float operator()(const float* query, const EntryT& member) const noexcept {
            const float* vec = get_vector(extract_key(member));
            if (!vec) return 2.0f;
            return compute_cosine_distance(query, vec, dim);
        }

        template <typename EntryA, typename EntryB>
        float operator()(const EntryA& a, const EntryB& b) const noexcept {
            const float* vecA = get_vector(extract_key(a));
            const float* vecB = get_vector(extract_key(b));
            if (!vecA || !vecB) return 2.0f;
            return compute_cosine_distance(vecA, vecB, dim);
        }

        static inline float compute_cosine_distance(const float* a, const float* b, size_t dim) noexcept {
            float dot = 0.0f;
            float normA = 0.0f;
            float normB = 0.0f;
            for (size_t i = 0; i < dim; ++i) {
                dot += a[i] * b[i];
                normA += a[i] * a[i];
                normB += b[i] * b[i];
            }
            if (normA <= 0.0f || normB <= 0.0f) return 1.0f;
            float sim = dot / (std::sqrt(normA) * std::sqrt(normB));
            if (sim > 1.0f) sim = 1.0f;
            if (sim < -1.0f) sim = -1.0f;
            return 1.0f - sim;
        }
    };

    CosineMetric metric() const {
        return CosineMetric{dimensions, this};
    }

    USearchIndexContext(size_t dims, int conn, int exp_add, int exp_search)
        : dimensions(dims),
          connectivity(conn),
          expansion_add(exp_add),
          expansion_search(exp_search),
          index(unum::usearch::index_config_t(conn, conn * 2)) {
        unum::usearch::index_limits_t limits;
        limits.members = 64;
        limits.threads_add = 1;
        limits.threads_search = 1;
        index.reserve(limits);
    }
};

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_omnidocs_app_search_ann_USearchNative_nativeInit(
    JNIEnv* env, jclass,
    jint dimensions, jint connectivity, jint expansionAdd, jint expansionSearch) {
    try {
        auto* ctx = new USearchIndexContext(
            static_cast<size_t>(dimensions),
            connectivity, expansionAdd, expansionSearch
        );
        return reinterpret_cast<jlong>(ctx);
    } catch (const std::exception& e) {
        LOGE("nativeInit failed: %s", e.what());
        return 0;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_omnidocs_app_search_ann_USearchNative_nativeAdd(
    JNIEnv* env, jclass,
    jlong handle, jlong key, jfloatArray vectorArray) {
    auto* ctx = reinterpret_cast<USearchIndexContext*>(handle);
    if (!ctx) return JNI_FALSE;

    jsize len = env->GetArrayLength(vectorArray);
    if (static_cast<size_t>(len) != ctx->dimensions) {
        LOGE("nativeAdd: dimension mismatch (got %d, expected %zu)", len, ctx->dimensions);
        return JNI_FALSE;
    }

    jfloat* data = env->GetFloatArrayElements(vectorArray, nullptr);
    if (!data) return JNI_FALSE;

    bool success = false;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->vectors[key] = std::vector<float>(data, data + ctx->dimensions);

        if (ctx->index.size() + 1 >= ctx->index.capacity()) {
            unum::usearch::index_limits_t limits;
            limits.members = std::max(ctx->index.size() * 2, size_t(128));
            limits.threads_add = 1;
            limits.threads_search = 1;
            ctx->index.reserve(limits);
        }

        unum::usearch::index_update_config_t config;
        config.expansion = ctx->expansion_add;
        auto res = ctx->index.add(key, data, ctx->metric(), config);
        success = !res.error;
        if (!success) {
            LOGE("nativeAdd index.add error: %s", res.error.what());
        }
    }

    env->ReleaseFloatArrayElements(vectorArray, data, JNI_ABORT);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_omnidocs_app_search_ann_USearchNative_nativeSearch(
    JNIEnv* env, jclass,
    jlong handle, jfloatArray queryArray, jint wanted,
    jlongArray outKeys, jfloatArray outDistances) {
    auto* ctx = reinterpret_cast<USearchIndexContext*>(handle);
    if (!ctx || wanted <= 0) return 0;

    jsize queryLen = env->GetArrayLength(queryArray);
    if (static_cast<size_t>(queryLen) != ctx->dimensions) {
        LOGE("nativeSearch: dimension mismatch");
        return 0;
    }

    jfloat* queryData = env->GetFloatArrayElements(queryArray, nullptr);
    if (!queryData) return 0;

    std::vector<int64_t> keys;
    std::vector<float> distances;

    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        if (ctx->index.size() > 0) {
            unum::usearch::index_search_config_t config;
            config.expansion = ctx->expansion_search;
            auto res = ctx->index.search(queryData, static_cast<size_t>(wanted), ctx->metric(), config);
            if (!res.error) {
                size_t count = std::min(res.size(), static_cast<size_t>(wanted));
                keys.resize(count);
                distances.resize(count);
                for (size_t i = 0; i < count; ++i) {
                    auto match = res[i];
                    keys[i] = match.member.key;
                    distances[i] = match.distance;
                }
            } else {
                LOGE("nativeSearch error: %s", res.error.what());
            }
        }
    }

    env->ReleaseFloatArrayElements(queryArray, queryData, JNI_ABORT);

    jsize count = static_cast<jsize>(keys.size());
    if (count > 0) {
        env->SetLongArrayRegion(outKeys, 0, count, reinterpret_cast<const jlong*>(keys.data()));
        env->SetFloatArrayRegion(outDistances, 0, count, distances.data());
    }

    return count;
}

JNIEXPORT jboolean JNICALL
Java_com_omnidocs_app_search_ann_USearchNative_nativeSave(
    JNIEnv* env, jclass,
    jlong handle, jstring pathStr) {
    auto* ctx = reinterpret_cast<USearchIndexContext*>(handle);
    if (!ctx || !pathStr) return JNI_FALSE;

    const char* path = env->GetStringUTFChars(pathStr, nullptr);
    if (!path) return JNI_FALSE;

    bool ok = false;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        std::string graphPath = std::string(path) + ".usearch";
        std::string vecPath = std::string(path) + ".usearch.vec";

        auto res = ctx->index.save(graphPath.c_str());
        if (!res.error) {
            std::ofstream out(vecPath, std::ios::binary);
            if (out) {
                uint32_t magic = 0x55535643;
                uint32_t dim = static_cast<uint32_t>(ctx->dimensions);
                uint64_t count = static_cast<uint64_t>(ctx->vectors.size());
                out.write(reinterpret_cast<const char*>(&magic), sizeof(magic));
                out.write(reinterpret_cast<const char*>(&dim), sizeof(dim));
                out.write(reinterpret_cast<const char*>(&count), sizeof(count));

                for (const auto& kv : ctx->vectors) {
                    int64_t k = kv.first;
                    out.write(reinterpret_cast<const char*>(&k), sizeof(k));
                    out.write(reinterpret_cast<const char*>(kv.second.data()), sizeof(float) * dim);
                }
                out.close();
                ok = true;
            }
        } else {
            LOGE("nativeSave graph error: %s", res.error.what());
        }
    }

    env->ReleaseStringUTFChars(pathStr, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_omnidocs_app_search_ann_USearchNative_nativeLoad(
    JNIEnv* env, jclass,
    jlong handle, jstring pathStr) {
    auto* ctx = reinterpret_cast<USearchIndexContext*>(handle);
    if (!ctx || !pathStr) return JNI_FALSE;

    const char* path = env->GetStringUTFChars(pathStr, nullptr);
    if (!path) return JNI_FALSE;

    bool ok = false;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        std::string graphPath = std::string(path) + ".usearch";
        std::string vecPath = std::string(path) + ".usearch.vec";

        std::ifstream in(vecPath, std::ios::binary);
        if (in) {
            uint32_t magic = 0;
            uint32_t dim = 0;
            uint64_t count = 0;
            in.read(reinterpret_cast<char*>(&magic), sizeof(magic));
            in.read(reinterpret_cast<char*>(&dim), sizeof(dim));
            in.read(reinterpret_cast<char*>(&count), sizeof(count));

            if (magic == 0x55535643 && dim == ctx->dimensions) {
                ctx->vectors.clear();
                ctx->vectors.reserve(count);
                for (uint64_t i = 0; i < count; ++i) {
                    int64_t k = 0;
                    in.read(reinterpret_cast<char*>(&k), sizeof(k));
                    std::vector<float> v(dim);
                    in.read(reinterpret_cast<char*>(v.data()), sizeof(float) * dim);
                    ctx->vectors[k] = std::move(v);
                }
                in.close();

                auto res = ctx->index.load(graphPath.c_str());
                ok = !res.error;
                if (!ok) {
                    LOGE("nativeLoad graph error: %s", res.error.what());
                }
            } else {
                LOGE("nativeLoad vector format error (magic=0x%x, dim=%u)", magic, dim);
            }
        }
    }

    env->ReleaseStringUTFChars(pathStr, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_omnidocs_app_search_ann_USearchNative_nativeView(
    JNIEnv* env, jclass,
    jlong handle, jstring pathStr) {
    // For view, load the vectors and view the graph
    auto* ctx = reinterpret_cast<USearchIndexContext*>(handle);
    if (!ctx || !pathStr) return JNI_FALSE;

    const char* path = env->GetStringUTFChars(pathStr, nullptr);
    if (!path) return JNI_FALSE;

    bool ok = false;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        std::string graphPath = std::string(path) + ".usearch";
        std::string vecPath = std::string(path) + ".usearch.vec";

        std::ifstream in(vecPath, std::ios::binary);
        if (in) {
            uint32_t magic = 0;
            uint32_t dim = 0;
            uint64_t count = 0;
            in.read(reinterpret_cast<char*>(&magic), sizeof(magic));
            in.read(reinterpret_cast<char*>(&dim), sizeof(dim));
            in.read(reinterpret_cast<char*>(&count), sizeof(count));

            if (magic == 0x55535643 && dim == ctx->dimensions) {
                ctx->vectors.clear();
                ctx->vectors.reserve(count);
                for (uint64_t i = 0; i < count; ++i) {
                    int64_t k = 0;
                    in.read(reinterpret_cast<char*>(&k), sizeof(k));
                    std::vector<float> v(dim);
                    in.read(reinterpret_cast<char*>(v.data()), sizeof(float) * dim);
                    ctx->vectors[k] = std::move(v);
                }
                in.close();

                auto res = ctx->index.view(graphPath.c_str());
                ok = !res.error;
                if (!ok) {
                    LOGE("nativeView graph error: %s", res.error.what());
                }
            }
        }
    }

    env->ReleaseStringUTFChars(pathStr, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_omnidocs_app_search_ann_USearchNative_nativeSize(
    JNIEnv* env, jclass, jlong handle) {
    auto* ctx = reinterpret_cast<USearchIndexContext*>(handle);
    if (!ctx) return 0;
    std::lock_guard<std::mutex> lock(ctx->mutex);
    return static_cast<jint>(ctx->index.size());
}

JNIEXPORT jboolean JNICALL
Java_com_omnidocs_app_search_ann_USearchNative_nativeContains(
    JNIEnv* env, jclass, jlong handle, jlong key) {
    auto* ctx = reinterpret_cast<USearchIndexContext*>(handle);
    if (!ctx) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(ctx->mutex);
    return (ctx->vectors.find(key) != ctx->vectors.end()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_omnidocs_app_search_ann_USearchNative_nativeClose(
    JNIEnv* env, jclass, jlong handle) {
    auto* ctx = reinterpret_cast<USearchIndexContext*>(handle);
    if (ctx) {
        delete ctx;
    }
}

} // extern "C"
