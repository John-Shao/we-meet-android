package com.we.meet.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 跨端字体契约的 Android 映射。
 *
 * 15 档遵循 Material 3 type scale,值与 Web 仓库
 * `src/design-tokens/typography.tokens.json` 一一对应。这里显式列出而不是依赖
 * Compose 默认值,避免升级 Material 后 App 字阶静默变化。字体族仍采用 Android
 * 系统默认(Roboto/Noto CJK),Web 则采用自己的 system-ui fallback；共享的是语义、
 * 字号、行高、字重与字距,不是强迫两个平台加载同一份字体文件。
 */
val JusiTypography: Typography = Typography(
    displayLarge = weMeetTextStyle(57, 64, letterSpacing = -0.25f),
    displayMedium = weMeetTextStyle(45, 52),
    displaySmall = weMeetTextStyle(36, 44),
    headlineLarge = weMeetTextStyle(32, 40),
    headlineMedium = weMeetTextStyle(28, 36),
    headlineSmall = weMeetTextStyle(24, 32),
    titleLarge = weMeetTextStyle(22, 28, FontWeight.Medium),
    titleMedium = weMeetTextStyle(16, 24, FontWeight.Medium, 0.15f),
    titleSmall = weMeetTextStyle(14, 20, FontWeight.Medium, 0.1f),
    bodyLarge = weMeetTextStyle(16, 24, letterSpacing = 0.5f),
    bodyMedium = weMeetTextStyle(14, 20, letterSpacing = 0.25f),
    bodySmall = weMeetTextStyle(12, 16, letterSpacing = 0.4f),
    labelLarge = weMeetTextStyle(14, 20, FontWeight.Medium, 0.1f),
    labelMedium = weMeetTextStyle(12, 16, FontWeight.Medium, 0.5f),
    labelSmall = weMeetTextStyle(11, 16, FontWeight.Medium, 0.5f),
)

private fun weMeetTextStyle(
    fontSize: Int,
    lineHeight: Int,
    fontWeight: FontWeight = FontWeight.Normal,
    letterSpacing: Float = 0f,
): TextStyle = TextStyle(
    fontSize = fontSize.sp,
    lineHeight = lineHeight.sp,
    fontWeight = fontWeight,
    letterSpacing = letterSpacing.sp,
)

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
     * 里的时间。正文和可点击文字一律不许用这么小。
     *
     * 这条 11sp 下限是**本规范自订**的(§1.2),不是 WCAG 要求 —— WCAG 没有
     * 最小字号条款,SC 1.4.4 管的是「能放大到 200% 而不丢内容」,`sp` 跟随
     * 系统字体缩放本身就满足。所以这里的判据是可读性,不是合规。
     */
    val LabelTiny: TextStyle = TextStyle(
        fontSize = 10.sp,
        // 行高取 12 而不是「正常」的 14:这一档的使用方全是密集场景(日历网格
        // 里的日程块、时刻刻度、画面块角标),多 2sp 行距就可能让两行标题在
        // 15 分钟的短块里被裁掉。单行场景取紧一点也无害。
        lineHeight = 12.sp,
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
     * 同样地,这是可读性欠账,不是合规缺陷(理由见 [LabelTiny])。
     *
     * 收在这里是为了让这笔账只有一处、可一次算清,不是为它背书。三处用法的
     * 修法各不相同,不是统一缩放能解决的:
     * - **有标题的日程块**:这一行是时间,而时间在时间网格上已经由块的位置和
     *   高度表达了 —— 冗余信息,直接删掉这行即可,不需要更小的字;
     * - **无标题的忙闲块**:这一行是唯一内容,提到 [LabelTiny] 即可,块高门槛
     *   (`BUSY_TIME_LABEL_MIN`)本来就在挡放不下的情况。
     */
    val LabelMicro: TextStyle = TextStyle(
        fontSize = 9.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.Medium,
    )

    /**
     * 表情选择器里的大表情。
     *
     * 这不是「文字」而是「图形」—— 26sp 是让人一眼认出表情所需的尺寸,不参与
     * 正文的字号阶梯,也不受 11sp 可读下限那条规矩约束。
     */
    val EmojiPicker: TextStyle = TextStyle(fontSize = 26.sp)

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
