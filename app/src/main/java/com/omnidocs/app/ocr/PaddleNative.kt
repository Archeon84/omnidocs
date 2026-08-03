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
