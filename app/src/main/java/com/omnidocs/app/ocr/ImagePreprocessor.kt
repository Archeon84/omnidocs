package com.omnidocs.app.ocr

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ImagePreprocessor"
private const val MAX_DIMENSION = 2048 // Downsample images larger than this

/**
 * Optimized image processor for OCR preprocessing.
 *
 * Uses bulk pixel array operations ([getPixels]/[setPixels]) instead of
 * per-pixel [getPixel]/[setPixel] JNI calls. For a 12 MP image this reduces
 * ~48 M JNI bridge calls to 2 (one read, one write).
 *
 * All public methods are [suspend] and run on [Dispatchers.Default] for CPU work.
 */
@Singleton
class ImagePreprocessor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Grayscale conversion with contrast enhancement.
     * Downsamples large images to [MAX_DIMENSION] on the longest side first.
     */
    suspend fun preprocessForOcr(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val input = downsample(bitmap)
        val width = input.width
        val height = input.height
        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = IntArray(width * height)

        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[i]
                val gray = luma(pixel)
                val enhanced = enhanceContrast(gray)
                out[i] = (0xFF shl 24) or (enhanced shl 16) or (enhanced shl 8) or enhanced
                i++
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, width, 0, 0, width, height)
        result
    }

    /**
     * Binarize (black & white) using a fixed threshold.
     */
    suspend fun binarizeImage(bitmap: Bitmap, threshold: Int = 128): Bitmap = withContext(Dispatchers.Default) {
        val input = downsample(bitmap)
        val width = input.width
        val height = input.height
        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = IntArray(width * height)

        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[i]
                val gray = luma(pixel)
                val bw = if (gray > threshold) 255 else 0
                out[i] = (0xFF shl 24) or (bw shl 16) or (bw shl 8) or bw
                i++
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, width, 0, 0, width, height)
        result
    }

    /**
     * 3×3 sharpen kernel applied via bulk array ops (not per-pixel JNI).
     */
    suspend fun sharpenImage(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val input = downsample(bitmap)
        val width = input.width
        val height = input.height
        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = IntArray(width * height)

        val kernel = floatArrayOf(
            0f, -1f, 0f,
            -1f, 5f, -1f,
            0f, -1f, 0f
        )

        for (y in 1 until height - 1) {
            val rowOffset = y * width
            for (x in 1 until width - 1) {
                var sumR = 0f; var sumG = 0f; var sumB = 0f
                var ki = 0
                for (j in -1..1) {
                    val neighborRow = (y + j) * width
                    for (i in -1..1) {
                        val neighbor = pixels[neighborRow + (x + i)]
                        val k = kernel[ki++]
                        sumR += ((neighbor shr 16) and 0xFF) * k
                        sumG += ((neighbor shr 8) and 0xFF) * k
                        sumB += (neighbor and 0xFF) * k
                    }
                }
                out[rowOffset + x] = (0xFF shl 24) or
                    (sumR.toInt().coerceIn(0, 255) shl 16) or
                    (sumG.toInt().coerceIn(0, 255) shl 8) or
                    sumB.toInt().coerceIn(0, 255)
            }
        }

        // Copy unsharpened edge pixels
        for (x in 0 until width) {
            out[x] = pixels[x]                                   // top
            out[(height - 1) * width + x] = pixels[(height - 1) * width + x]
        }
        for (y in 0 until height) {
            out[y * width] = pixels[y * width]                   // left
            out[y * width + (width - 1)] = pixels[y * width + (width - 1)]
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, width, 0, 0, width, height)
        result
    }

    // ---- internal helpers ----

    /**
     * Downsample so the longest side does not exceed [MAX_DIMENSION].
     * Never recycles [bitmap]: the caller retains ownership (see MlKitOcrEngine
     * contract) and may reuse it for preview or a second pass.
     */
    private fun downsample(bitmap: Bitmap): Bitmap {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        if (maxDim <= MAX_DIMENSION) return bitmap
        val scale = MAX_DIMENSION.toFloat() / maxDim
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    /** Luminosity from an ARGB pixel — skips alpha. */
    private fun luma(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 0.299 + g * 0.587 + b * 0.114).toInt()
    }

    private fun enhanceContrast(gray: Int): Int {
        val contrast = 1.5
        val factor = (259 * (contrast * 128 + 255)) / (255 * (259 - contrast * 128))
        val enhanced = (factor * (gray - 128) + 128).toInt()
        return enhanced.coerceIn(0, 255)
    }

    suspend fun saveBitmapToFile(bitmap: Bitmap, fileName: String): File = withContext(Dispatchers.IO) {
        val safeName = File(fileName).name // strip any path components
        val file = File(context.cacheDir, safeName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        file
    }
}
