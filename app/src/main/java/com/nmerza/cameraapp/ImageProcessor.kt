package com.nmerza.cameraapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService

class ImageProcessor(
    private val context: Context, // For the VideoRecorder
    private val nativeFilter: NativeFilter,
    private val cameraExecutor: ExecutorService
) : ImageAnalysis.Analyzer {

    private val _processedBitmap = MutableStateFlow<Bitmap?>(null)
    val processedBitmap: StateFlow<Bitmap?> = _processedBitmap

    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var videoRecorder: VideoRecorder? = null
    private var nv21Buffer: ByteBuffer? = null
    private var isRecording = false
    private var recordingRotation = 0

    fun startRecording(rotationDegrees: Int) {
        isRecording = true
        recordingRotation = rotationDegrees
        // The VideoRecorder will be created lazily in analyze() when the first frame arrives.
    }

    fun stopRecording(onVideoSaved: (Uri?) -> Unit) {
        isRecording = false
        videoRecorder?.stopRecording(onVideoSaved)
        videoRecorder = null
    }

    fun setActiveFilter(filterName: String) {
        cameraExecutor.execute {
            nativeFilter.setActiveFilter(filterName)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val image = imageProxy.image ?: run { imageProxy.close(); return }

        // Lazy initialization of the VideoRecorder
        if (isRecording && videoRecorder == null) {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            videoRecorder = VideoRecorder(context, image.width, image.height, rotationDegrees).also {
                it.startRecording()
            }
        }

        if (image.format != ImageFormat.YUV_420_888) {
            imageProxy.close()
            return
        }

        val planes = image.planes
        val width = image.width
        val height = image.height

        val requiredCapacity = width * height * 3 / 2
        if (nv21Buffer == null || nv21Buffer!!.capacity() != requiredCapacity || !nv21Buffer!!.isDirect) {
            nv21Buffer = ByteBuffer.allocateDirect(requiredCapacity)
        }
        nv21Buffer!!.clear()

        val processedRgba: ByteArray? = nativeFilter.processFrame(
            planes[0].buffer, planes[1].buffer, planes[2].buffer,
            width, height,
            planes[0].rowStride, planes[1].rowStride, planes[2].rowStride,
            planes[1].pixelStride, planes[2].pixelStride, nv21Buffer!!
        )

        if (processedRgba == null) {
            imageProxy.close()
            return // Exit if native code fails
        }

        if (isRecording) {
            videoRecorder?.onFrameAvailable(nv21Buffer!!, imageProxy.imageInfo.timestamp)
        }

        val rawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        rawBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(processedRgba))

        val rotationDegreesFloat = imageProxy.imageInfo.rotationDegrees.toFloat()
        val matrix = Matrix().apply {
            postRotate(rotationDegreesFloat)
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                postScale(-1f, 1f, width / 2f, height / 2f)
            }
        }

        val rotatedBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, width, height, matrix, true)
        _processedBitmap.value = rotatedBitmap

        imageProxy.close()
    }
}