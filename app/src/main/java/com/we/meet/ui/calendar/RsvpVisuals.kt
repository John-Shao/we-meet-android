package com.we.meet.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.we.meet.R
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTextStyles
import com.we.meet.ui.theme.WeMeetTheme

/**
 * 日程「我的表态」视觉档位。回复状态只控制带文字提示的图形徽标:
 * 接受 ✓、未回复 …、待定 ?、拒绝 ×。日程块的底色和左色条专门表示日历归属,
 * 两种信息不再争用同一个色相;拒绝额外保留标题删除线。
 */
enum class RsvpVisual { ACCEPTED, NEEDS_ACTION, TENTATIVE, DECLINED }

/** `my_rsvp` → 视觉档位。null(历史数据里组织者没有 attendee 行)按接受处理。 */
fun rsvpVisualOf(myRsvp: String?): RsvpVisual = when (myRsvp) {
    "declined" -> RsvpVisual.DECLINED
    "tentative" -> RsvpVisual.TENTATIVE
    "needs_action" -> RsvpVisual.NEEDS_ACTION
    else -> RsvpVisual.ACCEPTED
}

/** 档位对应的小徽标强调色。 */
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

/** 档位对应的小徽标文字色;拒绝标题也复用灰色。 */
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

@Composable
@ReadOnlyComposable
fun rsvpStatusLabel(visual: RsvpVisual): String = stringResource(
    when (visual) {
        RsvpVisual.ACCEPTED -> R.string.event_rsvp_accept
        RsvpVisual.NEEDS_ACTION -> R.string.freebusy_rsvp_pending
        RsvpVisual.TENTATIVE -> R.string.event_rsvp_tentative
        RsvpVisual.DECLINED -> R.string.event_rsvp_decline
    },
)

/** RSVP 图形徽标;glyph + contentDescription 保证状态不依赖色觉。 */
@Composable
fun RsvpStatusBadge(
    visual: RsvpVisual,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    glyphOnly: Boolean = false,
) {
    val accent = rsvpAccentColor(visual)
    val label = rsvpStatusLabel(visual)
    val glyph = when (visual) {
        RsvpVisual.ACCEPTED -> "✓"
        RsvpVisual.NEEDS_ACTION -> "…"
        RsvpVisual.TENTATIVE -> "?"
        RsvpVisual.DECLINED -> "×"
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(
                when {
                    glyphOnly -> Dimens.Calendar.RsvpGlyphSize
                    compact -> Dimens.Calendar.RsvpBadgeCompactSize
                    else -> Dimens.Calendar.RsvpBadgeSize
                },
            )
            .clip(CircleShape)
            .then(
                if (glyphOnly) {
                    Modifier
                } else {
                    Modifier
                        .background(accent.copy(alpha = if (WeMeetTheme.isDark) 0.22f else 0.10f))
                        .border(Dimens.BorderThin, accent.copy(alpha = 0.72f), CircleShape)
                },
            )
            .semantics { contentDescription = label },
    ) {
        Text(
            text = glyph,
            color = rsvpTextColor(visual),
            style = WeMeetTextStyles.LabelMicro,
            maxLines = 1,
        )
    }
}
