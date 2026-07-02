package com.we.meet.core.directory.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.abs

/**
 * Circular avatar with an initials fallback.
 *
 * [cacheKey] must be a STABLE identity (e.g. "avatar:<userId>"), never the URL:
 * avatar URLs are short-lived presigned links whose query signature changes on
 * every fetch — keying by URL would cache-miss every render and bloat the disk
 * cache, while a stable key keeps the bytes valid even after the URL expires.
 */
@Composable
fun MemberAvatar(
    name: String,
    url: String?,
    cacheKey: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    var failed by remember(cacheKey) { mutableStateOf(false) }
    val showImage = !url.isNullOrBlank() && !failed

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (showImage) Color.Transparent else fallbackColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        if (showImage) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .build(),
                contentDescription = name,
                onError = { failed = true },
                modifier = Modifier.size(size),
            )
        } else {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun fallbackColor(name: String): Color {
    // Deterministic tint per name so the same person keeps the same color.
    val palette = listOf(
        Color(0xFF5B8DEF), Color(0xFF7C6FE0), Color(0xFF43A88A),
        Color(0xFFE0876F), Color(0xFFD46A9C), Color(0xFF6FA8DC),
    )
    return palette[abs(name.hashCode()) % palette.size]
}
