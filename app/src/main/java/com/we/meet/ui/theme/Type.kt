package com.we.meet.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * App 字体阶梯 = Material 3 默认 type scale,一个字号也不改。
 *
 * 保持默认是有意的:M3 的 15 档已经覆盖了会议 App 的全部层级,自己重写一遍
 * 只会引入视觉回归。这里包一层是为了给「换字体」和「CJK 字距微调」留一个
 * 唯一入口 —— 要改就在这改,别在页面里 `.copy(fontSize = …)`。
 *
 * 对应关系(挑常用的记):
 * ```
 * headlineSmall  24sp  页面大标题(登录页、空页面主文案)
 * titleLarge     22sp  TopAppBar 标题
 * titleMedium    16sp  卡片/列表行主标题
 * titleSmall     14sp  分组小标题
 * bodyLarge      16sp  正文
 * bodyMedium     14sp  次要正文、列表行副标题
 * bodySmall      12sp  辅助说明、时间戳
 * labelLarge     14sp  按钮文字
 * labelMedium    12sp  标签
 * labelSmall     11sp  最小可读标签
 * ```
 */
val JusiTypography: Typography = Typography()

/**
 * M3 阶梯够不到的几档,补在这里。
 *
 * 和 [Dimens] 同理:这些是与主题无关的常量,不随深浅色变,所以用普通 object
 * 而不是 CompositionLocal。
 *
 * 只在确有需要时才加新档 —— 每多一档,页面之间「差不多但不一样」的概率就
 * 高一分。加之前先确认 [JusiTypography] 里真的没有能用的。
 */
object WeMeetTextStyles {

    /**
     * 比 `labelSmall`(11sp)更小的一档,10sp。
     *
     * 仅用于**受限空间内的非关键信息**:视频画面角标、未读数气泡、日历格
     * 里的时间。正文和可点击文字一律不许用这么小 —— 低于 11sp 在小屏上已
     * 经触碰 WCAG 的可读性下限。
     */
    val LabelTiny: TextStyle = TextStyle(
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
    )

    /**
     * 9sp,比 [LabelTiny] 还小一档。
     *
     * ⚠️ **不要在新代码里用。** 规范 §1.2 写明可读下限是 11sp,这一档是给
     * 日历时间网格的历史欠账兜底的 —— 那里的日程小块高度由时长决定,15 分钟
     * 的块放不下更大的字,直接调大会溢出裁切。
     *
     * 收在这里是为了让这笔账只有一处、可一次算清,不是为它背书。真正的修法
     * 是让小块在放不下时改为只显示色条不显示文字,而不是把字继续缩小。
     */
    val LabelMicro: TextStyle = TextStyle(
        fontSize = 9.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.Medium,
    )

    /**
     * 等宽数字风格的会议号/验证码展示。字距放宽,让 8 位数字好读、好抄。
     */
    val CodeDisplay: TextStyle = TextStyle(
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 4.sp,
    )
}
