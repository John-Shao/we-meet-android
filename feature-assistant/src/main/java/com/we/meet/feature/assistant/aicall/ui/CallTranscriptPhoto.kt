package com.we.meet.feature.assistant.aicall.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.we.meet.feature.assistant.R
import com.we.meet.feature.assistant.history.AssistantHistoryPhoto
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decode outside the main thread; saved history loads one photo instead of all photo bytes. */
@Composable
private fun photoBitmap(photo: AssistantHistoryPhoto, thumbnail: Boolean): State<ImageBitmap?> = produceState<ImageBitmap?>(null, photo, thumbnail) {
    value = withContext(Dispatchers.IO) {
        runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            fun decode(): android.graphics.Bitmap? = when (photo) {
                is AssistantHistoryPhoto.Memory -> BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size, options)
                is AssistantHistoryPhoto.Stored -> BitmapFactory.decodeFile(photo.file.absolutePath, options)
            }
            decode()
            if (options.outWidth !in 1..1920 || options.outHeight !in 1..1920) return@runCatching null
            options.inSampleSize = if (thumbnail) {
                var sample = 1
                while (maxOf(options.outWidth, options.outHeight) / sample > 640) sample *= 2
                sample
            } else 1
            options.inJustDecodeBounds = false
            decode()?.asImageBitmap()
        }.getOrNull()
    }
}

@Composable
fun CallTranscriptPhoto(photo: AssistantHistoryPhoto, id: String, modifier: Modifier = Modifier) {
    val bitmap by photoBitmap(photo, thumbnail = true)
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val label = stringResource(R.string.assistant_photo_view)
    Surface(modifier.clip(RoundedCornerShape(Dimens.CornerL)).clickable(onClickLabel = label) { expanded = true }
        .testTag("call-photo-$id"), color = MaterialTheme.colorScheme.surfaceVariant) {
        // Keep a stable size while decoding so arriving images do not shift the scroll position.
        Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(checkNotNull(bitmap), contentDescription = label,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text(stringResource(R.string.assistant_photo_attachment), Modifier.padding(Dimens.SpaceL))
        }
    }
    if (expanded) CallPhotoViewer(photo) { expanded = false }
}

@Composable
private fun CallPhotoViewer(photo: AssistantHistoryPhoto, onClose: () -> Unit) {
    val bitmap by photoBitmap(photo, thumbnail = false)
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    fun bounded(candidate: Offset): Offset {
        val image = bitmap ?: return Offset.Zero
        if (viewport.width == 0 || viewport.height == 0) return Offset.Zero
        val fit = minOf(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
        val x = ((image.width * fit * zoom - viewport.width) / 2).coerceAtLeast(0f)
        val y = ((image.height * fit * zoom - viewport.height) / 2).coerceAtLeast(0f)
        return Offset(candidate.x.coerceIn(-x, x), candidate.y.coerceIn(-y, y))
    }
    val gestures = rememberTransformableState { scale, pan, _ ->
        zoom = (zoom * scale).coerceIn(1f, 5f)
        offset = bounded(offset + pan)
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("call-photo-viewer"), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(Dimens.SpaceNone))
                    .onSizeChanged { viewport = it; offset = bounded(offset) }
                    .transformable(gestures)
                    .pointerInput(photo) { detectTapGestures(onDoubleTap = {
                        zoom = if (zoom > 1f) 1f else 2.5f
                        offset = Offset.Zero
                    }) }
                    .semantics { stateDescription = "${(zoom * 100).toInt()}%" }
                    .testTag("call-photo-zoom"), contentAlignment = Alignment.Center) {
                    if (bitmap != null) Image(checkNotNull(bitmap), stringResource(R.string.assistant_photo_attachment),
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y
                        }, contentScale = ContentScale.Fit)
                    else Text(stringResource(R.string.assistant_photo_attachment))
                }
                FilledIconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(Dimens.SpaceL)) {
                    Icon(Icons.Default.Close, stringResource(R.string.assistant_photo_close))
                }
            }
        }
    }
}
