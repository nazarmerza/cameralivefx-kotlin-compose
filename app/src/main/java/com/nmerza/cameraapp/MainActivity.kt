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

data class FilterInfo(val displayName: String, val internalName: String)

private val filterOptions = listOf(
    FilterInfo("None", "None"),
    FilterInfo("Blue Arch", "Blue Architecture"),
    FilterInfo("Hard Boost", "HardBoost"),
    FilterInfo("Morning", "LongBeachMorning"),
    FilterInfo("Lush Green", "LushGreen"),
    FilterInfo("Magic Hour", "MagicHour"),
    FilterInfo("Natural", "NaturalBoost"),
    FilterInfo("Orange/Blue", "OrangeAndBlue"),
    FilterInfo("B&W Soft", "SoftBlackAndWhite"),
    FilterInfo("Waves", "Waves"),
    FilterInfo("Blue Hour", "BlueHour"),
    FilterInfo("Cold Chrome", "ColdChrome"),
    FilterInfo("Autumn", "CrispAutumn"),
    FilterInfo("Somber", "DarkAndSomber")
)

enum class CaptureMode { PHOTO, VIDEO }

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

@Composable
fun CameraAppCameraScreen(
    imageProcessor: ImageProcessor,
    processedBitmapFlow: StateFlow<Bitmap?>,
    onCapturePhoto: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    getLastMediaUri: () -> Uri?
) {
    var selectedFilter by remember { mutableStateOf(filterOptions.first()) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var captureMode by remember { mutableStateOf(CaptureMode.PHOTO) }
    var isRecording by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lensFacing) { imageProcessor.lensFacing = lensFacing }

    LaunchedEffect(captureMode) {
        if (captureMode == CaptureMode.PHOTO && isRecording) {
            onStopRecording()
            isRecording = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ProcessedCameraPreview(
            imageProcessor = imageProcessor,
            modifier = Modifier.fillMaxSize(),
            lensFacing = lensFacing,
            lifecycleOwner = lifecycleOwner,
            processedBitmapFlow = processedBitmapFlow
        )

        TopAppBar(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
            captureMode = captureMode,
            onCaptureModeChange = { captureMode = it }
        )

        BottomSheetUI(
            modifier = Modifier.align(Alignment.BottomCenter),
            selectedFilter = selectedFilter,
            onFilterSelected = { filter ->
                selectedFilter = filter
                imageProcessor.setActiveFilter(filter.internalName)
            },
            onSwitchCamera = { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK },
            onCapturePhoto = onCapturePhoto,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            getLastMediaUri = getLastMediaUri,
            onThumbnailClick = {
                val uri = getLastMediaUri()
                if (uri != null) {
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                }
            },
            captureMode = captureMode,
            onCaptureModeChange = { captureMode = it },
            isRecording = isRecording,
            setIsRecording = { isRecording = it }
        )
    }
}

@Composable
fun ProcessedCameraPreview(
    imageProcessor: ImageProcessor,
    modifier: Modifier,
    lensFacing: Int,
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
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9) // More robust than fixed size
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor, imageProcessor)

            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
            } catch (e: Exception) {
                Log.e("CameraApp", "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    if (bitmap != null) {
        Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = "Real-time camera preview", modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier = modifier.background(Color.Black))
    }
}

@Composable
fun TopAppBar(modifier: Modifier = Modifier, captureMode: CaptureMode, onCaptureModeChange: (CaptureMode) -> Unit) {
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        ModeSelector(currentMode = captureMode, onModeChange = onCaptureModeChange)
    }
}

@Composable
fun ModeSelector(currentMode: CaptureMode, onModeChange: (CaptureMode) -> Unit) {
    Row(modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.5f)), verticalAlignment = Alignment.CenterVertically) {
        ModeButton(text = "PHOTO", isSelected = currentMode == CaptureMode.PHOTO, onClick = { onModeChange(CaptureMode.PHOTO) })
        ModeButton(text = "VIDEO", isSelected = currentMode == CaptureMode.VIDEO, onClick = { onModeChange(CaptureMode.VIDEO) })
    }
}

@Composable
fun ModeButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clickable(onClick = onClick).background(if (isSelected) Color(0xFF13A4EC) else Color.Transparent).padding(horizontal = 24.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
fun ThumbnailPreview(onClick: () -> Unit, modifier: Modifier = Modifier, lastPhotoUri: Uri?) {
    Box(
        modifier = modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(0.4f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (lastPhotoUri != null) {
            AsyncImage(model = lastPhotoUri, contentDescription = "Last captured photo", modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Default.PhotoLibrary, "Last photo (none)", tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
fun BottomSheetUI(
    modifier: Modifier, selectedFilter: FilterInfo, onFilterSelected: (FilterInfo) -> Unit,
    onSwitchCamera: () -> Unit, onCapturePhoto: () -> Unit, onStartRecording: () -> Unit, onStopRecording: () -> Unit,
    getLastMediaUri: () -> Uri?, onThumbnailClick: () -> Unit, captureMode: CaptureMode, onCaptureModeChange: (CaptureMode) -> Unit, isRecording: Boolean, setIsRecording: (Boolean) -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth().background(Color(0xFF101C22), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).padding(bottom = 16.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            contentAlignment = Alignment.Center
        ) { Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF4A5568))) }
        Text("Choose Filter", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9CA3AF), modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), textAlign = TextAlign.Center)
        FilterSelectorRow(selectedFilter, onFilterSelected, Modifier.padding(vertical = 12.dp))
        CameraControlsRow(onSwitchCamera, onCapturePhoto, onStartRecording, onStopRecording, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), getLastMediaUri, onThumbnailClick, captureMode, onCaptureModeChange, isRecording, setIsRecording)
    }
}

@Composable
fun FilterSelectorRow(selectedFilter: FilterInfo, onFilterSelected: (FilterInfo) -> Unit, modifier: Modifier) {
    val listState = rememberLazyListState()
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(filterOptions) { filter -> FilterButton(filter.displayName, filter.internalName == selectedFilter.internalName, { onFilterSelected(filter) }) }
    }
}

@Composable
fun FilterButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) Color(0xFF13A4EC) else Color(0xFF1F2937).copy(0.8f), contentColor = if (isSelected) Color.White else Color(0xFF9CA3AF)),
        contentPadding = PaddingValues(horizontal = 20.dp)
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
fun CameraControlsRow(
    onSwitchCamera: () -> Unit, onCapturePhoto: () -> Unit, onStartRecording: () -> Unit, onStopRecording: () -> Unit,
    modifier: Modifier, getLastMediaUri: () -> Uri?, onThumbnailClick: () -> Unit, captureMode: CaptureMode, onCaptureModeChange: (CaptureMode) -> Unit, isRecording: Boolean, setIsRecording: (Boolean) -> Unit
) {
    Row(modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
        ThumbnailPreview(onThumbnailClick, lastPhotoUri = getLastMediaUri())
        CaptureControl(captureMode, onCapturePhoto, onStartRecording, onStopRecording, isRecording, setIsRecording)
        RoundIconButton(icon = Icons.Default.FlipCameraAndroid, onClick = onSwitchCamera, size = 48.dp)
    }
}

@Composable
fun CaptureControl(mode: CaptureMode, onPhoto: () -> Unit, onStartVideo: () -> Unit, onStopVideo: () -> Unit, isRecording: Boolean, setIsRecording: (Boolean) -> Unit) {
    when (mode) {
        CaptureMode.PHOTO -> CaptureButton(onClick = onPhoto)
        CaptureMode.VIDEO -> VideoCaptureButton(isRecording, {
            if (isRecording) onStopVideo() else onStartVideo()
            setIsRecording(!isRecording)
        })
    }
}

@Composable
fun VideoCaptureButton(isRecording: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(80.dp),
        shape = CircleShape,
        color = Color.White
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(6.dp)) {
            val color = if (isRecording) Color.Red else Color(0xFF13A4EC)
            Box(Modifier.fillMaxSize().clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                if (isRecording) {
                    Box(modifier = Modifier.size(24.dp).background(Color.White, shape = RoundedCornerShape(4.dp)))
                } else {
                    Icon(Icons.Default.Videocam, "Start recording", tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun RoundIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, size: Dp, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.1f)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Icon(icon, null, tint = Color(0xFFE5E7EB), modifier = Modifier.size(24.dp)) }
    }
}

@Composable
fun CaptureButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(80.dp),
        shape = CircleShape,
        color = Color.White
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(6.dp)) { Box(Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFF13A4EC))) }
    }
}
