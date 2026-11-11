package com.visionagent.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX controller designed for low-latency, single-shot image capture for blind users.
 *
 * Key features:
 * - Back camera default ([CameraSelector.DEFAULT_BACK_CAMERA]).
 * - Low-latency capture mode ([ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY]).
 * - Optional Preview binding (supports sight-impaired mode where preview may be hidden).
 * - Suspend single-shot photo capture delivering an [ImageProxy].
 * - Clean lifecycle unbinding and executor shutdown.
 */
class CameraController(private val context: Context) {

    companion object {
        private const val TAG = "VisionCameraController"
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private var camera: Camera? = null

    private var activeLifecycleOwner: LifecycleOwner? = null
    private var activePreviewView: PreviewView? = null

    /**
     * Binds CameraX use cases to the provided [lifecycleOwner].
     * If [previewView] is non-null, binds both [Preview] and [ImageCapture].
     * If [previewView] is null (e.g. preview disabled in settings), binds only [ImageCapture].
     */
    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView? = null
    ) = withContext(Dispatchers.Main) {
        activeLifecycleOwner = lifecycleOwner
        activePreviewView = previewView

        if (ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot bind camera yet: CAMERA permission has not been granted")
            return@withContext
        }

        try {
            val provider = getCameraProvider()
            cameraProvider = provider
            provider.unbindAll()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture

            if (previewView != null) {
                val newPreview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                preview = newPreview
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    newPreview,
                    capture
                )
                Log.d(TAG, "Camera bound with Preview and ImageCapture")
            } else {
                preview = null
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    capture
                )
                Log.d(TAG, "Camera bound with ImageCapture only (no Preview)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera use cases: ${e.message}", e)
        }
    }

    /**
     * Rebinds with or without a [PreviewView] using the current active [LifecycleOwner].
     */
    suspend fun updatePreviewTarget(previewView: PreviewView?) {
        val lifecycleOwner = activeLifecycleOwner ?: return
        bindCamera(lifecycleOwner, previewView)
    }

    /**
     * Asynchronously retrieves the [ProcessCameraProvider] instance.
     */
    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val provider = future.get()
                    if (continuation.isActive) {
                        continuation.resume(provider)
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
            }, mainExecutor)
        }

    /**
     * Captures a single image frame asynchronously.
     *
     * @return An [ImageProxy] containing the uncompressed/unrotated camera frame.
     * Caller is responsible for closing the [ImageProxy] (handled automatically by [com.visionagent.util.ImageUtils]).
     */
    suspend fun captureImage(): ImageProxy = suspendCancellableCoroutine { continuation ->
        val capture = imageCapture
        if (capture == null) {
            continuation.resumeWithException(
                IllegalStateException("Camera is not bound or ImageCapture is null. Call bindCamera() first.")
            )
            return@suspendCancellableCoroutine
        }

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    if (continuation.isActive) {
                        continuation.resume(image)
                    } else {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture error: ${exception.imageCaptureError} - ${exception.message}", exception)
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                    }
                }
            }
        )
    }

    /**
     * Unbinds all CameraX use cases from the lifecycle.
     */
    fun unbind() {
        try {
            cameraProvider?.unbindAll()
            imageCapture = null
            preview = null
            camera = null
            Log.d(TAG, "Camera unbound successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding camera: ${e.message}")
        }
    }

    /**
     * Completely shuts down camera resources and background executor thread.
     */
    fun shutdown() {
        unbind()
        try {
            cameraExecutor.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down camera executor: ${e.message}")
        }
    }
}
