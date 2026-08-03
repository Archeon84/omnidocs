package com.omnidocs.app.ocr

import android.graphics.BitmapFactory
import android.graphics.Bitmap

/**
 * Helper class for loading images from C++ JNI code.
 * Called from paddle_ocr_jni.cpp via JNI static method.
 */
object BitmapHelper {
    /**
     * Load an image file and return pixel data as int array.
     * First two ints are [width, height], followed by ARGB pixel data.
     * Returns null on failure.
     */
    @JvmStatic
    fun loadImage(path: String): IntArray? {
        return try {
            val bitmap = BitmapFactory.decodeFile(path) ?: return null
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()

            // Return [width, height, pixel0, pixel1, ...]
            IntArray(2 + pixels.size).apply {
                this[0] = width
                this[1] = height
                System.arraycopy(pixels, 0, this, 2, pixels.size)
            }
        } catch (e: Exception) {
            null
        }
    }
}
