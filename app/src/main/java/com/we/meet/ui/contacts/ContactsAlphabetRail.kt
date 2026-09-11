package com.we.meet.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.we.meet.R
import com.we.meet.ui.theme.Dimens

/**
 * 通讯录右侧的 A–Z 索引条。
 *
 * 几个取舍:
 *  - **位置固定、字母齐全**:没人(`count == 0`)的字母画成禁用态而不是不画 ——
 *    字母的位置固定,手指才有肌肉记忆;少画一个字母会让它上面所有字母都挪一格。
 *  - 行高只有 [Dimens.AlphabetRailRowHeight](27 个字母要一屏放得下)。低于 Material
 *    的 48dp 热区,这是索引条这类控件的固有取舍:热区够大就得滚动,而一个要滚动的
 *    索引条比没有更糟。命中率靠宽度补一点。
 *  - 不用 `IconButton`:它会要求 48dp 触达尺寸(设计规范 §5.2),27 个字母会直接
 *    撑爆屏幕。所以这里用 clickable 的 Box,并在 KDoc 里说清为什么。
 *  - 无障碍:每个字母都带「跳到 X 开头」/「X 没有成员」/「取消 X 起点」的朗读文案;
 *    已经选中的字母再点一次是取消起点,文案照实说,不靠颜色猜。
 */
@Composable
fun ContactsAlphabetRail(
    slots: List<AlphabetSlot>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (slots.isEmpty()) return
    val railLabel = stringResource(R.string.contacts_alphabet_rail)
    val otherLabel = stringResource(R.string.contacts_alphabet_other)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(Dimens.AlphabetRailWidth)
            .fillMaxHeight()
            // 极窄屏 / 横屏下 27 个字母也可能放不下:那时至少要能滚,而不是被裁掉。
            .verticalScroll(rememberScrollState())
            .padding(vertical = Dimens.SpaceXs)
            .semantics { contentDescription = railLabel },
    ) {
        slots.forEach { slot ->
            val label = if (slot.letter == OTHER_INITIAL) otherLabel else slot.letter
            val description = when {
                slot.clearing -> stringResource(R.string.contacts_alphabet_clear, label)
                slot.enabled -> stringResource(R.string.contacts_alphabet_jump, label)
                // 禁用也给出理由:TalkBack 只念「L,已停用」时用户不知道是没人还是坏了。
                else -> stringResource(R.string.contacts_alphabet_empty, label)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(Dimens.AlphabetRailWidth)
                    .height(Dimens.AlphabetRailRowHeight)
                    .background(
                        // 选中的那个字母是**状态**,用底色表达比只改字色清楚;
                        // 未选中用透明色,免得整条竖条看着像有底纹。
                        color = if (slot.active) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(Dimens.CornerS),
                    )
                    .clickable(enabled = slot.enabled, onClickLabel = description) {
                        onPick(slot.letter)
                    }
                    .semantics { contentDescription = description },
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (slot.active) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    color = when {
                        slot.active -> MaterialTheme.colorScheme.onPrimaryContainer
                        slot.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        // 禁用的字母要比正文淡一档,但仍要看得见 —— 它表达的是
                        // 「这一册里没有这个字母开头的人」,不是「这里没有这个字母」。
                        // 用前景槽位 + 透明度,而不是 outline 那类面色(outline 当
                        // 文字色实测只有 4.44:1,设计规范 §1.1 点名过)。
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                    maxLines = 1,
                )
            }
        }
    }
}
