package com.omnidocs.app.search.ann

import android.util.Log

/**
 * JNI bindings for USearch embedded HNSW vector indexing.
 * Safely guards System.loadLibrary so JVM unit tests do not fail with UnsatisfiedLinkError.
 */
object USearchNative {
    private const val TAG = "USearchNative"

    var isNativeLoaded = false
        private set

    init {
        try {
            System.loadLibrary("usearch-android")
            isNativeLoaded = true
            Log.i(TAG, "Successfully loaded native library usearch-android for USearch")
        } catch (t: Throwable) {
            try {
                System.loadLibrary("llama-android")
                isNativeLoaded = true
                Log.i(TAG, "Successfully loaded legacy library llama-android for USearch")
            } catch (t2: Throwable) {
                isNativeLoaded = false
                Log.w(TAG, "USearchNative library not available. Falling back to Kotlin HNSW.")
            }
        }
    }

    @JvmStatic
    external fun nativeInit(
        dimensions: Int,
        connectivity: Int,
        expansionAdd: Int,
        expansionSearch: Int
    ): Long

    @JvmStatic
    external fun nativeAdd(handle: Long, key: Long, vector: FloatArray): Boolean

    @JvmStatic
    external fun nativeSearch(
        handle: Long,
        query: FloatArray,
        wanted: Int,
        outKeys: LongArray,
        outDistances: FloatArray
    ): Int

    @JvmStatic
    external fun nativeSave(handle: Long, path: String): Boolean

    @JvmStatic
    external fun nativeLoad(handle: Long, path: String): Boolean

    @JvmStatic
    external fun nativeView(handle: Long, path: String): Boolean

    @JvmStatic
    external fun nativeSize(handle: Long): Int

    @JvmStatic
    external fun nativeContains(handle: Long, key: Long): Boolean

    @JvmStatic
    external fun nativeClose(handle: Long)
}
