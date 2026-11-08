package io.github.jay41chen.cameo.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

private const val NO_PERMISSION_TEXT = "需要相機權限才能拍攝自拍"
private const val NO_CAMERA_TEXT = "這台裝置無法使用相機"
private const val MAX_PREVIEW_EDGE = 1600

/**
 * 執行相機初始化／綁定；一般例外回 null（呼叫端顯示「無法使用相機」），
 * [CancellationException] 重新丟出以維持協程取消。
 */
internal suspend fun <T : Any> cameraCatching(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

internal fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@Composable
fun CameraScreen(onUsePhoto: (String) -> Unit, viewModel: CameraViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    // 已經要求過但仍未授權 = 已拒絕（含永久拒絕）
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    // 從系統設定返回時重查權限
    OnResume { granted = hasCameraPermission(context) }
    LaunchedEffect(Unit) {
        if (!granted && !asked) launcher.launch(Manifest.permission.CAMERA)
    }

    val s = state
    when {
        s is CameraUiState.Preview -> PhotoPreview(s.path, onRetake = viewModel::retake, onUse = { onUsePhoto(s.path) })
        !granted -> Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text(NO_PERMISSION_TEXT)
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("允許相機") }
            if (asked) {
                OutlinedButton(onClick = { openAppSettings(context) }) { Text("前往設定") }
            }
        }
        else -> Viewfinder(state = s, viewModel = viewModel)
    }
}

@Composable
private fun Viewfinder(state: CameraUiState, viewModel: CameraViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build(),
            )
            .build()
    }
    var noCamera by remember { mutableStateOf(false) }

    LaunchedEffect(lifecycleOwner) {
        val provider = cameraCatching { ProcessCameraProvider.awaitInstance(context) }
        if (provider == null) {
            noCamera = true
            return@LaunchedEffect
        }
        val selector = listOf(CameraSelector.DEFAULT_FRONT_CAMERA, CameraSelector.DEFAULT_BACK_CAMERA)
            .firstOrNull { runCatching { provider.hasCamera(it) }.getOrDefault(false) }
        if (selector == null) {
            noCamera = true
            return@LaunchedEffect
        }
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        try {
            val bound = cameraCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            }
            if (bound == null) {
                noCamera = true
                return@LaunchedEffect
            }
            awaitCancellation()
        } finally {
            provider.unbind(preview, imageCapture)
        }
    }

    if (noCamera) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(NO_CAMERA_TEXT) }
        return
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomCenter).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state is CameraUiState.Error) {
                Text(state.message)
                OutlinedButton(onClick = viewModel::retake) { Text("重拍") }
            } else {
                Button(
                    enabled = state == CameraUiState.Viewfinder,
                    onClick = {
                        val path = viewModel.startCapture() ?: return@Button
                        previewView.display?.let { imageCapture.targetRotation = it.rotation }
                        imageCapture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(File(path)).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) =
                                    viewModel.onCaptured(path)

                                override fun onError(exception: ImageCaptureException) =
                                    viewModel.onCaptureFailed()
                            },
                        )
                    },
                ) { Text("拍照") }
            }
        }
    }
}

@Composable
private fun PhotoPreview(path: String, onRetake: () -> Unit, onUse: () -> Unit) {
    // null = 解碼中或失敗；失敗時仍可重拍
    val bitmap by produceDecoded(path)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            bitmap?.let {
                Image(it.asImageBitmap(), contentDescription = "剛拍的照片", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) { Text("重拍") }
            Button(onClick = onUse, modifier = Modifier.weight(1f)) { Text("使用這張") }
        }
    }
}

@Composable
private fun produceDecoded(path: String) = androidx.compose.runtime.produceState<Bitmap?>(null, path) {
    value = withContext(Dispatchers.IO) { decodeUpright(path, MAX_PREVIEW_EDGE) }
}

/** 取樣解碼並依 EXIF 方向轉正；失敗回 null。 */
private fun decodeUpright(path: String, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    val raw = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val orientation = runCatching {
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        else -> return raw
    }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
}
