package com.we.meet.ui.theme

import androidx.compose.ui.graphics.Color

// Brand seed and a small handful of derived tones.  Material 3 will fill in
// the rest of the palette via lightColorScheme/darkColorScheme.
val Seed = Color(0xFF1F6FEB)

val LightPrimary = Seed
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFD6E4FF)

/**
 * 比 [Seed] 深一档的蓝,**不要**图省事写回 `Seed`。
 *
 * 这个 token 同时被图标和正文用(共 11 处:7 处图标着色、4 处文字),
 * 而两者门槛不同 —— WCAG 2.2 AA 的 SC 1.4.11 对图标要 3:1、SC 1.4.3 对
 * 正文要 4.5:1。`Seed` 压在 [LightPrimaryContainer] 上只有 3.62:1,正好
 * 卡在中间:图标过,文字不过。不过的那 4 处是月视图「今天」那格的日期
 * 数字、日视图全天条标题(12sp,更吃亏)、审批「已通过」标签、会中 AI
 * 气泡正文。
 *
 * `1A5FCC` 压在同一个底上 4.62:1,过线;图标那 7 处本来就过,只会更好。
 * 深色那套没这个问题(`D6E4FF` on `1E3A7A` = 8.45:1)。
 *
 * ⚠️ 改这个值或 [LightPrimaryContainer] 之前先算对比度,4.5:1 是底线。
 */
val LightOnPrimaryContainer = Color(0xFF1A5FCC)
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
/**
 * 月网格里**非本月**那几天的日期数字。
 *
 * 原先直接用 `colorScheme.outlineVariant`,压在页面底上只有 1.66:1(浅)/
 * 1.98:1(深)——「弱化」过头成了「几乎看不见」。日期是有信息的文字,不是
 * 装饰:用户会拿它对齐周次、点上月末那几天。
 *
 * 非本月日期仍然可以点击，也承担跨月对齐的信息，因此按普通文字的 4.5:1
 * 门槛处理，不能用“辅助信息”规避 WCAG。现在两套分别为 4.72:1 / 5.46:1；
 * 视觉层级靠与主文字的亮度差保留，不靠把文字压到不可读。
 */
val LightCalendarOutOfMonthDay = Color(0xFF766F7D)
val DarkCalendarOutOfMonthDay = Color(0xFF8F8999)

val LightCalendarNowLine = Color(0xFFEF4444)
val LightCalendarConflict = Color(0xFFDC2626)
val LightCalendarReminder = Color(0xFFFF8800)

/**
 * 压在提醒橙**实底**上的前景(消息列表提醒行的日历图标)。
 *
 * 深浅两套同一个值,是因为 [LightCalendarReminder] / [DarkCalendarReminder]
 * 本身在两套主题里都是亮橙 —— 底不翻转,前景也就不该翻转。
 *
 * 原先这里写死 `Color.White`,压在橙上只有 2.39:1(浅)/ 1.95:1(深),
 * 两套都过不了 SC 1.4.11 的 3:1,深色更差。换成深色后 7.14:1 / 8.77:1。
 * 亮底配白字是最容易凭感觉写错的一种 —— 橙看着"挺深",算出来不是。
 */
val CalendarOnReminder = Color(0xFF1A1C1E)

/**
 * 提醒页「现在 / N 分钟后」角标的**文字**色 —— 它压在 14% 提醒橙的淡底上,
 * 不是压在实底上,所以不能直接用 [LightCalendarReminder] 本身。
 *
 * 原先文字和底同色(都用 reminder),浅色下算出来 2.07:1,远低于正文 4.5:1;
 * 深色下同样写法反而有 7.39:1(底被页面底压暗了),所以只有浅色需要单独取值。
 * `9A5200` 压在浅色实际底 `FCECDA` 上 5.07:1。
 */
val LightCalendarReminderText = Color(0xFF9A5200)

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
// neutral 是「没有强调」那一档,**必须是真中性灰**。曾经直接用
// `colorScheme.surfaceVariant`,但那个值 M3 是从 primary 派生的 —— 在本 App
// 里带明显紫调,于是「无强调」的卡片头看起来像有强调,而且跟普通消息气泡同色。
// 对比度 7.9:1,远超 WCAG AA。
val LightNeutralContainer = Color(0xFFEDEDED)
val LightOnNeutralContainer = Color(0xFF424242)

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
// 深色下的中性档。亮度与其余几个 *Container 同一档,免得 neutral 卡片在深色
// 里显得比 danger/warning 更「重」。对比度 7.4:1。
val DarkNeutralContainer = Color(0xFF3A3F44)
val DarkOnNeutralContainer = Color(0xFFD5D8DC)

/**
 * IM(聊天与一对一通话)专用色。
 *
 * 通话的红/绿是**全 App 唯一一套**,AI 通话页也用这两个值(见 Theme.kt 的
 * [AiCallColors])。曾经两边各有一套:AI 用 `E0524C`/`1FB85F`,IM 用现在
 * 这两个 —— 同一个动作两种红绿。收进 token 后暴露出来,并入这一套。
 *
 * 选 IM 这套不是因为它先来,是因为白图标压在它上面才够对比度
 * (WCAG 2.2 AA 的 SC 1.4.11 非文本对比,门槛 3:1):
 *
 * | | 白图标对比度 | |
 * |---|---|---|
 * | 挂断 `E5484D` | 3.91:1 | ✅ |
 * | 挂断 `E0524C`(原 AI) | 3.83:1 | ✅ |
 * | 接听 `30A46C` | 3.16:1 | ✅ |
 * | 发起 `1FB85F`(原 AI) | **2.60:1** | ❌ |
 *
 * ⚠️ 接听绿的 3.16:1 离 3:1 门槛只差一点,**调它之前先算对比度**。要更多
 * 余量可换 `218A56`(4.34:1,肉眼只深一档)—— 现在只有这一处定义,改一行即可。
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

/**
 * 群机器人预设头像的 8 档底色。
 *
 * ⚠️ 存进后端的是**下标**,所以改顺序等于把所有已存在机器人的头像换色。
 * 三端各存一份同样的值:后端 `core/services/im_bots.BOT_AVATAR_PALETTE`、
 * Web `features/im/components/bots/botAvatar.ts` 的 `BOT_COLORS`。
 * 改色必须三端同步。
 *
 * 与 [GroupAvatarPalette] 分开:机器人的底色是创建者**明确挑的**,不是按名字
 * 哈希兜底的 —— 挑了蓝色渲染成绿色是 bug,不是降级。深浅共用一套。
 */
val BotAvatarPalette = listOf(
    Color(0xFF3370FF), Color(0xFF0891B2), Color(0xFF16A34A), Color(0xFF65A30D),
    Color(0xFFD97706), Color(0xFFEA580C), Color(0xFF7C3AED), Color(0xFFDB2777),
)

/** 「@我」提及在消息正文里的高亮底与文字 —— 让人一眼扫到自己被点名。 */
val ImMentionSelfBg = Color(0xFFFDE68A)
val ImMentionSelfFg = Color(0xFF92400E)

/**
 * AI 通话页中性控件(麦克风、视频切换)的圆底与图标色,深浅各一套。
 *
 * 原先只有固定浅色一套(`EDEDED` + 黑图标)。深色模式逐屏核对时,这排控件
 * 在近黑的页面底上连成一条亮带,像块贴上去的浅色岛。参考通话类 App 的通行
 * 做法改成跟随主题:深色下取比页面底高一档的深灰圆 + 浅色图标,圆本身退回
 * 页面里,亮的只有图标。
 *
 * 视频开启时控件压在摄像头画面上,那种情况**恒取深色这套**(不看主题)——
 * 判断收在 `AiCallColors.controlOnDark`,和顶栏 / 状态条的 `onDark` 同一套
 * 逻辑。
 *
 * 选中态(视频已开)沿用「比未选中更亮」这条规则,深浅两套方向一致。
 *
 * 对比度(SC 1.4.11 门槛 3:1,图标是识别控件的那个元素):
 *
 * | | 图标 / 圆底 | |
 * |---|---|---|
 * | 浅色 `1A1C1E` on `EDEDED` | 15.2:1 | ✅ |
 * | 深色 `E6E8EB` on `2A2E33` | 11.1:1 | ✅ |
 * | 静音红 `E5484D` on `EDEDED` | 3.34:1 | ✅ |
 * | 静音红 `E5484D` on `2A2E33` | 3.49:1 | ✅ |
 *
 * 圆底相对页面底只有 1.35:1(深色),是有意的 —— 它是装饰,承担识别的是
 * 图标。改圆底颜色前先确认上表里的图标那几行还成立。
 */
val LightAiCallControlSurface = Color(0xFFEDEDED)
val LightAiCallOnControlSurface = Color(0xFF1A1C1E)
val LightAiCallControlSelected = Color(0xFFFFFFFF)
val LightAiCallOnControlSelected = Color(0xFF1A1C1E)
val DarkAiCallControlSurface = Color(0xFF2A2E33)
val DarkAiCallOnControlSurface = Color(0xFFE6E8EB)
val DarkAiCallControlSelected = Color(0xFFE6E8EB)
val DarkAiCallOnControlSelected = Color(0xFF111418)
// 挂断/发起两色不在这里定义 —— 通话红绿全 App 共用 [ImCallHangUp] /
// [ImCallAccept] 那一套,理由和对比度数字见那里。它们是实底圆(白图标压在
// 红/绿上),深浅色一致,不属于上面这套「跟随主题的中性控件」。

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
