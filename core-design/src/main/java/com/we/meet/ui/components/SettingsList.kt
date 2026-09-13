package com.we.meet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.ui.theme.Dimens

/**
 * 设置页的统一组件 —— **设置总页与它的每一个子页面都用这一套**,不要再各写各的
 * 行/卡片(见 docs/设计规范.md §设置页)。
 *
 * 建这套东西的原因:改之前六个设置页面有六种写法 —— 行高 40/48/56dp 三种、
 * 卡片圆角 12/16dp 两种、页面底色两种、分隔线有/没有两种、箭头是
 * `KeyboardArrowRight` 还是 `Outlined.ChevronRight` 两种,连"分组标题"也只在
 * 日历设置里存在。同一种东西在不同页面长得不一样,用户每翻一页都要重新认一遍。
 *
 * 约定(改之前先读这张表):
 *
 * | 项 | 取值 |
 * |---|---|
 * | 页面底色 | 主题 `background`(浅灰),由 Scaffold 提供;卡片浮在上面 |
 * | 分组卡片 | 白底 `surface` + [Dimens.CornerM] 圆角 + 左右 [Dimens.ScreenPadding] |
 * | 分组之间 | [Dimens.SpaceL];首组距顶栏 [Dimens.SpaceL];末组距底 [Dimens.SpaceXl] |
 * | 组内行高 | 上下 [Dimens.SpaceL] 内边距(≈56dp,热区 ≥48dp) |
 * | 组内分隔 | [SettingsDivider];单行组不需要 |
 * | 分组标题 | [SettingsGroupHeader]:`titleSmall` + `onSurfaceVariant`,在卡片上方 |
 * | 行标签 | `bodyLarge`;值 `bodyMedium` + `onSurfaceVariant` |
 * | 箭头 | `KeyboardArrowRight`,[Dimens.IconSmall],`onSurfaceVariant` |
 * | 说明文字 | [SettingsHint]:`bodySmall` + `onSurfaceVariant`,贴着它解释的那张卡片 |
 */

/**
 * 一组设置项的白卡片。行与行之间自己插 [SettingsDivider](单行组不用插)。
 *
 * 不在这里自动插分隔线:Compose 数不出子项,而且组里可能混着分隔线不该出现的东西
 * (说明文字、加载态)。显式写一行 `SettingsDivider()` 比猜要清楚。
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .clip(RoundedCornerShape(Dimens.CornerM))
            .background(MaterialTheme.colorScheme.surface),
        content = content,
    )
}

/** 组内两行之间的分隔线:与行内文字左缘对齐(左右各缩进 [Dimens.ScreenPadding])。 */
@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = modifier.padding(horizontal = Dimens.ScreenPadding),
    )
}

/**
 * 分组小标题 —— 卡片**上方**那一行灰字(如「时区」「工作时间」)。
 *
 * 左缘与卡片外缘对齐、不与行内文字对齐:它是这一组东西的标签,不是其中一行
 * (飞书/微信的设置页同理)。
 */
@Composable
fun SettingsGroupHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .padding(top = Dimens.SpaceL, bottom = Dimens.SpaceS),
    )
}

/**
 * 卡片下方的说明文字。语气是"补充一句",所以 `bodySmall` + `onSurfaceVariant`,
 * 且与卡片左缘对齐 —— 改之前这一行有的页面缩进 16dp、有的 24dp,同一屏里两种边距。
 */
@Composable
fun SettingsHint(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .padding(top = Dimens.SpaceS),
    )
}

/**
 * 一行设置项。
 *
 * 三件事按同一个规矩来:左标签、右值(或 [trailing] 控件)、点了会跳才给箭头。
 * `onClick != null && trailing == null` 才画箭头 —— 有开关的行再画一个箭头会让人
 * 以为「点进去还有一页」。
 *
 * @param label 主文字。
 * @param subtitle 第二行小字(解释这个开关是干什么的)。只有确实需要解释时给。
 * @param value 右侧的值(当前选择)。太长时从左侧省略,不会把标签挤没。
 * @param onClick 点整行的动作;null = 这一行不可点(只读)。
 * @param enabled 置灰并屏蔽点击(保存中、加载中)。
 * @param valueLoading 值还在路上(例如组织名首次加载):值的位置画一条灰条,
 *   行高不变 —— 数据回来是"填进去",不是把下面的行推下去。
 * @param trailing 右侧控件(开关等)。给了它就不再画箭头和 [value]。
 */
@Composable
fun SettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    valueLoading: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    // 置灰用「次要色再压透明度」,而不是另找一个灰:这套主题的灰只有这一档是
    // 为「不可用」准备的(与 NotificationSettingsScreen 原来那行同样的口径)。
    val labelColor = if (enabled) onSurface else secondary.copy(alpha = DISABLED_ALPHA)
    val valueColor = if (enabled) secondary else secondary.copy(alpha = DISABLED_ALPHA)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = labelColor,
            )
            if (subtitle != null) {
                Spacer(Modifier.width(Dimens.SpaceXxs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = valueColor,
                )
            }
        }
        when {
            trailing != null -> {
                Spacer(Modifier.width(Dimens.SpaceM))
                trailing()
            }

            valueLoading -> {
                Spacer(Modifier.width(Dimens.SpaceM))
                Box(
                    modifier = Modifier
                        .size(width = Dimens.SkeletonBarWidth, height = Dimens.SkeletonBarHeight)
                        .clip(RoundedCornerShape(Dimens.CornerXs))
                        .background(secondary),
                )
            }

            value != null -> {
                // 值这一格与标签各占一半:值右对齐,标签长了就从左边省略 ——
                // 反过来(标签不设权重)会让长值把标签整行挤出屏幕。
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = valueColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (trailing == null && onClick != null) {
            Spacer(Modifier.width(Dimens.SpaceXs))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = if (enabled) secondary else secondary.copy(alpha = DISABLED_ALPHA),
                modifier = Modifier.size(Dimens.IconSmall),
            )
        }
    }
}

/**
 * 居中的动作行 —— 「退出登录」「注销账号」这种只有一个动作、没有值的行。
 * 危险动作用 `contentColor = MaterialTheme.colorScheme.error`;行本身照样落在
 * 一张 [SettingsGroup] 里。
 */
@Composable
fun SettingsActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceL),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
        )
    }
}

/** [SettingsPickerSheet] 的一个选项。 */
data class SettingsPickerOption(
    val label: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
)

/**
 * 选择型设置项的弹层(主题、语言、时区、默认时长……)。
 *
 * 全设置树统一走它:改之前同一个"选一个值"的动作,有的页面是贴着行的
 * `DropdownMenu`(还得靠 `wrapContentSize(TopEnd)` 把它拽到右边缘)、有的是
 * `ModalBottomSheet`、还有原生 `TimePickerDialog`。底部弹层的信息容量、触控区和
 * 长列表滚动都更好,而且不用再跟锚点较劲。
 *
 * 打开时自动滚到当前选中项附近,长列表(时区)不必手动翻。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPickerSheet(
    title: String,
    options: List<SettingsPickerOption>,
    onDismissRequest: () -> Unit,
) {
    val selectedIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (selectedIndex - PICKER_SHEET_LEAD_IN).coerceAtLeast(0),
    )
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = Dimens.SheetContentMaxHeight),
            contentPadding = PaddingValues(bottom = Dimens.SpaceXl),
        ) {
            itemsIndexed(options) { _, option ->
                ListItem(
                    headlineContent = { Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        if (option.selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(Dimens.IconMedium),
                            )
                        }
                    },
                    modifier = Modifier.clickable {
                        option.onSelect()
                        onDismissRequest()
                    },
                )
            }
        }
    }
}

/** 置灰态的不透明度:与 M3 的 disabled 一致。 */
private const val DISABLED_ALPHA = 0.38f

/** 弹层打开时在选中项上方留几行,让用户看到"上面还有"。 */
private const val PICKER_SHEET_LEAD_IN = 2
