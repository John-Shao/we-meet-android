package com.we.meet.feature.im.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.feature.im.data.ImUserInfo
import kotlin.math.abs

/** Deterministic palette — same member always gets the same tint. */
private val AVATAR_COLORS = listOf(
    Color(0xFF2563EB), Color(0xFF7C3AED), Color(0xFFDB2777),
    Color(0xFFEA580C), Color(0xFF16A34A), Color(0xFF0891B2),
)

private fun tintFor(name: String): Color {
    var h = 0
    for (c in name) h = (h * 31 + c.code) ushr 0
    return AVATAR_COLORS[abs(h) % AVATAR_COLORS.size]
}

private fun initialOf(name: String): String =
    name.trim().firstOrNull()?.uppercase() ?: "?"

/**
 * WeChat/Feishu-style 9-grid group avatar — tiles up to 9 member avatars.
 *
 * Layout rules:
 * - 1 member → fills the square
 * - 2–4 → 2×2 grid
 * - 5–9 → 3×3 grid, top row centred
 *
 * Each tile shows the member's uploaded image (if available via [resolveUser]),
 * otherwise a tinted initial.
 */
@Composable
fun GroupAvatar(
    memberUids: List<String>,
    resolveUser: (uid: String) -> ImUserInfo?,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    val members = memberUids.take(9)
    val n = members.size

    if (n == 0) {
        // Fallback: groups icon.
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(size * 0.2f),
            modifier = modifier.size(size),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Groups,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(size * 0.55f),
                )
            }
        }
        return
    }

    // Grid dimensions.
    val cols = if (n == 1) 1 else if (n <= 4) 2 else 3
    val sidePct = 1f / cols
    val rows = (n + cols - 1) / cols
    val firstRow = if (n % cols == 0) cols else n % cols

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.2f))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val density = LocalDensity.current
        val sizePx = with(density) { size.toPx() }
        val tileSidePx = sizePx * sidePct
        val tileSideDp = with(density) { tileSidePx.toDp() }
        var idx = 0
        for (r in 0 until rows) {
            val k = if (r == 0) firstRow else cols
            val leftStartPx = (sizePx - k * tileSidePx) / 2f
            for (c in 0 until k) {
                val uid = members[idx++]
                val info = resolveUser(uid)
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (leftStartPx + c * tileSidePx).toInt(),
                                (r * tileSidePx).toInt(),
                            )
                        }
                        .size(tileSideDp),
                    contentAlignment = Alignment.Center,
                ) {
                    Tile(
                        avatarUrl = info?.avatarUrl?.takeIf { it.isNotBlank() },
                        name = info?.displayName ?: uid.take(2),
                    )
                }
            }
        }
    }
}

@Composable
private fun Tile(avatarUrl: String?, name: String) {
    val fallbackColor = tintFor(name)
    var imageFailed by remember(name) { mutableStateOf(false) }

    if (!avatarUrl.isNullOrBlank() && !imageFailed) {
        AsyncImage(
            model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                .data(avatarUrl)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onError = { imageFailed = true },
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(fallbackColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initialOf(name),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
