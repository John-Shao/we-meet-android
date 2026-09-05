package com.we.meet.ui.profile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.we.meet.R
import com.we.meet.design.R as DesignR
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.OnMediaOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Cap the longest decoded edge so a huge gallery photo doesn't blow memory;
 *  the 600–1200px outputs stay sharp well below this. */
private const val MAX_DECODE_PX = 1600

/** Max zoom-in relative to the cover-the-window minimum scale. */
private const val MAX_ZOOM_FACTOR = 8f

/** JPEG quality for the rendered crop. */
private const val JPEG_QUALITY = 90

/**
 * Mutable view-transform of the source bitmap relative to the crop window. All
 * values are in window-local pixels:
 *  - [scale]   px-per-bitmap-pixel currently applied on screen
 *  - [tx]/[ty] window-local position of the bitmap's top-left corner
 *  - [winW]/[winH] crop-window size on screen (matches the output aspect ratio)
 *
 * The same numbers drive both the on-screen preview and the final render, so
 * the framed region is reproduced exactly at the output resolution.
 */
private class CropState {
    var scale by mutableFloatStateOf(1f)
    var tx by mutableFloatStateOf(0f)
    var ty by mutableFloatStateOf(0f)
    var winW by mutableFloatStateOf(0f)
    var winH by mutableFloatStateOf(0f)
    var ready by mutableStateOf(false)
}

/**
 * Full-screen image cropper. The user picks any image; a fixed window of the
 * output's aspect ratio sits centered while the image pans/zooms beneath it
 * (always covering the window). On confirm we render exactly the windowed
 * region to an [outputWidth]×[outputHeight] JPEG and hand the bytes back.
 *
 * WeChat-style: the app always emits a fixed size regardless of source
 * dimensions (avatar 600×600, cover 1200×900).
 */
@Composable
fun ImageCropDialog(
    uri: Uri,
    outputWidth: Int,
    outputHeight: Int,
    onConfirm: (ByteArray) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(uri) { mutableStateOf(false) }
    var loadRetryNonce by remember(uri) { mutableStateOf(0) }
    var rendering by remember(uri) { mutableStateOf(false) }
    val state = remember(uri) { CropState() }

    LaunchedEffect(uri, loadRetryNonce) {
        bitmap = null
        loadFailed = false
        // Coil applies EXIF orientation + downsampling and yields a software
        // bitmap we can draw onto a Canvas.
        try {
            val result = withContext(Dispatchers.IO) {
                val request = ImageRequest.Builder(context)
                    .data(uri)
                    .allowHardware(false)
                    .size(MAX_DECODE_PX)
                    .build()
                context.imageLoader.execute(request) as? SuccessResult
            }
            val bmp = (result?.drawable as? BitmapDrawable)?.bitmap
            if (bmp != null) bitmap = bmp else loadFailed = true
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            loadFailed = true
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                val bmp = bitmap
                when {
                    bmp != null -> CropCanvas(
                        bitmap = bmp,
                        aspect = outputWidth.toFloat() / outputHeight.toFloat(),
                        state = state,
                    )
                    loadFailed -> Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                    ) {
                        Text(
                            text = stringResource(R.string.profile_image_error_upload),
                            color = OnMediaOverlay,
                        )
                        TextButton(
                            onClick = { loadRetryNonce += 1 },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = OnMediaOverlay,
                            ),
                        ) {
                            Text(stringResource(DesignR.string.common_retry))
                        }
                    }
                    else -> CircularProgressIndicator(
                        color = OnMediaOverlay,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                if (rendering) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = OnMediaOverlay) }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onCancel,
                    enabled = !rendering,
                    colors = ButtonDefaults.textButtonColors(contentColor = OnMediaOverlay),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    onClick = {
                        val bmp = bitmap ?: return@TextButton
                        if (!state.ready || rendering) return@TextButton
                        rendering = true
                        scope.launch {
                            val bytes = withContext(Dispatchers.Default) {
                                renderCrop(bmp, state, outputWidth, outputHeight)
                            }
                            onConfirm(bytes)
                        }
                    },
                    enabled = bitmap != null && state.ready && !rendering,
                    colors = ButtonDefaults.textButtonColors(contentColor = OnMediaOverlay),
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        }
    }
}

@Composable
private fun CropCanvas(bitmap: Bitmap, aspect: Float, state: CropState) {
    val overlayScrim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val areaW = constraints.maxWidth.toFloat()
        val areaH = constraints.maxHeight.toFloat()
        // Largest centered rect of the output aspect ratio that fits the area.
        var winW = areaW
        var winH = winW / aspect
        if (winH > areaH) {
            winH = areaH
            winW = winH * aspect
        }
        val winLeft = (areaW - winW) / 2f
        val winTop = (areaH - winH) / 2f

        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        // Cover the window: both axes must at least span it.
        val minScale = max(winW / bw, winH / bh)
        val maxScale = minScale * MAX_ZOOM_FACTOR

        // Initialise once per (bitmap, layout): fit-cover, centered.
        LaunchedEffect(bitmap, winW, winH) {
            state.winW = winW
            state.winH = winH
            state.scale = minScale
            state.tx = (winW - bw * minScale) / 2f
            state.ty = (winH - bh * minScale) / 2f
            state.ready = true
        }

        fun clamp() {
            val imgW = bw * state.scale
            val imgH = bh * state.scale
            state.tx = state.tx.coerceIn(winW - imgW, 0f)
            state.ty = state.ty.coerceIn(winH - imgH, 0f)
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap, winW, winH) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        // Centroid in window-local coords.
                        val cx = centroid.x - winLeft
                        val cy = centroid.y - winTop
                        val old = state.scale
                        val new = (old * zoom).coerceIn(minScale, maxScale)
                        // Keep the bitmap point under the centroid fixed while
                        // zooming, then apply the pan.
                        state.tx = cx - ((cx - state.tx) / old) * new + pan.x
                        state.ty = cy - ((cy - state.ty) / old) * new + pan.y
                        state.scale = new
                        clamp()
                    }
                },
        ) {
            if (!state.ready) return@Canvas
            // Bitmap, transformed by the live preview matrix.
            drawIntoCanvas { canvas ->
                val m = Matrix().apply {
                    setScale(state.scale, state.scale)
                    postTranslate(winLeft + state.tx, winTop + state.ty)
                }
                canvas.nativeCanvas.drawBitmap(bitmap, m, PREVIEW_PAINT)
            }
            // Dim everything outside the crop window.
            drawRect(overlayScrim, topLeft = Offset.Zero, size = Size(size.width, winTop))
            drawRect(
                overlayScrim,
                topLeft = Offset(0f, winTop + winH),
                size = Size(size.width, size.height - winTop - winH),
            )
            drawRect(overlayScrim, topLeft = Offset(0f, winTop), size = Size(winLeft, winH))
            drawRect(
                overlayScrim,
                topLeft = Offset(winLeft + winW, winTop),
                size = Size(size.width - winLeft - winW, winH),
            )
            // Window outline.
            drawRect(
                color = OnMediaOverlay,
                topLeft = Offset(winLeft, winTop),
                size = Size(winW, winH),
                style = Stroke(width = 2f),
            )
        }
    }
}

private val PREVIEW_PAINT = Paint().apply {
    isFilterBitmap = true
    isAntiAlias = true
}

/**
 * Render the framed window region to an [outW]×[outH] JPEG. Reuses the live
 * transform: window-local coords scale by outW/winW into output px (winW and
 * winH share the output aspect ratio, so the factor is uniform).
 */
private fun renderCrop(bitmap: Bitmap, state: CropState, outW: Int, outH: Int): ByteArray {
    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    // Flatten any transparency (PNG source) onto white before JPEG encoding.
    canvas.drawColor(OnMediaOverlay.toArgb())
    val outScale = outW / state.winW
    val m = Matrix().apply {
        setScale(state.scale * outScale, state.scale * outScale)
        postTranslate(state.tx * outScale, state.ty * outScale)
    }
    canvas.drawBitmap(bitmap, m, PREVIEW_PAINT)
    return ByteArrayOutputStream().use { baos ->
        out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        baos.toByteArray()
    }
}
