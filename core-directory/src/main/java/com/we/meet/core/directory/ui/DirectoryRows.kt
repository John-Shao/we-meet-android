package com.we.meet.core.directory.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.core.directory.data.MemberDto
import com.we.meet.ui.theme.Dimens

/**
 * 通讯录名单的统一零件:**人/群行**、**入口行**、**字母小节头**,以及配套的两条分隔线。
 *
 * 为什么要有这个文件:改之前「头像 + 名字 + 副标题（+ 尾巴）」这一行在 App 里手写了
 * **七份** —— 内部联系人、星标联系人、我的群组、消息特别提醒、外部联系人、选人器、
 * 日历的选人器。它们的上下内边距 8/12/16dp 各不同、分隔线有的缩进 72dp 有的没有、
 * 有的左右还没有边距（选人器落在弹层里）。同一份名册换个入口进来就换一副长相,而这
 * 一行是整个模块里出现次数最多的东西,只能有一个写法。
 *
 * **这是名单自己的规矩,不是设置页那套。** 设置页的字段行(`SettingsList.kt`)是卡片
 * 里的一行「标签 + 值/开关」,没有头像、没有副标题;名单里的一行是「头像 + 名字 +
 * 副标题」,落在浅灰底上、按字母分段。两者只共用 `Dimens` 里的那几个数。别拿一边的
 * 行去套另一边:把名单行写成 56dp 的卡片字段行(或反过来)都不是统一,是错位。
 *
 * 名单的几何(所有名单页共用):
 *
 * | 项 | 取值 |
 * |---|---|
 * | 行高 | 头像 40dp + 上下 [Dimens.SpaceS] ≈ 56dp(≥48dp 热区) |
 * | 行内边距 | 左右 [Dimens.ScreenPadding](16dp) |
 * | 名字 | `bodyLarge`;副标题 `bodySmall` + `onSurfaceVariant` |
 * | 头像 | [MemberAvatar] 40dp(列表行唯一头像写法) |
 * | 条目白底 | 名单落在浅灰滚动区上,所以行自带 `surface`;弹层里传 `background = false` |
 * | 分隔线 | [MemberRowDivider]:`outlineVariant`,缩进 [Dimens.DividerIndentAvatar] |
 * | 小节头 | [DirectoryLetterHeader]:`background` 灰底 + `labelMedium`,它是名单的底 |
 * | 箭头 | 24dp `KeyboardArrowRight`(与入口行同一个尺寸;名单行默认不画) |
 *
 * **尾巴按语义选,不按页面选**(这也是唯一允许各页不同的地方):
 *
 * - 点进去还有一页 → [ContactListRow] 的 `showChevron`;
 * - 能勾选 → `trailing = { Checkbox(...) }`(选人器);
 * - 行尾有个动作 → `trailing = { TextButton(...) }`(消息特别提醒的「关闭」);
 * - 什么都没有 → 不传(名单里点一下就是打开这个人,再画个箭头只是噪声)。
 */
@Composable
fun ContactListRow(
    name: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /**
     * 高亮的名字(搜索命中)。给了它就按它渲染、忽略 [name] —— 只有列表自己知道
     * 哪几个字命中,而"名字怎么排"这件事仍然归这里管,所以走参数而不是让调用方
     * 自己再写一遍行。
     */
    nameAnnotated: AnnotatedString? = null,
    /** 名字右边的徽标(星标 ⭐ / 特别提醒 🔔 之类)。纯装饰,放在名字与副标题之间。 */
    badge: (@Composable () -> Unit)? = null,
    /** 主位头像;不传就是 [MemberAvatar] 的首字母兜底块。 */
    avatar: (@Composable () -> Unit)? = null,
    /** 行尾控件(Checkbox / TextButton)。给了它就不画箭头。 */
    trailing: (@Composable () -> Unit)? = null,
    /** 点进去还有一页时置 true。有 [trailing] 时无意义。 */
    showChevron: Boolean = false,
    /** 行底色:名单里 true(白条目落在浅灰上);弹层里 false(弹层本身就是白的)。 */
    background: Boolean = true,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (background) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
    ) {
        if (avatar != null) {
            avatar()
        } else {
            MemberAvatar(name = name, url = null, cacheKey = "initial:$name")
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = Dimens.SpaceM),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = nameAnnotated ?: AnnotatedString(name),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Spacer(Modifier.width(Dimens.SpaceXs))
                    badge()
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            trailing != null -> {
                Spacer(Modifier.width(Dimens.SpaceS))
                trailing()
            }

            showChevron -> {
                Spacer(Modifier.width(Dimens.SpaceXs))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 成员行:[MemberDto] 的那一层包装,副标题默认拼「职务 · 部门」。
 *
 * @param showDepartment 部门视图里整列都是同一个部门,再写一遍是零信息;
 *   「全部成员」「选人器」里部门恰恰是这个人唯一的区别。
 * @param subtitleOverride 完全接管副标题(选人器要在最前面加「外部」标签)。
 * @param avatarSize 头像尺寸;详情页/大列表要更大一档时传。
 */
@Composable
fun MemberRow(
    member: MemberDto,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    showDepartment: Boolean = true,
    subtitleOverride: String? = null,
    avatarSize: androidx.compose.ui.unit.Dp = Dimens.AvatarM,
    badge: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showChevron: Boolean = false,
    background: Boolean = true,
    enabled: Boolean = true,
) {
    val subtitle = subtitleOverride ?: listOfNotNull(
        member.title?.takeIf { it.isNotBlank() },
        if (showDepartment) member.department?.name?.takeIf { it.isNotBlank() } else null,
    ).joinToString(" · ")

    ContactListRow(
        name = member.displayName,
        modifier = modifier,
        subtitle = subtitle,
        badge = badge,
        avatar = {
            MemberAvatar(
                name = member.displayName,
                url = member.avatarUrl,
                cacheKey = "avatar:${member.id}",
                size = avatarSize,
            )
        },
        trailing = trailing,
        showChevron = showChevron,
        background = background,
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * 入口行:左侧一个图标 + 一行文字(+ 可选行尾小字/控件)。
 *
 * 通讯录首页那四个入口与部门下钻里的部门行是同一个形状(飞书的设置/入口列表也是),
 * 差别只在图标颜色:首页那几个固定入口是**品牌蓝**的动作入口,部门行是**导航内容**
 * 所以用次要色。
 *
 * 行高 = 图标 24dp + 上下 [Dimens.SpaceM] ≈ 48dp(正好是热区下限)。
 */
@Composable
fun DirectoryEntryRow(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** 行尾小字(部门人数这类)。 */
    trailingText: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(Dimens.IconMedium),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = Dimens.ScreenPadding),
        )
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Dimens.SpaceS),
            )
        }
        when {
            trailing != null -> {
                Spacer(Modifier.width(Dimens.SpaceS))
                trailing()
            }

            else -> {
                Spacer(Modifier.width(Dimens.SpaceXs))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 字母小节头(A–Z / #)。底色取页面 `background`(浅灰):它属于**名单的底**,
 * 白条目之间那道灰缝由它给出,所以不能画成白的(画成白的名单会看着凹进去)。
 */
@Composable
fun DirectoryLetterHeader(
    initial: String,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.AlphabetHeaderHeight)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = Dimens.ScreenPadding),
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 成员行之间的分隔线:缩进到头像右侧,与名字左缘对齐。 */
@Composable
fun MemberRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = modifier.padding(start = Dimens.DividerIndentAvatar),
    )
}

/** 入口/部门行之间的分隔线:缩进到图标右侧,与文字左缘对齐。 */
@Composable
fun EntryRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = modifier.padding(start = Dimens.DividerIndent),
    )
}
