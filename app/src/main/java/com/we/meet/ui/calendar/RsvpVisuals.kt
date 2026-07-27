package com.we.meet.ui.calendar

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.we.meet.ui.theme.WeMeetTheme

/**
 * 日程「我的表态」的视觉档位(对齐 Web 日历):
 * - [ACCEPTED] 接受 —— 主色实心竖条/色条(默认档);
 * - [TENTATIVE] 待定 / 未反馈 —— 琥珀;日/周视图的竖条画成斜纹(对齐 Web),
 *   月/日程卡片用色条,「还没定」一眼可辨;
 * - [DECLINED] 拒绝 —— 灰 + 标题删除线,退到背景里但仍占位。
 *
 * 未反馈刻意与待定同档(与 Web 一致):两者都是「还没定下来」,拆成两色只会
 * 让四个视图多一档需要记忆的颜色。
 */
enum class RsvpVisual { ACCEPTED, TENTATIVE, DECLINED }

/** `my_rsvp` → 视觉档位。null(历史数据里组织者没有 attendee 行)按接受处理。 */
fun rsvpVisualOf(myRsvp: String?): RsvpVisual = when (myRsvp) {
    "declined" -> RsvpVisual.DECLINED
    "tentative", "needs_action" -> RsvpVisual.TENTATIVE
    else -> RsvpVisual.ACCEPTED
}

/** 档位对应的强调色(竖条/色条)。 */
@Composable
@ReadOnlyComposable
fun rsvpAccentColor(visual: RsvpVisual): Color = when (visual) {
    RsvpVisual.ACCEPTED -> MaterialTheme.colorScheme.primary
    RsvpVisual.TENTATIVE -> WeMeetTheme.extras.rsvpTentative
    RsvpVisual.DECLINED -> WeMeetTheme.extras.rsvpDeclined
}
