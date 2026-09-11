package com.we.meet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.we.meet.ui.theme.Dimens

/**
 * 可横向滚动的 chip 行 —— 搜索分类、筛选条件这类「一排圆角标签」。
 *
 * 建它的直接原因是**装不下的那几个 chip 没有任何提示**。搜索页的分类行有
 * 六个以上,任务页的筛选行有五个,两行都溢出屏幕:用户看到的是一排整齐的
 * chip,不会想到右边还有;更糟的是脚本排列下**左右两端被切掉的可能是当前
 * 选中项或默认项** —— 截图上「全部截止时间」正好卡在右边缘被拦腰截断。
 *
 * 所以在两端补一层渐隐:只在那个方向确实还能滚的时候出现,滚到头就消失。
 * 渐隐是纯装饰的 Box,不处理指针事件,底下的 chip 该点还是点得到。
 *
 * 间距与左右留白固定为本组件的责任 —— 三个调用处原先各写各的
 * `contentPadding`,扫一眼看不出差异,改一处也生效不了。
 *
 * **假设所在区域的底色是 `surface`**(渐隐是从 `surface` 渐到透明)。目前两处
 * 调用(搜索页分类行、任务页筛选行)都成立;换到别的底色区域时要重新想这件事。
 *
 * ```
 * WeMeetChipRow(state = listState) {
 *     items(categories) { cat -> FilterChip(...) }
 * }
 * ```
 */
@Composable
fun WeMeetChipRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val scrollBack by remember { derivedStateOf { state.canScrollBackward } }
    val scrollForward by remember { derivedStateOf { state.canScrollForward } }

    Box(modifier = modifier) {
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            contentPadding = PaddingValues(
                horizontal = Dimens.ScreenPadding,
                vertical = Dimens.SpaceXs,
            ),
            content = content,
        )
        if (scrollBack || scrollForward) {
            // 渐隐层必须**不参与**外层 Box 的尺寸计算。
            //
            // 直接把它放进外层 Box 会踩一个很隐蔽的坑:渐隐条内部用 fillMaxHeight()
            // 撑满高度,而外层 Box 是 wrap-content —— fillMaxHeight 解析的是**传入的
            // 最大高度约束**(= 父级剩余的全部空间),于是这一行的 Box 高度变成整屏,
            // 行本身照常显示在顶部,后面的内容全被挤出屏幕。
            //
            // matchParentSize() 正好是干这个的:自身尺寸跟随父 Box,但不反过来影响
            // 父 Box 的高度测量。
            Box(modifier = Modifier.matchParentSize()) {
                if (scrollBack) {
                    EdgeFade(
                        alignment = Alignment.CenterStart,
                        colors = listOf(MaterialTheme.colorScheme.surface, Color.Transparent),
                    )
                }
                if (scrollForward) {
                    EdgeFade(
                        alignment = Alignment.CenterEnd,
                        colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surface),
                    )
                }
            }
        }
    }
}

/** 一端压在 chip 行上的渐隐条。宽度取一档间距,够柔和不至于吃掉整块 chip。 */
@Composable
private fun BoxScope.EdgeFade(alignment: Alignment, colors: List<Color>) {
    Box(
        modifier = Modifier
            .align(alignment)
            .width(Dimens.SpaceXl)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(colors)),
    )
}
