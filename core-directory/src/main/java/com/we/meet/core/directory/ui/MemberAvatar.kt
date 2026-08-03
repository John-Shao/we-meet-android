package com.we.meet.core.directory.ui

import com.we.meet.ui.theme.AvatarFallbackPalette
import com.we.meet.ui.theme.Dimens
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * Coil cache key for an avatar image.
 *
 * Presigned GET URLs rotate their query signature on every fetch, so keying by
 * the full URL would cache-miss every render. But the backend mints a NEW object
 * key on every avatar upload (utils.build_profile_object_key → `{uid}/{uuid}.ext`),
 * so the URL *path* is stable per-image yet changes when the avatar changes.
 * Keying by the path (query stripped) therefore:
 *   - hits the cache across presign rotation (same image → same path), and
 *   - misses → re-fetches when the avatar actually changes (new key → new path).
 * Falls back to [stableId] when the URL is blank (nothing to render anyway).
 */
fun avatarCacheKey(url: String?, stableId: String): String {
    if (url.isNullOrBlank()) return stableId
    val q = url.indexOf('?')
    val path = if (q >= 0) url.substring(0, q) else url
    return path.ifBlank { stableId }
}

/**
 * Rounded-square avatar (WeChat/企业微信 style, 与 Web Avatar 一致:圆角 = 边长 20%)
 * with an initials fallback.
 *
 * [cacheKey] is a STABLE per-user identity (e.g. "avatar:<userId>") used only as
 * a fallback: the effective Coil key is derived from the avatar URL's path via
 * [avatarCacheKey], so a changed avatar (new object key) actually re-fetches.
 */
@Composable
fun MemberAvatar(
    name: String,
    url: String?,
    cacheKey: String,
    size: Dp = Dimens.AvatarM,
    modifier: Modifier = Modifier,
) {
    val effectiveKey = avatarCacheKey(url, cacheKey)
    // Reset on the FULL url, not effectiveKey: a presign rotation keeps the same
    // path (so the Coil cache key is stable) but yields a fresh signature that
    // deserves a retry. Keying `failed` on effectiveKey would pin the avatar to
    // initials forever after one expired-URL 403.
    var failed by remember(url) { mutableStateOf(false) }
    val showImage = !url.isNullOrBlank() && !failed

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.2f))
            .background(if (showImage) Color.Transparent else fallbackColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        if (showImage) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .memoryCacheKey(effectiveKey)
                    .diskCacheKey(effectiveKey)
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
    // 调色板在 core-design 的 Color.kt:顺序和长度不能改,改了全公司头像换色。
    val palette = AvatarFallbackPalette
    // floorMod (not abs %): abs(Int.MIN_VALUE) is still negative → would crash
    // with a negative index for a name whose hashCode is exactly Int.MIN_VALUE.
    return palette[Math.floorMod(name.hashCode(), palette.size)]
}
