package com.visionagent.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Utility functions for camera image processing before sending to the Gemini Vision API.
 * Handles rotation correction, aspect-ratio-preserving downscaling (max dimension <= 1024px),
 * JPEG compression at quality 80, and Base64 NO_WRAP encoding.
 *
 * All operations execute strictly on [Dispatchers.Default] and never write to device storage.
 */
object ImageUtils {

    private const val MAX_DIMENSION_PX = 1024
    private const val JPEG_QUALITY = 80

    /**
     * Processes an [ImageProxy] from CameraX for the Gemini API:
     * 1. Converts to Bitmap via `imageProxy.toBitmap()`.
     * 2. Rotates to match `imageProxy.imageInfo.rotationDegrees`.
     * 3. Downscales so max(width, height) <= 1024 px, preserving aspect ratio.
     * 4. Compresses to JPEG with quality 80.
     * 5. Encodes to Base64 (NO_WRAP).
     * 6. Strictly closes [imageProxy] in a finally block.
     *
     * @param imageProxy The captured camera image proxy.
     * @return Base64-encoded JPEG string.
     */
    suspend fun processImageForGemini(imageProxy: ImageProxy): String = withContext(Dispatchers.Default) {
        try {
            val rawBitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            processBitmapInternal(rawBitmap, rotationDegrees)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Processes a direct [Bitmap] for the Gemini API with optional rotation.
     *
     * @param bitmap Input bitmap.
     * @param rotationDegrees Degrees to rotate (default 0).
     * @return Base64-encoded JPEG string.
     */
    suspend fun processBitmapForGemini(bitmap: Bitmap, rotationDegrees: Int = 0): String =
        withContext(Dispatchers.Default) {
            processBitmapInternal(bitmap, rotationDegrees)
        }

    private fun processBitmapInternal(rawBitmap: Bitmap, rotationDegrees: Int): String {
        // 1. Correct rotation
        val rotatedBitmap = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            if (rotated !== rawBitmap) {
                rawBitmap.recycle()
            }
            rotated
        } else {
            rawBitmap
        }

        // 2. Downscale preserving aspect ratio if max(width, height) > 1024
        val maxDimension = maxOf(rotatedBitmap.width, rotatedBitmap.height)
        val scaledBitmap = if (maxDimension > MAX_DIMENSION_PX) {
            val scale = MAX_DIMENSION_PX.toFloat() / maxDimension.toFloat()
            val targetWidth = (rotatedBitmap.width * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (rotatedBitmap.height * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(rotatedBitmap, targetWidth, targetHeight, true)
            if (scaled !== rotatedBitmap) {
                rotatedBitmap.recycle()
            }
            scaled
        } else {
            rotatedBitmap
        }

        // 3. Compress to JPEG quality 80 in-memory
        val outputStream = ByteArrayOutputStream()
        try {
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            val jpegBytes = outputStream.toByteArray()
            // 4. Base64 NO_WRAP encoding
            return Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        } finally {
            scaledBitmap.recycle()
            outputStream.close()
        }
    }
}

/**
 * Top-level convenience delegate for [ImageUtils.processImageForGemini].
 */
suspend fun processImageForGemini(imageProxy: ImageProxy): String =
    ImageUtils.processImageForGemini(imageProxy)
