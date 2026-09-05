package com.we.meet.feature.im.ui.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme

/**
 * 机器人头像:有图走图,否则「预设色底 + 机器人图标」。
 *
 * 不直接复用 [MemberAvatar] 的兜底:它的底色是**按名字哈希**取的,而机器人的
 * 底色是创建者在表单里明确挑的第 N 号 —— 挑了蓝色渲染成绿色是 bug,不是降级。
 * 有图时仍转交 MemberAvatar,共用同一套 Coil 缓存键规则(头像是短时签名 URL,
 * `avatarCacheKey` 已经处理了签名轮换)。
 *
 * 后端总会把色块渲染成真图,所以这里的色块分支实际上只是防御。
 */
@Composable
internal fun BotAvatar(
    name: String,
    avatarUrl: String?,
    colorIndex: Int,
    cacheKey: String,
    size: Dp = Dimens.AvatarM,
    modifier: Modifier = Modifier,
) {
    if (!avatarUrl.isNullOrBlank()) {
        MemberAvatar(
            name = name,
            url = avatarUrl,
            cacheKey = cacheKey,
            size = size,
            modifier = modifier,
        )
        return
    }
    val palette = WeMeetTheme.extras.im.botAvatarPalette
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 20))
            .background(palette[colorIndex.coerceIn(0, palette.lastIndex)]),
    ) {
        Icon(
            imageVector = Icons.Filled.SmartToy,
            contentDescription = null,
            tint = WeMeetTheme.extras.im.avatarContent,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}
