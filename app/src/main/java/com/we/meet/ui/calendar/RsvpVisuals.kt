package com.we.meet.ui.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.we.meet.ui.theme.WeMeetTheme

/**
 * 日程「我的表态」的视觉档位 —— 四态四色、一律实线,与 Web 日历同一组色值
 * (见 calendarGridOverrides.css 末尾的表态区):
 * - [ACCEPTED] 接受 = 蓝
 * - [NEEDS_ACTION] 未反馈 = 紫
 * - [TENTATIVE] 待定 = 琥珀
 * - [DECLINED] 拒绝 = 灰,额外加标题删除线(退到背景里但仍占位)
 *
 * 日/周视图的竖条、月/日程卡片的左色条共用这一组,四个视图一套语言。
 */
enum class RsvpVisual { ACCEPTED, NEEDS_ACTION, TENTATIVE, DECLINED }

/** `my_rsvp` → 视觉档位。null(历史数据里组织者没有 attendee 行)按接受处理。 */
fun rsvpVisualOf(myRsvp: String?): RsvpVisual = when (myRsvp) {
    "declined" -> RsvpVisual.DECLINED
    "tentative" -> RsvpVisual.TENTATIVE
    "needs_action" -> RsvpVisual.NEEDS_ACTION
    else -> RsvpVisual.ACCEPTED
}

/** 档位对应的强调色(竖条/色条)。 */
@Composable
@ReadOnlyComposable
fun rsvpAccentColor(visual: RsvpVisual): Color = with(WeMeetTheme.extras.rsvp) {
    when (visual) {
        RsvpVisual.ACCEPTED -> accepted
        RsvpVisual.NEEDS_ACTION -> needsAction
        RsvpVisual.TENTATIVE -> tentative
        RsvpVisual.DECLINED -> declined
    }
}

/** 档位对应的块内文字色(同色系深档,对齐 Web)。 */
@Composable
@ReadOnlyComposable
fun rsvpTextColor(visual: RsvpVisual): Color = with(WeMeetTheme.extras.rsvp) {
    when (visual) {
        RsvpVisual.ACCEPTED -> acceptedText
        RsvpVisual.NEEDS_ACTION -> needsActionText
        RsvpVisual.TENTATIVE -> tentativeText
        RsvpVisual.DECLINED -> declinedText
    }
}

/**
 * 时间轴块的底色 = 强调色低透明(浅色 .12 / 深色 .24,与 Web 同口径)。
 * 拒绝档不跟色系走,压成中性灰底。
 */
@Composable
@ReadOnlyComposable
fun rsvpBlockBackground(visual: RsvpVisual): Color {
    val accent = rsvpAccentColor(visual)
    if (visual == RsvpVisual.DECLINED) return accent.copy(alpha = 0.16f)
    return accent.copy(alpha = if (WeMeetTheme.isDark) 0.24f else 0.14f)
}
