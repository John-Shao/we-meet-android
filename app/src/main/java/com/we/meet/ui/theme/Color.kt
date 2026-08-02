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
