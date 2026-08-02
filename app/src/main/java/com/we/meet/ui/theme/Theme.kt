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

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    background = LightBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    background = DarkBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
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
    /** 危险/警告/成功/激活四档状态色(见 [StatusColors])。 */
    val status: StatusColors,
    /** 会中界面专用色(见 [RoomColors])。 */
    val room: RoomColors,
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
        onAccentActiveContainer = LightOnAccentActiveContainer,
    ),
    room = SharedRoomColors,
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
        onAccentActiveContainer = DarkOnAccentActiveContainer,
    ),
    room = SharedRoomColors,
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
            content = content,
        )
    }
}
