package com.we.meet.ui.theme

import androidx.compose.ui.graphics.Color

// Brand seed and a small handful of derived tones.  Material 3 will fill in
// the rest of the palette via lightColorScheme/darkColorScheme.
val Seed = Color(0xFF1F6FEB)

val LightPrimary = Seed
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFD6E4FF)
val LightOnPrimaryContainer = Seed
val LightBackground = Color(0xFFFCFCFD)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF1A1C1E)

val DarkPrimary = Color(0xFF7BAAFB)
val DarkOnPrimary = Color(0xFF002F69)
val DarkPrimaryContainer = Color(0xFF1E3A7A)
val DarkOnPrimaryContainer = Color(0xFFD6E4FF)
val DarkBackground = Color(0xFF111418)
val DarkSurface = Color(0xFF1A1C1E)
val DarkOnSurface = Color(0xFFE2E2E5)

// --- Semantic tokens not covered by Material 3's ColorScheme ---------------
// These surface in [WeMeetExtras] and are resolved by [WeMeetTheme] based
// on the active light/dark mode, so callers never branch on the theme.

/** Thin tinted band used to separate zones on the Home page (飞书-style). */
val LightSurfaceBand = Color(0xFFF2F4F3)
val DarkSurfaceBand = Color(0xFF0A0A0A)

/**
 * 日历表态色:四态四色、一律实线 —— 接受=蓝、未反馈=紫、待定=琥珀、拒绝=灰。
 * 每档一对「强调色(竖条/色条)+ 文字色」,块底由强调色低透明推导。
 *
 * 色值与 Web 的 calendarGridOverrides.css 一一对应(接受档也用 #3370FF 而非
 * 品牌 primary,两端才真的同色),改色请两端同步。
 */
val LightRsvpAccepted = Color(0xFF3370FF)
val LightRsvpAcceptedText = Color(0xFF1E4DB3)
val LightRsvpNeeds = Color(0xFF8B5CF6)
val LightRsvpNeedsText = Color(0xFF5B21B6)
val LightRsvpTentative = Color(0xFFF59E0B)
val LightRsvpTentativeText = Color(0xFF92400E)
val LightRsvpDeclined = Color(0xFF9CA3AF)
val LightRsvpDeclinedText = Color(0xFF6B7280)

/**
 * 日历网格专用色(表态色见上面的 Rsvp 一组)。
 *
 * 这三档原先散在 5 处字面量里,其中 `DC2626`(冲突)和 `FF8800`(提醒橙)各自
 * 在两个文件里重复定义过 —— 正是 token 要消灭的情况。
 *
 * 都不复用 [LightDanger]:`conflict` 表示的是「这两个日程撞了」,是一种信息
 * 标注,不是「点下去会造成损失」,语义不同不该共用一个 token。
 */
val LightCalendarNowLine = Color(0xFFEF4444)
val LightCalendarConflict = Color(0xFFDC2626)
val LightCalendarReminder = Color(0xFFFF8800)

val DarkCalendarNowLine = Color(0xFFFF7A7A)
val DarkCalendarConflict = Color(0xFFF87171)
val DarkCalendarReminder = Color(0xFFFFA640)

val DarkRsvpAccepted = Color(0xFF5C8DFF)
val DarkRsvpAcceptedText = Color(0xFFA3B8EC)
val DarkRsvpNeeds = Color(0xFFA78BFA)
val DarkRsvpNeedsText = Color(0xFFC4B5FD)
val DarkRsvpTentative = Color(0xFFFBBF24)
val DarkRsvpTentativeText = Color(0xFFFCD34D)
val DarkRsvpDeclined = Color(0xFF8A9099)
val DarkRsvpDeclinedText = Color(0xFF9CA3AF)

/**
 * 状态色:危险 / 警告 / 成功 / 强调激活。
 *
 * M3 的 ColorScheme 只给了 `error` 一档,而会议 App 至少要区分「破坏性操作
 * (挂断、结束会议)」「进行中的提醒(举手、录制)」「正向状态(正在说话、
 * 已连接)」。这三档以前散在页面里写死 —— 同一个红色在 RoomScreen 里就有
 * `FF4444` / `EE4444` / `FF6B6B` 三个版本。收进来统一。
 *
 * 每档一组三色:`…` 主色、`…Container` 浅底、`…OnContainer` 浅底上的文字。
 */
// danger 用 Material Red 700 而非更艳的 E53935:白字压在 D32F2F 上对比度
// ≈4.6:1,刚好过 WCAG AA 正文门槛(4.5:1);E53935 只有 ≈4.2:1,不够。
val LightDanger = Color(0xFFD32F2F)
val LightOnDanger = Color(0xFFFFFFFF)
val LightDangerContainer = Color(0xFFFFE5E5)
val LightOnDangerContainer = Color(0xFF8E1515)
val LightWarning = Color(0xFFF59E0B)
val LightWarningContainer = Color(0xFFFFE6B3)
val LightOnWarningContainer = Color(0xFF92400E)
val LightSuccess = Color(0xFF00A344)
val LightSuccessContainer = Color(0xFFD6F5E0)
val LightOnSuccessContainer = Color(0xFF005222)
val LightAccentActive = Color(0xFF3366FF)
val LightAccentActiveContainer = Color(0xFFD9E8FF)
val LightOnAccentActiveContainer = Color(0xFF1E3A8A)

// 深色主题下 danger 是浅红(它同时充当图标前景色,必须在深底上够亮),
// 所以压在它上面的文字反过来要用深色。
val DarkDanger = Color(0xFFFF6B6B)
val DarkOnDanger = Color(0xFF4A0E0E)
val DarkDangerContainer = Color(0xFF5C1A1A)
val DarkOnDangerContainer = Color(0xFFFFD4D4)
val DarkWarning = Color(0xFFFBBF24)
val DarkWarningContainer = Color(0xFF5C4310)
val DarkOnWarningContainer = Color(0xFFFFE6B3)
val DarkSuccess = Color(0xFF4ADE80)
val DarkSuccessContainer = Color(0xFF12492A)
val DarkOnSuccessContainer = Color(0xFFD6F5E0)
val DarkAccentActive = Color(0xFF7BAAFB)
val DarkAccentActiveContainer = Color(0xFF1E3A7A)
val DarkOnAccentActiveContainer = Color(0xFFD9E8FF)

/**
 * IM(聊天与一对一通话)专用色。
 *
 * ⚠️ **两套通话配色目前不一致**,这是收进 token 后才暴露出来的:
 *
 * | 语义 | AI 通话 | IM 通话 |
 * |---|---|---|
 * | 挂断 | `E0524C` | `E5484D` |
 * | 接听 | `1FB85F` | `30A46C` |
 *
 * 同一个动作在两个界面是两种红/绿。本次迁移**只搬不改**,两边都保留原值,
 * 免得在纯重构里夹带视觉变更。要统一的话是个独立决定 —— 选定一套后把
 * 另一套删掉即可,调用处不用动。
 */
val ImCallHangUp = Color(0xFFE5484D)
val ImCallAccept = Color(0xFF30A46C)
/** 通话中的中性控件(免提、静音等未激活态)与其禁用态。 */
val ImCallNeutralControl = Color(0xFF6B7280)
val ImCallMutedControl = Color(0xFF9CA3AF)
/** 视频通话舞台:永远深色(要压住视频画面),不随主题。 */
val ImVideoStageBackground = Color(0xFF111418)
val ImVideoStageLabel = Color(0xEEFFFFFF)

/**
 * IM 连接状态条的四态配色,每档一对「底色 + 文字色」。
 *
 * 不复用 [LightSuccess] / [LightWarning] 那几档:那是**动作反馈**的语义,
 * 这里是**长驻的连接状态提示**,底色要更淡才不会一直抢注意力。
 */
val ImConnConnectedBg = Color(0xFFE7F5E7)
val ImConnConnectedFg = Color(0xFF1B5E20)
val ImConnConnectingBg = Color(0xFFFFF6E0)
val ImConnConnectingFg = Color(0xFF7A4F01)
val ImConnFailedBg = Color(0xFFFCE4E4)
val ImConnFailedFg = Color(0xFF8B0000)
val ImConnOfflineBg = Color(0xFFEDEDED)
val ImConnOfflineFg = Color(0xFF444444)

/** 群头像无图时的底色调色板。与人物头像的 [AvatarFallbackPalette] 分开 ——
 *  群和人在列表里混排,配色不同才好一眼区分。 */
val GroupAvatarPalette = listOf(
    Color(0xFF2563EB), Color(0xFF7C3AED), Color(0xFFDB2777),
    Color(0xFFEA580C), Color(0xFF16A34A), Color(0xFF0891B2),
)

/** 「@我」提及在消息正文里的高亮底与文字 —— 让人一眼扫到自己被点名。 */
val ImMentionSelfBg = Color(0xFFFDE68A)
val ImMentionSelfFg = Color(0xFF92400E)

/**
 * AI 通话页控件专用色。
 *
 * 这一屏的控件是**固定浅色**的:浅灰底 + 黑色图标,不随深浅色主题变(视频开启
 * 时画面本身变暗,靠 `onDark` 单独切文字色)。所以这几个值只有一套。
 *
 * 保持原样是有意的 —— 把它改成跟随主题是个真实的设计改动(深色模式下这排
 * 控件的观感会变),应当单独评估,不该混在 token 迁移里顺手做掉。
 */
val AiCallControlSurface = Color(0xFFEDEDED)
val AiCallHangUp = Color(0xFFE0524C)
val AiCallStartCall = Color(0xFF1FB85F)

/** 说话动效球:外层辉光两色 + 球体本身的扫描渐变(五档首尾同色,才能接成环)。 */
val AiCallSphereGlowOuter = Color(0xFF4DA8FF)
val AiCallSphereGlowInner = Color(0xFF1968F0)
val AiCallSphereGradient = listOf(
    Color(0xFF7BC1FF),
    Color(0xFF3D90FF),
    Color(0xFF1F6BFF),
    Color(0xFF3D90FF),
    Color(0xFF7BC1FF),
)

/**
 * 无头像时按名字哈希取色的调色板。
 *
 * 同一个人必须永远同色 —— 取色靠 `floorMod(name.hashCode(), size)`,所以**这个
 * 列表的顺序和长度都不能随便改**:改了等于全公司的头像颜色重新洗牌。
 */
val AvatarFallbackPalette = listOf(
    Color(0xFF5B8DEF),
    Color(0xFF7C6FE0),
    Color(0xFF43A88A),
    Color(0xFFE0876F),
    Color(0xFFD46A9C),
    Color(0xFF6FA8DC),
)

/**
 * 会中(RoomScreen)专用色。
 *
 * 会中界面永远是深色的 —— 视频画面之上必须压暗才看得清叠加控件,不跟随
 * 系统深浅色。所以这几个值只有一套,不分 Light/Dark。
 */
val RoomTileBackground = Color(0xFF2C3033)
val RoomSpeakingRing = Color(0xFF00C853)
val RoomOverlayScrim = Color(0xCC000000)
val RoomSubtitleText = Color(0xFFB3D6FF)
val RoomAvatarFallback = Color(0xFF3366FF)
/** 深色浮层上的危险前景色(挂断图标、静音标记)。浅色主题的 danger 太深,压不亮。 */
val RoomDangerOnOverlay = Color(0xFFFF6B6B)
/** 深色浮层上的强调文字(工具条上的「已开启」一类状态词)。 */
val RoomOverlayAccentText = Color(0xFF93C5FD)
/**
 * 实底横幅/按钮的底色与其上的前景色。
 *
 * 两组都按 WCAG AA 校过 —— 原先是白字压 #FFB300(约 2.1:1)和白字压 #FF4444
 * (约 3.4:1),都不过 4.5:1 的正文门槛:
 * - 琥珀保留色相,前景换成深棕(约 7.9:1),视觉识别度不变;
 * - 红压深到 Red 700,白字变成约 4.6:1。
 */
val RoomWarningFill = Color(0xFFFFB300)
val RoomOnWarningFill = Color(0xFF3D2A00)
val RoomDangerFill = Color(0xFFD32F2F)
val RoomOnDangerFill = Color(0xFFFFFFFF)
// 会中消息里首字母头像的渐变底(蓝→青)。纯装饰,不承载语义。
val RoomAvatarGradientStart = Color(0xFF4C7CF3)
val RoomAvatarGradientEnd = Color(0xFF3FC6B1)
