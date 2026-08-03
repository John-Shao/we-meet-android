package com.we.meet.feature.im.ui.common

import com.we.meet.ui.theme.WeMeetTheme
import com.we.meet.ui.theme.WeMeetTextStyles
import com.we.meet.ui.theme.Dimens
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
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.we.meet.feature.im.data.GroupTile
import kotlin.math.abs

/**
 * 同一个群永远同色 —— 按名字 hash 从调色板取。
 *
 * 调色板由调用方从 token 传入(而不是在这里读 CompositionLocal),好让这个
 * 函数保持纯函数:同样的入参永远同样的结果,好测也好推理。
 */
private fun tintFor(name: String, palette: List<Color>): Color {
    var h = 0
    for (c in name) h = (h * 31 + c.code) ushr 0
    return palette[abs(h) % palette.size]
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
 * Each tile shows the member's uploaded image (if present in [tiles]),
 * otherwise a tinted initial. Tiles are pre-resolved by the caller so a later
 * directory resolve changes this param and Compose recomposes the grid — a
 * snapshot-reading callback would leave stale initials frozen on screen.
 */
@Composable
fun GroupAvatar(
    tiles: List<GroupTile>,
    size: Dp = Dimens.ListLeadingIcon,
    modifier: Modifier = Modifier,
) {
    val members = tiles.take(9)
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
                val tile = members[idx++]
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
                        avatarUrl = tile.avatarUrl?.takeIf { it.isNotBlank() },
                        name = tile.name,
                        // fallback 用 uid;真实 Coil key 由 URL path 推导(见
                        // avatarCacheKey),换头像后 object key 变 -> path 变 -> 刷新。
                        cacheKey = "im-avatar:${tile.uid}",
                    )
                }
            }
        }
    }
}

@Composable
private fun Tile(avatarUrl: String?, name: String, cacheKey: String) {
    val fallbackColor = tintFor(name, WeMeetTheme.extras.im.groupAvatarPalette)
    val effectiveKey = com.we.meet.core.directory.ui.avatarCacheKey(avatarUrl, cacheKey)
    var imageFailed by remember(effectiveKey) { mutableStateOf(false) }

    if (!avatarUrl.isNullOrBlank() && !imageFailed) {
        AsyncImage(
            model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                .data(avatarUrl)
                .memoryCacheKey(effectiveKey)
                .diskCacheKey(effectiveKey)
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
                style = WeMeetTextStyles.LabelTiny,
                textAlign = TextAlign.Center,
            )
        }
    }
}
