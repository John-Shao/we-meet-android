package com.we.meet.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.ui.list.ConversationListScreen
import com.we.meet.ui.calendar.CalendarTabScreen
import com.we.meet.ui.contacts.ContactsTabScreen
import com.we.meet.ui.home.HomeScreen
import com.we.meet.ui.profile.ProfileScreen

/** Bottom tabs, in bar order. Feishu-style: 消息 · 日历 · 会议 · 通讯录 · 我的. */
enum class MainTab { Messages, Calendar, Meeting, Contacts, Profile }

private data class TabItem(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val badgeCount: Long = 0,
    val content: @Composable () -> Unit,
)

@Composable
fun MainTabScreen(
    onCreateMeeting: () -> Unit,
    onJoinMeeting: () -> Unit,
    onJoinSlug: (slug: String) -> Unit,
    onScanQrCode: () -> Unit,
    onHistoryClick: (roomId: String) -> Unit,
    onSettingsClick: () -> Unit,
    onOpenAiHub: () -> Unit,
    onOpenApproval: () -> Unit,
    onSignedOut: () -> Unit,
    onOpenChat: (cid: String) -> Unit,
    onNewChat: () -> Unit,
    onMemberClick: (userId: String) -> Unit,
    onEventClick: (eventId: String) -> Unit,
    onCreateEvent: (epochDay: Long) -> Unit,
) {
    // Default to the Messages tab.
    var selectedTab by rememberSaveable { mutableIntStateOf(MainTab.Messages.ordinal) }
    val app = LocalContext.current.applicationContext as WeMeetApp

    // Live unread total for the 消息 tab badge — fed by the process-wide IM
    // session so it counts even while another tab is selected. remember-gated so
    // a recomposition after sign-out (which calls ImSession.shutdown()) can't
    // resurrect a fresh session/socket for the user just logged out.
    val imSession = remember { ImSession.get(app) }
    val imUnread by imSession.totalUnread.collectAsStateWithLifecycle()

    // Single source of truth: each tab pairs its bar appearance with its content, so
    // adding/reordering a tab is one edit and the bar can't drift out of sync with
    // the screen shown.
    val tabs = listOf(
        TabItem(
            R.string.tab_messages,
            Icons.Filled.ChatBubble,
            Icons.Outlined.ChatBubbleOutline,
            badgeCount = imUnread,
        ) {
            ConversationListScreen(
                deps = app,
                onOpenChat = onOpenChat,
                onNewChat = onNewChat,
            )
        },
        TabItem(R.string.tab_calendar, Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth) {
            CalendarTabScreen(
                onEventClick = onEventClick,
                onCreateEvent = onCreateEvent,
            )
        },
        TabItem(R.string.tab_meeting, Icons.Filled.Videocam, Icons.Outlined.Videocam) {
            HomeScreen(
                onCreateMeeting = onCreateMeeting,
                onJoinMeeting = onJoinMeeting,
                onJoinSlug = onJoinSlug,
                onScanQrCode = onScanQrCode,
                onHistoryClick = onHistoryClick,
            )
        },
        TabItem(R.string.tab_contacts, Icons.Filled.Contacts, Icons.Outlined.Contacts) {
            ContactsTabScreen(onMemberClick = onMemberClick)
        },
        TabItem(R.string.tab_profile, Icons.Filled.Person, Icons.Outlined.Person) {
            ProfileScreen(
                onSettingsClick = onSettingsClick,
                onOpenAiHub = onOpenAiHub,
                onOpenApproval = onOpenApproval,
                onSignedOut = {
                    // Drop the IM socket + caches so the next login doesn't
                    // inherit this user's session.
                    ImSession.shutdown()
                    onSignedOut()
                },
            )
        },
    )

    Scaffold(
        bottomBar = {
            CompactTabBar(
                tabs = tabs,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            // coerceIn defends against a restored index pointing past the list (e.g.
            // a saved tab count from an older app version).
            tabs[selectedTab.coerceIn(tabs.indices)].content()
        }
    }
}

@Composable
private fun CompactTabBar(
    tabs: List<TabItem>,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = selectedTab == index
                val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { onTabSelected(index) },
                ) {
                    Box {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = stringResource(tab.labelRes),
                            tint = color,
                            modifier = Modifier.size(28.dp),
                        )
                        if (tab.badgeCount > 0) {
                            TabBadge(
                                count = tab.badgeCount,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 10.dp, y = (-4).dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(tab.labelRes),
                        fontSize = 10.sp,
                        color = color,
                        lineHeight = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun TabBadge(count: Long, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.error, CircleShape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = MaterialTheme.colorScheme.onError,
            fontSize = 9.sp,
            lineHeight = 12.sp,
        )
    }
}
