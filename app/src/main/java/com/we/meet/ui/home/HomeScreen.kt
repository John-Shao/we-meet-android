package com.we.meet.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.we.meet.ui.theme.WeMeetTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.WeMeetApp
import com.we.meet.R

@Composable
fun HomeScreen(
    onCreateMeeting: () -> Unit,
    onJoinMeeting: () -> Unit,
    onHistoryClick: (roomId: String) -> Unit,
    /** P8:预约会议行 → 预约详情页(进会/复制/删除收进详情)。 */
    onScheduledClick: (slug: String, name: String, scheduledAtIso: String) -> Unit,
    /** 预约会议关联了日程 → 走统一的日程详情(一场会一个详情页)。 */
    onScheduledEventClick: (eventId: String) -> Unit,
    /** 预约会议 = 创建日程(对标飞书):打开日历的创建日程界面。 */
    onScheduleMeeting: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory(app))
    val history by homeViewModel.history.collectAsStateWithLifecycle()
    val scheduledMeetings by homeViewModel.scheduledMeetings.collectAsStateWithLifecycle()

    // Refresh the server-side rooms list whenever Home becomes visible
    // again — covers returning from a meeting, the room-end flow, or a
    // create-on-another-device case (the user opened the App expecting
    // to see a room their Web session just made).
    LifecycleResumeEffect(homeViewModel) {
        homeViewModel.refreshRemoteRooms()
        onPauseOrDispose { }
    }

    // Header (top bar + action zone + band) stays pinned; only the
    // meeting lists below scroll when the user swipes up. Same Feishu /
    // WeChat-style "fixed action shelf + scrolling timeline" layout.
    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar: tab title on the left, meeting-settings gear on the right.
        // (Scan-QR lives in the 消息 header's "more" menu; profile/app settings
        // stay behind the 消息 avatar — only meeting-scoped settings are here.)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.tab_meeting),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.meeting_settings_title),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Action zone — padded, same background as the page.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ActionCard(
                icon = Icons.Default.Bolt,
                label = stringResource(R.string.home_create_meeting),
                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = onCreateMeeting,
                modifier = Modifier.weight(1f),
            )
            ActionCard(
                icon = Icons.Default.AddBox,
                label = stringResource(R.string.home_join_meeting),
                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = onJoinMeeting,
                modifier = Modifier.weight(1f),
            )
            ActionCard(
                icon = Icons.Default.Schedule,
                label = stringResource(R.string.home_create_later_meeting),
                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                // 预约会议 = 创建日程:打开日历的创建日程界面(替代旧的轻量弹窗)。
                onClick = onScheduleMeeting,
                modifier = Modifier.weight(1f),
            )
        }

        // Full-width tinted band separating the action zone from the
        // history list — mirrors the Feishu home layout.
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(WeMeetTheme.extras.surfaceBand),
        )

        // Scheduled + History zones — padded inside one column. This is
        // the only scrollable region; the action shelf above stays put.
        // Scheduled list renders nothing when empty, so on a fresh
        // install the history section still sits flush with the band.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            // P8(对标飞书):行点击进详情,操作(进会/复制/删除)收进详情页。
            ScheduledMeetingsList(
                rooms = scheduledMeetings,
                onEntryClick = { room ->
                    // 一场会一个详情页:有日程的走统一的日程详情(带参与人/
                    // RSVP/纪要);无日程的(快速会议、存量裸预约)才进会议详情。
                    val eventId = room.event_id
                    if (!eventId.isNullOrBlank()) {
                        onScheduledEventClick(eventId)
                    } else {
                        onScheduledClick(
                            room.slug.orEmpty(),
                            room.name.orEmpty(),
                            room.scheduled_at.orEmpty(),
                        )
                    }
                },
            )
            HistoryList(
                entries = history,
                // P8 实测修正:统一点击进详情页。此前按 closed_at 分流
                // (进行中→重进会议),但大量房间从未显式结束、closed_at
                // 恒空,同一列表头尾行为不一致;重进会议在详情页一键可达。
                onEntryClick = { entry -> onHistoryClick(entry.roomId) },
            )
        }
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    label: String,
    backgroundColor: Color,
    iconTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(backgroundColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}
