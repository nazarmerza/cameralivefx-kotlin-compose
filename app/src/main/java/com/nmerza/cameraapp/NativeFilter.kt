package com.nmerza.cameraapp

import java.nio.ByteBuffer

class NativeFilter {
    companion object {
        init {
            // Must match your CMake target name
            System.loadLibrary("cameraapp-native")
        }
    }

    /**
     * YUV_420_888 -> RGBA (for preview) with LUT, and packs NV12 (UV) into nv12Output for encoder.
     */
    external fun processFrame(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uRowStride: Int,
        vRowStride: Int,
        uPixelStride: Int,
        vPixelStride: Int,
        nv12Output: ByteBuffer
    ): ByteArray

    external fun loadLut(lutData: ByteArray, size: Int): Boolean
    external fun setActiveFilter(filterName: String): Boolean
}
