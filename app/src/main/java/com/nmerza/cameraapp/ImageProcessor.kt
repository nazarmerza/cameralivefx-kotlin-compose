package com.nmerza.cameraapp

import NativeFilter
import android.graphics.Bitmap
import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ExecutorService


// likely a Flow or MutableState.
// ----------------------------------------------------------------------
// NEW: Image Processor Class
// Handles ImageAnalysis callback and manages the output Bitmap flow.
// ----------------------------------------------------------------------
class ImageProcessor(
    private val nativeFilter: NativeFilter,
    private val cameraExecutor: ExecutorService
) : ImageAnalysis.Analyzer {

    // MutableStateFlow to pass the processed Bitmap back to the Compose UI
    private val _processedBitmap: MutableStateFlow<Bitmap?> = MutableStateFlow(null)
    val processedBitmap: StateFlow<Bitmap?> = _processedBitmap

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        // The frame needs to be processed off the main thread,
        // but since we are using a single-threaded executor for ImageAnalysis,
        // we can proceed directly.

        val image = imageProxy.image
        if (image == null || image.format != ImageFormat.YUV_420_888) {
            imageProxy.close()
            return
        }

        val planes = image.planes

        // Use the native C++ function to process the frame.
        val processedRgba = nativeFilter.processFrame(
            planes[0].buffer, // Y
            planes[1].buffer, // U
            planes[2].buffer, // V
            image.width,
            image.height,
            planes[0].rowStride, // Y Stride
            planes[1].rowStride  // UV Stride
        )

        // Convert the returned RGBA byte array into a Bitmap
        val resultBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        resultBitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(processedRgba))

        // Update the flow for the Compose UI to redraw.
        _processedBitmap.value = resultBitmap

        imageProxy.close()
    }
}