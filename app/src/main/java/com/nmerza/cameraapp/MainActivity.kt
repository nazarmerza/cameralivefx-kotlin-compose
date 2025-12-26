package com.nmerza.cameraapp

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imageProcessor: ImageProcessor
    private var lastMediaUri by mutableStateOf<Uri?>(null)

    private lateinit var requestPermissionsLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.entries.all { it.value }) {
                setupCameraContent()
            } else {
                setContent {
                    CameraAppThemeTheme {
                        PermissionDeniedScreen {
                            requestPermissionsLauncher.launch(getPermissionsToRequest())
                        }
                    }
                }
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        imageProcessor = ImageProcessor(applicationContext, NativeFilter(), cameraExecutor)

        if (getPermissionsToRequest().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            setupCameraContent()
        } else {
            requestPermissionsLauncher.launch(getPermissionsToRequest())
        }
    }

    private fun getPermissionsToRequest(): Array<String> {
        return mutableListOf(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    private fun setupCameraContent() {
        lastMediaUri = findLastPhotoUri()

        setContent {
            CameraAppThemeTheme {
                CameraAppCameraScreen(
                    imageProcessor = imageProcessor,
                    processedBitmapFlow = imageProcessor.processedBitmap,
                    onCapturePhoto = { capturePhoto() },
                    onStartRecording = { startVideoRecording() },
                    onStopRecording = { stopVideoRecording() },
                    getLastMediaUri = { lastMediaUri }
                )
            }
        }
    }

    private fun capturePhoto() {
        val bitmapToSave = imageProcessor.processedBitmap.value ?: return

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraApp")
            }
        }

        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)?.let {
            try {
                contentResolver.openOutputStream(it)?.use { out -> bitmapToSave.compress(Bitmap.CompressFormat.JPEG, 95, out) }
                lastMediaUri = it
            } catch (e: Exception) {
                contentResolver.delete(it, null, null)
            }
        }
    }

    private fun startVideoRecording() {
        imageProcessor.startRecording(getDeviceRotation())
    }

    private fun stopVideoRecording() {
        imageProcessor.stopRecording { uri ->
            if (uri != null) {
                lastMediaUri = uri
            }
        }
    }

    private fun getDeviceRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: android.view.Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
    }

    private fun findLastPhotoUri(): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_TAKEN)
        val sortOrder = "${MediaStore.MediaColumns.DATE_TAKEN} DESC"
        val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val videoCursor = contentResolver.query(videoUri, projection, null, null, sortOrder)
        val imageCursor = contentResolver.query(imageUri, projection, null, null, sortOrder)

        val latestVideo = videoCursor?.use { if (it.moveToFirst()) it.getLong(it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)) to ContentUris.withAppendedId(videoUri, it.getLong(it.getColumnIndexOrThrow(MediaStore.Video.Media._ID))) else null }
        val latestImage = imageCursor?.use { if (it.moveToFirst()) it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)) to ContentUris.withAppendedId(imageUri, it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))) else null }

        return when {
            latestVideo != null && latestImage != null -> if (latestVideo.first > latestImage.first) latestVideo.second else latestImage.second
            latestVideo != null -> latestVideo.second
            latestImage != null -> latestImage.second
            else -> null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        imageProcessor.stopRecording { }
    }
}

@Composable
fun CameraAppThemeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF13A4EC), background = Color(0xFF101C22), surface = Color(0xFF101C22)), content = content)
}

@Composable
fun PermissionDeniedScreen(onRequestPermission: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "This app requires both Camera and Microphone permissions to function. Please grant all permissions to continue.",
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequestPermission) {
                Text("Grant Permissions")
            }
        }
    }
}
