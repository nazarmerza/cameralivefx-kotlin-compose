package com.nmerza.cameraapp

import NativeFilter
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    lateinit var imageProcessor: ImageProcessor
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var lastPhotoUri by mutableStateOf<Uri?>(null)

    private val requestPermissionsLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.entries.all { it.value }) {
                Log.d("CameraApp", "All permissions granted")
                setupCameraContent()
            } else {
                Log.e("CameraApp", "Permissions not granted")
                setContent { CameraAppThemeTheme { PermissionDeniedScreen() } }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        imageProcessor = ImageProcessor(NativeFilter(), cameraExecutor)

        val permissionsToRequest = mutableListOf(
            Manifest.permission.CAMERA
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()

        if (permissionsToRequest.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            setupCameraContent()
        } else {
            requestPermissionsLauncher.launch(permissionsToRequest)
        }
    }

    private fun setupCameraContent() {
        lastPhotoUri = findLastPhotoUri()

        setContent {
            CameraAppThemeTheme {
                CameraAppCameraScreen(
                    imageProcessor = imageProcessor,
                    processedBitmapFlow = imageProcessor.processedBitmap,
                    onCapturePhoto = { capturePhoto() },
                    onImageCaptureReady = { capture -> imageCapture = capture },
                    onCameraProviderReady = { provider -> cameraProvider = provider },
                    getLastPhotoUri = { lastPhotoUri }
                )
            }
        }
    }

    private fun capturePhoto() {
        val imageCapture = this.imageCapture ?: return

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraApp")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraApp", "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults){
                    Log.d("CameraApp", "Photo capture succeeded: ${output.savedUri}")
                    output.savedUri?.let { lastPhotoUri = it }
                }
            }
        )
    }

    private fun findLastPhotoUri(): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        return contentResolver.query(
            contentUri, projection, null, null, sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val imageId = cursor.getLong(idColumn)
                ContentUris.withAppendedId(contentUri, imageId)
            } else {
                null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@Composable
fun CameraAppThemeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF13A4EC),
            background = Color(0xFF101C22),
            surface = Color(0xFF101C22)
        ),
        content = content
    )
}

@Composable
fun PermissionDeniedScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Camera permission is required to use this app.",
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp)
        )
    }
}

enum class UiOption {
    OPTION_ONE, OPTION_TWO, OPTION_THREE
}

@Composable
fun CameraAppCameraScreen(
    imageProcessor: ImageProcessor,
    processedBitmapFlow: StateFlow<Bitmap?>,
    onCapturePhoto: () -> Unit,
    onImageCaptureReady: (ImageCapture) -> Unit,
    onCameraProviderReady: (ProcessCameraProvider) -> Unit,
    getLastPhotoUri: () -> Uri?
) {
    var selectedOption by remember { mutableStateOf(UiOption.OPTION_TWO) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var showLastPhoto by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalContext.current as LifecycleOwner
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        ProcessedCameraPreview(
            imageProcessor = imageProcessor,
            modifier = Modifier.fillMaxSize(),
            lensFacing = lensFacing,
            onImageCaptureReady = onImageCaptureReady,
            onCameraProviderReady = onCameraProviderReady,
            lifecycleOwner = lifecycleOwner,
            processedBitmapFlow = processedBitmapFlow
        )

        TopAppBar(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
        )

        BottomSheetUI(
            modifier = Modifier.align(Alignment.BottomCenter),
            selectedOption = selectedOption,
            onOptionSelected = { option ->
                selectedOption = option
                Log.d("CameraApp", "Selected option: ${option.name}")
            },
            onSwitchCamera = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
            },
            onCapturePhoto = onCapturePhoto,
            getLastPhotoUri = getLastPhotoUri,
            onThumbnailClick = { showLastPhoto = true }
        )
    }

    if (showLastPhoto) {
        val lastUri = getLastPhotoUri()
        if (lastUri != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(lastUri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("CameraApp", "Could not launch photo viewer", e)
            }
        } else {
            Log.d("CameraApp", "Thumbnail clicked. No photo saved yet.")
        }
        showLastPhoto = false
    }
}

@Composable
fun ProcessedCameraPreview(
    imageProcessor: ImageProcessor,
    modifier: Modifier = Modifier,
    lensFacing: Int,
    onImageCaptureReady: (ImageCapture) -> Unit,
    onCameraProviderReady: (ProcessCameraProvider) -> Unit,
    lifecycleOwner: LifecycleOwner,
    processedBitmapFlow: StateFlow<Bitmap?>
) {
    val context = LocalContext.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    val bitmap by processedBitmapFlow.collectAsState()

    LaunchedEffect(lensFacing) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            onCameraProviderReady(cameraProvider)

            val imageCapture = ImageCapture.Builder()
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .build()
            onImageCaptureReady(imageCapture)

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, imageProcessor)
                }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageAnalysis, 
                    imageCapture
                )
            } catch (e: Exception) {
                Log.e("CameraApp", "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Real-time camera preview with filter",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(modifier = modifier.background(Color.Black))
    }
}

@Composable
fun TopAppBar(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Camera App",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ThumbnailPreview(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lastPhotoUri: Uri?
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (lastPhotoUri != null) {
            AsyncImage(
                model = lastPhotoUri,
                contentDescription = "Last captured photo thumbnail",
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = "Last photo (none available)",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
fun BottomSheetUI(
    modifier: Modifier = Modifier,
    selectedOption: UiOption,
    onOptionSelected: (UiOption) -> Unit,
    onSwitchCamera: () -> Unit,
    onCapturePhoto: () -> Unit,
    getLastPhotoUri: () -> Uri?,
    onThumbnailClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Color(0xFF101C22),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            )
            .padding(bottom = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF4A5568))
            )
        }
        Text(
            text = "Choose option",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF9CA3AF),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            textAlign = TextAlign.Center
        )
        OptionSelectorRow(
            selectedOption = selectedOption,
            onOptionSelected = onOptionSelected,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        CameraControlsRow(
            onSwitchCamera = onSwitchCamera,
            onCapturePhoto = onCapturePhoto,
            getLastPhotoUri = getLastPhotoUri,
            onThumbnailClick = onThumbnailClick,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

@Composable
fun OptionSelectorRow(
    selectedOption: UiOption,
    onOptionSelected: (UiOption) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF1F2937).copy(alpha = 0.8f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            OptionButton(
                text = "Option 1",
                isSelected = selectedOption == UiOption.OPTION_ONE,
                onClick = { onOptionSelected(UiOption.OPTION_ONE) },
                modifier = Modifier.weight(1f)
            )
            OptionButton(
                text = "Option 2",
                isSelected = selectedOption == UiOption.OPTION_TWO,
                onClick = { onOptionSelected(UiOption.OPTION_TWO) },
                modifier = Modifier.weight(1f)
            )
            OptionButton(
                text = "Option 3",
                isSelected = selectedOption == UiOption.OPTION_THREE,
                onClick = { onOptionSelected(UiOption.OPTION_THREE) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun OptionButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 2.dp),
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) Color(0xFF13A4EC) else Color.Transparent
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (isSelected) Color.White else Color(0xFF9CA3AF),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun CameraControlsRow(
    onSwitchCamera: () -> Unit,
    onCapturePhoto: () -> Unit,
    modifier: Modifier = Modifier,
    getLastPhotoUri: () -> Uri?,
    onThumbnailClick: () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThumbnailPreview(
            onClick = onThumbnailClick,
            lastPhotoUri = getLastPhotoUri()
        )
        CaptureButton(onClick = onCapturePhoto)
        RoundIconButton(
            icon = Icons.Default.FlipCameraAndroid,
            onClick = onSwitchCamera,
            size = 48.dp
        )
    }
}

@Composable
fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    size: Dp,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.1f)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFFE5E7EB),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun CaptureButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(80.dp),
        shape = CircleShape,
        color = Color.White
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color(0xFF13A4EC))
            )
        }
    }
}