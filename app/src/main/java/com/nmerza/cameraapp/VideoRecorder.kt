package com.nmerza.cameraapp

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VideoRecorder(private val context: Context, private val width: Int, private val height: Int, private val orientationHint: Int) {

    private val executor = Executors.newSingleThreadExecutor()
    private var mediaMuxer: MediaMuxer? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1

    private val isRecording = AtomicBoolean(false)
    private val isMuxerStarted = AtomicBoolean(false)
    private var audioThread: Thread? = null
    private val muxerLock = Any()

    // FIX: Add timestamp baseline to normalize timestamps to start from 0
    private var firstVideoTimestampUs: Long = -1L
    private var audioPresentationTimeUs: Long = 0L

    var currentVideoUri: Uri? = null

    fun isRecording(): Boolean = isRecording.get()

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (!isRecording.compareAndSet(false, true)) {
            Log.w(TAG, "Recording is already in progress")
            return
        }

        executor.execute {
            try {
                isMuxerStarted.set(false)
                videoTrackIndex = -1
                audioTrackIndex = -1
                firstVideoTimestampUs = -1L  // Reset timestamp baseline
                audioPresentationTimeUs = 0L

                setupMediaMuxer()
                setupVideoEncoder()
                setupAudioEncoder()
                setupAudioRecord()

                videoEncoder?.start()
                audioEncoder?.start()
                audioRecord?.startRecording()

                audioThread = Thread { processAudio() }
                audioThread?.start()

                Log.d(TAG, "Recording components started.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                stopRecording { }
            }
        }
    }

    fun stopRecording(onVideoSaved: (Uri?) -> Unit) {
        if (!isRecording.compareAndSet(true, false)) {
            onVideoSaved(null)
            return
        }

        executor.execute {
            Log.d(TAG, "Stopping recording...")
            try {
                audioThread?.interrupt()
                audioThread?.join(500)

                // Signal end-of-stream for both encoders
                videoEncoder?.let { encoder ->
                    val inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }
                audioEncoder?.let { encoder ->
                    val inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }

                // Drain remaining data
                drainEncoder(videoEncoder, true, true)
                drainEncoder(audioEncoder, false, true)

                // Stop and release resources
                videoEncoder?.stop()
                videoEncoder?.release()
                videoEncoder = null

                audioEncoder?.stop()
                audioEncoder?.release()
                audioEncoder = null

                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null

                mediaMuxer?.stop()
                mediaMuxer?.release()
                mediaMuxer = null

                isMuxerStarted.set(false)
                Log.d(TAG, "Recording stopped and all resources released.")
            } catch (e: Exception) {
                Log.e(TAG, "Error during stopRecording", e)
            } finally {
                onVideoSaved(currentVideoUri)
            }
        }
    }
    private var lastVideoPtsUs = -1L

    fun onFrameAvailable(nvXXBuffer: ByteBuffer, timestampNs: Long) {
        if (!isRecording.get()) return
        executor.execute {
            videoEncoder?.let { encoder ->
                val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = encoder.getInputBuffer(inIndex)!!
                    buffer.clear()

                    val tsUs = timestampNs / 1000L
                    if (firstVideoTimestampUs == -1L) firstVideoTimestampUs = tsUs
                    var normalized = tsUs - firstVideoTimestampUs
                    if (normalized <= lastVideoPtsUs) normalized = lastVideoPtsUs + 1 // enforce strictly increasing
                    lastVideoPtsUs = normalized

                    val size = nvXXBuffer.remaining()
                    // (NV21→NV12 in-place if using Option A)
                    buffer.put(nvXXBuffer)
                    nvXXBuffer.rewind()

                    if (size > 0) {
                        encoder.queueInputBuffer(inIndex, 0, size, normalized, 0)
                    }
                }
                drainEncoder(encoder, true, false)
            }
        }
    }


    private fun drainEncoder(encoder: MediaCodec?, isVideo: Boolean, endOfStream: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = encoder?.dequeueOutputBuffer(bufferInfo, TIMEOUT_US) ?: MediaCodec.INFO_TRY_AGAIN_LATER

            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(muxerLock) {
                    if (isMuxerStarted.get()) throw RuntimeException("Format changed after muxer started")
                    val newFormat = encoder!!.outputFormat
                    val mime = newFormat.getString(MediaFormat.KEY_MIME)!!

                    val trackIndex = if (mime.startsWith("video/")) {
                        videoTrackIndex = mediaMuxer!!.addTrack(newFormat)
                        videoTrackIndex
                    } else {
                        audioTrackIndex = mediaMuxer!!.addTrack(newFormat)
                        audioTrackIndex
                    }
                    Log.d(TAG, "Added track $trackIndex for $mime")

                    if (videoTrackIndex != -1 && audioTrackIndex != -1) {
                        mediaMuxer?.start()
                        isMuxerStarted.set(true)
                        Log.d(TAG, "MediaMuxer started")
                    }
                }
            } else if (outIndex >= 0) {
                val buffer = encoder!!.getOutputBuffer(outIndex) ?: break
                if (isMuxerStarted.get() && bufferInfo.size != 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    val trackIndex = if (isVideo) videoTrackIndex else audioTrackIndex
                    synchronized(muxerLock) {
                        mediaMuxer?.writeSampleData(trackIndex, buffer, bufferInfo)
                    }
                }
                encoder.releaseOutputBuffer(outIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    private fun setupMediaMuxer() {
        val name = "VIDEO_${System.currentTimeMillis()}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/CameraApp")
            }
        }

        currentVideoUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)!!
        val pfd = context.contentResolver.openFileDescriptor(currentVideoUri!!, "w")!!
        mediaMuxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        mediaMuxer?.setOrientationHint(orientationHint)
    }

    private fun setupVideoEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar )
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupAudioEncoder() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
        }
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupAudioRecord() {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2)
    }

    private fun processAudio() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

        while (isRecording.get()) {
            audioEncoder?.let { encoder ->
                val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = encoder.getInputBuffer(inIndex)!!
                    buffer.clear()
                    val bytesRead = audioRecord?.read(buffer, buffer.capacity()) ?: 0
                    if (bytesRead > 0) {
                        val pts = audioPresentationTimeUs
                        encoder.queueInputBuffer(inIndex, 0, bytesRead, pts, 0)
                        // Calculate next PTS based on sample count
                        audioPresentationTimeUs += (bytesRead.toLong() * 1_000_000L) / (SAMPLE_RATE * 2)
                    } else {
                        encoder.queueInputBuffer(inIndex, 0, 0, 0, 0)
                    }
                }
                drainEncoder(encoder, false, false)
            }
        }
    }

    companion object {
        private const val TAG = "VideoRecorder"
        private const val TIMEOUT_US = 10000L
        private const val SAMPLE_RATE = 44100
    }
}