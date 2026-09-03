package com.we.meet.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

internal val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    inversePrimary = LightInversePrimary,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = LightPrimary,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    error = LightDanger,
    onError = LightOnDanger,
    errorContainer = LightDangerContainer,
    onErrorContainer = LightOnDangerContainer,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = ThemeScrim,
    surfaceBright = LightSurfaceBright,
    surfaceDim = LightSurfaceDim,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainerLowest = LightSurfaceContainerLowest,
)

internal val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    inversePrimary = DarkInversePrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = DarkPrimary,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    error = DarkDanger,
    onError = DarkOnDanger,
    errorContainer = DarkDangerContainer,
    onErrorContainer = DarkOnDangerContainer,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = ThemeScrim,
    surfaceBright = DarkSurfaceBright,
    surfaceDim = DarkSurfaceDim,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
)

/**
 * Semantic color tokens that sit outside Material 3's [ColorScheme].
 *
 * Add new tokens here (and to Color.kt as `Light…` / `Dark…` pairs) rather
 * than branching on [isSystemInDarkTheme] at call sites. Consumers read
 * them via [WeMeetTheme.extras].
 */
data class WeMeetExtras(
    /** Thin tinted band used to separate zones on the Home page. */
    val surfaceBand: Color,
    /** 日历表态四态四色(见 [RsvpColors])。 */
    val rsvp: RsvpColors,
    /** 日历网格的其余专用色(见 [CalendarColors])。表态色在 [rsvp]。 */
    val calendar: CalendarColors,
    /** 危险/警告/成功/激活四档状态色(见 [StatusColors])。 */
    val status: StatusColors,
    /** 会中界面专用色(见 [RoomColors])。 */
    val room: RoomColors,
    /** AI 通话页控件专用色(见 [AiCallColors])。 */
    val aiCall: AiCallColors,
    /** IM 聊天与一对一通话专用色(见 [ImColors])。 */
    val im: ImColors,
    /**
     * 无头像时按名字哈希取色的调色板。顺序与长度不可变 —— 见 Color.kt 说明。
     */
    val avatarPalette: List<Color>,
)

/**
 * 状态色四档,每档「主色 + 浅底 + 浅底上文字」。
 *
 * 用哪档:
 * - [danger] 破坏性、不可逆:挂断、结束会议、移出成员、删除
 * - [warning] 需要注意但不阻断:举手、录制中、网络差
 * - [success] 正向确认:正在说话、已连接、已保存
 * - [accentActive] 工具处于开启态:字幕开、共享中(非品牌主色,专表「激活」)
 *
 * 别拿 [danger] 当「红色」用 —— 它表示的是「这一步会造成损失」,不是配色。
 */
data class StatusColors(
    val danger: Color,
    /** 压在 [danger] 实底上的文字/图标色。已按 WCAG AA 校过对比度。 */
    val onDanger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val accentActive: Color,
    val accentActiveContainer: Color,
    val onAccentActiveContainer: Color,
    /**
     * 「没有强调」那一档。**不要用 `colorScheme.surfaceVariant` 代替** ——
     * 那个值 M3 从 primary 派生,在本 App 里带紫调,会让 neutral 看起来像
     * 一种颜色而不是「无色」,而且跟普通消息气泡撞色。
     */
    val neutralContainer: Color,
    val onNeutralContainer: Color,
)

/**
 * 会中界面专用色。深浅色两套取值相同 —— 会中永远深色,见 Color.kt 的说明。
 */
data class RoomColors(
    /**
     * 画面块底色(有无视频流都用它)。
     *
     * 固定为腾讯会议那种深灰,而**不是** `colorScheme.surfaceVariant` ——
     * 后者在浅色主题下接近白色,压在视频通话背后很怪,也会让无摄像头时
     * 那个白色 Person 图标糊成一片。
     */
    val tileBackground: Color,
    /** 正在说话的画面块描边(绿)。 */
    val speakingRing: Color,
    /** 压在视频上的控件底:半透明黑,保证白字可读。 */
    val overlayScrim: Color,
    /** 实时字幕文字色。 */
    val subtitleText: Color,
    /** 画面块上无摄像头时的圆形头像底色。 */
    val avatarFallback: Color,
    /** 会中消息里首字母头像的渐变底(蓝→青),纯装饰。 */
    val avatarGradient: List<Color>,
    /**
     * 深色浮层上的危险前景色。
     *
     * 和 [StatusColors.danger] 的区别在于「底是什么」:会中工具条永远是深色的,
     * 而浅色主题的 danger 是深红,压在深底上根本看不清。凡是画在视频浮层上的
     * 危险色用这个,画在 sheet/dialog 等跟随主题的面上用 [StatusColors.danger]。
     */
    val dangerOnOverlay: Color,
    /** 深色浮层上的强调文字。 */
    val overlayAccentText: Color,
    /** 实底横幅/按钮:等候区(琥珀)与录制中/挂断(红),各配前景色。 */
    val warningFill: Color,
    val onWarningFill: Color,
    val dangerFill: Color,
    val onDangerFill: Color,
)

/**
 * IM 专用色。分四块:一对一通话控件、连接状态条四态、「@我」高亮、群头像调色板。
 *
 * [callHangUp] / [callAccept] 是全 App 通话红绿的**唯一**来源,AI 通话页
 * ([AiCallColors]) 取的也是这两个值 —— 同一个动作不该有两种颜色。
 */
data class ImColors(
    val callHangUp: Color,
    val callAccept: Color,
    val callNeutralControl: Color,
    val callMutedControl: Color,
    val videoStageBackground: Color,
    val videoStageLabel: Color,
    /** 连接状态条:一档一对「底色 + 文字色」。 */
    val connConnectedBg: Color,
    val connConnectedFg: Color,
    val connConnectingBg: Color,
    val connConnectingFg: Color,
    val connFailedBg: Color,
    val connFailedFg: Color,
    val connOfflineBg: Color,
    val connOfflineFg: Color,
    /** 「@我」提及的高亮。 */
    val mentionSelfBg: Color,
    val mentionSelfFg: Color,
    /** 群头像无图时的底色池,按群名 hash 取。 */
    val groupAvatarPalette: List<Color>,
    /** 群机器人预设头像底色;存的是下标,顺序不可改。 */
    val botAvatarPalette: List<Color>,
)

private val SharedImColors = ImColors(
    callHangUp = ImCallHangUp,
    callAccept = ImCallAccept,
    callNeutralControl = ImCallNeutralControl,
    callMutedControl = ImCallMutedControl,
    videoStageBackground = ImVideoStageBackground,
    videoStageLabel = ImVideoStageLabel,
    connConnectedBg = ImConnConnectedBg,
    connConnectedFg = ImConnConnectedFg,
    connConnectingBg = ImConnConnectingBg,
    connConnectingFg = ImConnConnectingFg,
    connFailedBg = ImConnFailedBg,
    connFailedFg = ImConnFailedFg,
    connOfflineBg = ImConnOfflineBg,
    connOfflineFg = ImConnOfflineFg,
    mentionSelfBg = ImMentionSelfBg,
    mentionSelfFg = ImMentionSelfFg,
    groupAvatarPalette = GroupAvatarPalette,
    botAvatarPalette = BotAvatarPalette,
)

/**
 * AI 通话页里麦克风 / 视频切换这类**中性**控件的一组配色:未选中与选中各
 * 一对「圆底 + 图标」。取值与对比度见 Color.kt 的 [LightAiCallControlSurface]。
 */
data class AiCallControlColors(
    val surface: Color,
    val onSurface: Color,
    /** 选中态(视频已开)。规则是「比未选中更亮」,深浅两套方向一致。 */
    val selected: Color,
    val onSelected: Color,
)

/**
 * AI 通话页控件色。
 *
 * [control] 跟随主题;[controlOnDark] 是视频开启、控件压在摄像头画面上时用的
 * 那套 —— 恒深色,浅色主题下也一样。两个字段并存是因为组件要在同一次组合里
 * 按 `onDark` 二选一,没法只拿到当前主题的那套。
 *
 * [hangUp] / [startCall] 不是这一屏独有的值,而是复用 [ImColors] 那套通话
 * 红绿。曾经两屏各一套(AI `E0524C`/`1FB85F`,IM `E5484D`/`30A46C`),其中
 * AI 的绿配白图标只有 2.60:1,过不了 WCAG 1.4.11 的 3:1 —— 并过来同时修掉。
 * 它们是实底圆(白图标压在红/绿上),深浅色一致,所以不在 [control] 里。
 */
data class AiCallColors(
    val control: AiCallControlColors,
    val controlOnDark: AiCallControlColors,
    /** 挂断按钮底色,以及静音时的图标色。 */
    val hangUp: Color,
    /** 发起通话按钮底色。 */
    val startCall: Color,
    val sphereGlowOuter: Color,
    val sphereGlowInner: Color,
    /** 球体扫描渐变,首尾同色才能接成环。 */
    val sphereGradient: List<Color>,
)

private val LightAiCallControls = AiCallControlColors(
    surface = LightAiCallControlSurface,
    onSurface = LightAiCallOnControlSurface,
    selected = LightAiCallControlSelected,
    onSelected = LightAiCallOnControlSelected,
)

private val DarkAiCallControls = AiCallControlColors(
    surface = DarkAiCallControlSurface,
    onSurface = DarkAiCallOnControlSurface,
    selected = DarkAiCallControlSelected,
    onSelected = DarkAiCallOnControlSelected,
)

private fun aiCallColors(control: AiCallControlColors) = AiCallColors(
    control = control,
    // 压在摄像头画面上时恒用深色那套。
    controlOnDark = DarkAiCallControls,
    // 与 SharedImColors 同源 —— 通话红绿只此一套。
    hangUp = ImCallHangUp,
    startCall = ImCallAccept,
    sphereGlowOuter = AiCallSphereGlowOuter,
    sphereGlowInner = AiCallSphereGlowInner,
    sphereGradient = AiCallSphereGradient,
)

private val SharedRoomColors = RoomColors(
    tileBackground = RoomTileBackground,
    speakingRing = RoomSpeakingRing,
    overlayScrim = RoomOverlayScrim,
    subtitleText = RoomSubtitleText,
    avatarFallback = RoomAvatarFallback,
    avatarGradient = listOf(RoomAvatarGradientStart, RoomAvatarGradientEnd),
    dangerOnOverlay = RoomDangerOnOverlay,
    overlayAccentText = RoomOverlayAccentText,
    warningFill = RoomWarningFill,
    onWarningFill = RoomOnWarningFill,
    dangerFill = RoomDangerFill,
    onDangerFill = RoomOnDangerFill,
)

/**
 * 日历「我的表态」配色:一档一对「强调色 + 文字色」。色值与 Web 的
 * calendarGridOverrides.css 一一对应,改色两端同步。
 */
/**
 * 日历网格专用色。
 *
 * [conflict] 不复用 [StatusColors.danger] —— 它表示「这两个日程撞了」,是信息
 * 标注,不是「点下去会造成损失」。语义不同,不共用 token。
 */
data class CalendarColors(
    /** 工作时间网格的基础 surface。 */
    val gridBackground: Color,
    /** 时间与日期分隔线；仅表达结构，不承担文字语义。 */
    val gridLine: Color,
    /** 工作时间以外的区域底色。 */
    val nonWorkingSurface: Color,
    /** 不可用日期/成员列的底色。 */
    val unavailableSurface: Color,
    /** 不公开标题的忙碌时段中性底色。 */
    val busyContainer: Color,
    /** 已结束、已取消日程的退后层级底色。 */
    val pastEventContainer: Color,
    /** RSVP glyph 的承载底，隔离用户日历色。 */
    val statusBadgeContainer: Color,
    /** 日程标题前景。 */
    val eventContent: Color,
    /** 日程时间、组织者等辅助前景。 */
    val eventSupportingContent: Color,
    /** 新建、拖动和忙闲选择框的品牌浅底及其前景。 */
    val selectionContainer: Color,
    val onSelectionContainer: Color,
    /** 时间冲突选择框的浅底及其前景。 */
    val conflictContainer: Color,
    val onConflictContainer: Color,
    /** 「当前时间」横线。 */
    val nowLine: Color,
    /** 时间冲突标记(忙闲对比的红点、网格里的冲突选区)。 */
    val conflict: Color,
    /** 月网格里非本月那几天的日期数字。见 Color.kt:不是装饰,别弱化到读不出。 */
    val outOfMonthDay: Color,
    /** 提醒条目的强调橙。 */
    val reminder: Color,
    /** 压在 [reminder] **实底**上的前景(图标)。深浅同值,底本来就不翻转。 */
    val onReminder: Color,
    /**
     * 压在 [reminder] **淡底**(14% 左右)上的文字。浅色下必须比 [reminder]
     * 深一大截才够 4.5:1 —— 文字和底同色是这里踩过的坑,数字见 Color.kt。
     */
    val reminderText: Color,
)

data class RsvpColors(
    val accepted: Color,
    val acceptedText: Color,
    val needsAction: Color,
    val needsActionText: Color,
    val tentative: Color,
    val tentativeText: Color,
    val declined: Color,
    val declinedText: Color,
)

private val LightExtras = WeMeetExtras(
    surfaceBand = LightSurfaceBand,
    rsvp = RsvpColors(
        accepted = LightRsvpAccepted,
        acceptedText = LightRsvpAcceptedText,
        needsAction = LightRsvpNeeds,
        needsActionText = LightRsvpNeedsText,
        tentative = LightRsvpTentative,
        tentativeText = LightRsvpTentativeText,
        declined = LightRsvpDeclined,
        declinedText = LightRsvpDeclinedText,
    ),
    calendar = CalendarColors(
        gridBackground = LightCalendarGridBackground,
        gridLine = LightCalendarGridLine,
        nonWorkingSurface = LightCalendarNonWorkingSurface,
        unavailableSurface = LightCalendarUnavailableSurface,
        busyContainer = LightCalendarBusyContainer,
        pastEventContainer = LightCalendarPastEventContainer,
        statusBadgeContainer = LightCalendarStatusBadgeContainer,
        eventContent = LightCalendarEventContent,
        eventSupportingContent = LightCalendarEventSupportingContent,
        selectionContainer = LightPrimaryContainer,
        onSelectionContainer = LightOnPrimaryContainer,
        conflictContainer = LightDangerContainer,
        onConflictContainer = LightOnDangerContainer,
        outOfMonthDay = LightCalendarOutOfMonthDay,
        nowLine = LightCalendarNowLine,
        conflict = LightCalendarConflict,
        reminder = LightCalendarReminder,
        onReminder = CalendarOnReminder,
        reminderText = LightCalendarReminderText,
    ),
    status = StatusColors(
        danger = LightDanger,
        onDanger = LightOnDanger,
        dangerContainer = LightDangerContainer,
        onDangerContainer = LightOnDangerContainer,
        warning = LightWarning,
        warningContainer = LightWarningContainer,
        onWarningContainer = LightOnWarningContainer,
        success = LightSuccess,
        successContainer = LightSuccessContainer,
        onSuccessContainer = LightOnSuccessContainer,
        accentActive = LightAccentActive,
        accentActiveContainer = LightAccentActiveContainer,
        neutralContainer = LightNeutralContainer,
        onNeutralContainer = LightOnNeutralContainer,
        onAccentActiveContainer = LightOnAccentActiveContainer,
    ),
    room = SharedRoomColors,
    aiCall = aiCallColors(LightAiCallControls),
    im = SharedImColors,
    avatarPalette = AvatarFallbackPalette,
)

private val DarkExtras = WeMeetExtras(
    surfaceBand = DarkSurfaceBand,
    rsvp = RsvpColors(
        accepted = DarkRsvpAccepted,
        acceptedText = DarkRsvpAcceptedText,
        needsAction = DarkRsvpNeeds,
        needsActionText = DarkRsvpNeedsText,
        tentative = DarkRsvpTentative,
        tentativeText = DarkRsvpTentativeText,
        declined = DarkRsvpDeclined,
        declinedText = DarkRsvpDeclinedText,
    ),
    calendar = CalendarColors(
        gridBackground = DarkCalendarGridBackground,
        gridLine = DarkCalendarGridLine,
        nonWorkingSurface = DarkCalendarNonWorkingSurface,
        unavailableSurface = DarkCalendarUnavailableSurface,
        busyContainer = DarkCalendarBusyContainer,
        pastEventContainer = DarkCalendarPastEventContainer,
        statusBadgeContainer = DarkCalendarStatusBadgeContainer,
        eventContent = DarkCalendarEventContent,
        eventSupportingContent = DarkCalendarEventSupportingContent,
        selectionContainer = DarkPrimaryContainer,
        onSelectionContainer = DarkOnPrimaryContainer,
        conflictContainer = DarkDangerContainer,
        onConflictContainer = DarkOnDangerContainer,
        outOfMonthDay = DarkCalendarOutOfMonthDay,
        nowLine = DarkCalendarNowLine,
        conflict = DarkCalendarConflict,
        reminder = DarkCalendarReminder,
        // 深色下角标底被页面底压暗了,橙字压上去有 7.39:1,不需要单独取值。
        onReminder = CalendarOnReminder,
        reminderText = DarkCalendarReminder,
    ),
    status = StatusColors(
        danger = DarkDanger,
        onDanger = DarkOnDanger,
        dangerContainer = DarkDangerContainer,
        onDangerContainer = DarkOnDangerContainer,
        warning = DarkWarning,
        warningContainer = DarkWarningContainer,
        onWarningContainer = DarkOnWarningContainer,
        success = DarkSuccess,
        successContainer = DarkSuccessContainer,
        onSuccessContainer = DarkOnSuccessContainer,
        accentActive = DarkAccentActive,
        accentActiveContainer = DarkAccentActiveContainer,
        neutralContainer = DarkNeutralContainer,
        onNeutralContainer = DarkOnNeutralContainer,
        onAccentActiveContainer = DarkOnAccentActiveContainer,
    ),
    room = SharedRoomColors,
    aiCall = aiCallColors(DarkAiCallControls),
    im = SharedImColors,
    avatarPalette = AvatarFallbackPalette,
)

private val LocalWeMeetExtras = staticCompositionLocalOf { LightExtras }

/**
 * Whether the app is painting its dark palette (mirrors [WeMeetTheme]'s
 * `darkTheme`). Lets non-color consumers — e.g. the docs WebView, which must
 * tell embedded docs which scheme to render — read the active mode without
 * re-deriving it from settings. Read via [WeMeetTheme.isDark].
 */
private val LocalWeMeetIsDark = staticCompositionLocalOf { false }

/** Accessor mirroring `MaterialTheme.colorScheme` for our extras. */
object WeMeetTheme {
    val extras: WeMeetExtras
        @Composable
        @ReadOnlyComposable
        get() = LocalWeMeetExtras.current

    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalWeMeetIsDark.current
}

@Composable
fun WeMeetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }
    CompositionLocalProvider(
        LocalWeMeetExtras provides if (darkTheme) DarkExtras else LightExtras,
        LocalWeMeetIsDark provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = JusiTypography,
            shapes = JusiShapes,
            content = content,
        )
    }
}
