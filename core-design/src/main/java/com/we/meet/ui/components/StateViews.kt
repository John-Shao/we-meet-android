package com.we.meet.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.we.meet.design.R
import com.we.meet.ui.theme.Dimens

/**
 * 列表/页面的三种「非内容态」:加载中、空、出错。
 *
 * 建这三个组件是因为原先 22 处各写各的 loading、31 处各写各的空态 —— 有的
 * 居中有的靠上,有的给了重试有的没给。三态的视觉和行为必须一致,用户才能
 * 建立预期。
 *
 * 用法上把三态当一组处理,别只写 loading 就完事:
 * ```
 * when {
 *     state.loading && state.items.isEmpty() -> WeMeetLoading()
 *     state.error && state.items.isEmpty() -> WeMeetErrorState(onRetry = vm::refresh)
 *     state.items.isEmpty() -> WeMeetEmptyState(title = stringResource(R.string.xxx_empty))
 *     else -> /* 列表 */
 * }
 * ```
 * 注意三处 `items.isEmpty()`:**已有内容时不要用整屏态盖掉内容** —— 刷新
 * 失败应该是个 Snackbar,不是把用户已经看到的列表换成一张错误图。
 */

/** 整屏加载态:居中转圈。用于首屏拉取,不用于分页加载更多。 */
@Composable
fun WeMeetLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/** Compact loading state for a section inside an already visible page. */
@Composable
fun WeMeetInlineLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceL),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(Dimens.IconMedium),
            strokeWidth = Dimens.ProgressStroke,
        )
    }
}

/**
 * 空态:没有内容,但也没出错。
 *
 * 文案规矩 —— [title] 说「现在是什么情况」,别写「暂无数据」这种系统腔;
 * [description] 说「怎么才能有」。能给下一步动作就给 [action],空态是引导
 * 用户的机会,不只是占位。
 *
 * @param title 主文案,必填。
 * @param description 补充说明,可省。
 * @param icon 图标,可省;不传就只有文字,同样合规。
 * @param action 底部动作区,放一个按钮。
 */
@Composable
fun WeMeetEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.SpaceXxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    // 纯装饰:文案已经说清楚了,再读一遍图标是噪音
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.IconIllustration),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (action != null) {
                Box(Modifier.padding(top = Dimens.SpaceS)) { action() }
            }
        }
    }
}

/**
 * 错误态:加载失败。
 *
 * 和空态的区别是**必须给重试** —— 用户遇到失败却无路可走是最伤的体验。
 * [onRetry] 因此不可空。
 *
 * @param message 错误文案,默认是通用的网络失败提示。只有在能给出更具体、
 *   对用户更有用的说明时才覆盖它(比如「该会议已结束」)。
 */
@Composable
fun WeMeetErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.SpaceXxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
        ) {
            Text(
                text = message ?: stringResource(R.string.common_load_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.common_retry))
            }
        }
    }
}

/** Retryable error state for a section, card, or sheet within an existing page. */
@Composable
fun WeMeetInlineErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceS),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
    ) {
        Text(
            text = message ?: stringResource(R.string.common_load_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.common_retry))
        }
    }
}
