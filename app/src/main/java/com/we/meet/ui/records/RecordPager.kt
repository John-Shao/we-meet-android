package com.we.meet.ui.records

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.ui.theme.Dimens

/**
 * 记录 / 纪要各面板共用的翻页行：**上一页 · 下一页 · 刷新**。
 *
 * 建它的理由不是「少写几行」：这一行此前在七个地方各写一遍，而七处的行为都不一样 ——
 * 有的带刷新有的不带、有的在某些条件下把「下一页」藏起来、有的整行随列表滚走、
 * 有的左右内边距跟同屏其它行对不齐。用户在**同一次会话**里翻这三种页，手感却不一致。
 *
 * 规则收在这里：
 * - **单页时整行不出现**。只有「刷新」而没有上一页/下一页的那一行，是挂在底部的
 *   一个孤立动作；失败态与空态各自带重试，不需要它（这条来自
 *   `RecordOriginals` 里原来的注释，现在由组件统一保证）。
 * - 「上一页」靠左、「下一页」与「刷新」靠右（[Spacer] 顶开），三个都是同级导航动作，
 *   字号与点击区一致，不做层级区分。
 * - 左右内边距固定 [Dimens.ScreenPadding]，与同屏的列表行对齐。
 *
 * @param hasPrevious 还有上一页吗（没有就不占位）。
 * @param hasNext 还有下一页吗（同上）。
 * @param enabled 整行是否可点（分页请求进行中时置 false，避免连点翻页）。
 * @param onRefresh 传 null 表示这一处不要刷新按钮。
 */
@Composable
internal fun RecordPager(
    hasPrevious: Boolean,
    onPrevious: () -> Unit,
    hasNext: Boolean,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onRefresh: (() -> Unit)? = null,
) {
    if (!hasPrevious && !hasNext) return
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasPrevious) {
            TextButton(onClick = onPrevious, enabled = enabled) {
                Text(stringResource(R.string.records_previous))
            }
        }
        Spacer(Modifier.weight(1f))
        if (hasNext) {
            TextButton(onClick = onNext, enabled = enabled) {
                Text(stringResource(R.string.records_next))
            }
        }
        if (onRefresh != null) {
            TextButton(onClick = onRefresh, enabled = enabled) {
                Text(stringResource(R.string.records_refresh))
            }
        }
    }
}
