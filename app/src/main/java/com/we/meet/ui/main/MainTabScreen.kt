package com.we.meet.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
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
import com.we.meet.ui.calendar.reminder.ReminderEntryRow
import com.we.meet.ui.calendar.reminder.ReminderWindow
import com.we.meet.ui.calendar.reminder.loadReminderWindow
import com.we.meet.ui.calendar.reminder.nearest
import android.view.ViewGroup
import com.we.meet.ui.contacts.ContactsTabScreen
import com.we.meet.ui.docs.DocsTabScreen
import com.we.meet.ui.docs.createDocsWebView
import com.we.meet.ui.theme.WeMeetTheme
import com.we.meet.ui.home.HomeScreen
import com.we.meet.ui.profile.ProfileScreen
import kotlinx.coroutines.launch

/**
 * Bottom tabs, in bar order. Feishu-style: 消息 · 日历 · 会议 · 通讯录.
 *
 * There is deliberately no 我的 tab — the profile page lives behind the avatar
 * in the 消息 header (see the [ModalNavigationDrawer] below), matching Feishu.
 * That means 消息 is the ONLY route to the profile page.
 */
enum class MainTab { Messages, Calendar, Meeting, Contacts, Docs }

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
    /** P8:预约会议行 → 预约详情页。 */
    onScheduledClick: (slug: String, name: String, scheduledAtIso: String) -> Unit,
    onSettingsClick: () -> Unit,
    onOpenMeetingSettings: () -> Unit,
    onOpenAiHub: () -> Unit,
    onOpenApproval: () -> Unit,
    onOpenChat: (cid: String) -> Unit,
    onNewChat: () -> Unit,
    onOpenSearch: () -> Unit,
    onMemberClick: (userId: String) -> Unit,
    onEventClick: (eventId: String) -> Unit,
    onCreateEvent: (epochDay: Long) -> Unit,
    /** P8「在消息列表提醒日程」:置顶入口点开日程提醒页。 */
    onOpenReminders: () -> Unit,
    /** P8 日历设置页(日历 tab 齿轮)。 */
    onOpenCalendarSettings: () -> Unit,
) {
    // Default to the Messages tab.
    var selectedTab by rememberSaveable { mutableIntStateOf(MainTab.Messages.ordinal) }
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WeMeetApp

    // 云文档 WebView 提升到 tab 层持有:tabs 是 `tabs[safeTab].content()` 重组切换,
    // 若放进 content lambda,每次切 tab 都会重建 WebView、重走加载 + KC SSO 重定向。
    // remember 一次跨 tab 存活;MainTabScreen 退出(登出)时销毁,避免泄漏。
    val docsDark = WeMeetTheme.isDark
    val docsWebView = remember { createDocsWebView(ctx, darkTheme = docsDark) }
    DisposableEffect(Unit) {
        onDispose {
            (docsWebView.parent as? ViewGroup)?.removeView(docsWebView)
            docsWebView.destroy()
        }
    }
    // 运行时切换深浅:UA 是创建时固化的,靠注入 postMessage 让常驻 docs 立即跟随
    // (docs ConfigProvider 内嵌时监听 wemeet-theme 消息)。首帧主题已由 UA 覆盖。
    LaunchedEffect(docsDark) {
        val scheme = if (docsDark) "dark" else "light"
        docsWebView.evaluateJavascript(
            "window.postMessage({type:'wemeet-theme',theme:'$scheme'},'*')",
            null,
        )
    }

    // Live unread total for the 消息 tab badge — fed by the process-wide IM
    // session so it counts even while another tab is selected. remember-gated so
    // a recomposition after sign-out (which calls ImSession.shutdown()) can't
    // resurrect a fresh session/socket for the user just logged out.
    val imSession = remember { ImSession.get(app) }
    val imUnread by imSession.totalUnread.collectAsStateWithLifecycle()

    // P8「在消息列表提醒日程」(对标飞书,默认开):今日/明日窗口 60s 轮询,
    // 30s tick 刷倒计时角标;开关关闭即停拉并隐藏入口。
    val reminderEnabled by app.settingsStore.imReminderEntry.collectAsStateWithLifecycle()
    var reminderWindow by remember { mutableStateOf<ReminderWindow?>(null) }
    var reminderNow by remember { mutableStateOf(java.time.ZonedDateTime.now()) }
    LaunchedEffect(reminderEnabled) {
        if (!reminderEnabled) {
            reminderWindow = null
            return@LaunchedEffect
        }
        while (true) {
            runCatching { loadReminderWindow(app.apiClient.calendarApi) }
                .onSuccess { reminderWindow = it }
            kotlinx.coroutines.delay(60_000)
        }
    }
    LaunchedEffect(reminderEnabled) {
        if (!reminderEnabled) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(30_000)
            reminderNow = java.time.ZonedDateTime.now()
        }
    }
    val reminderNearest =
        if (reminderEnabled) reminderWindow?.nearest(reminderNow) else null

    // rememberSaveable can restore an index from a build that had more tabs (我的
    // used to be ordinal 4). Normalize ONCE here and use `safeTab` at every read
    // site: coercing only where the content is looked up would still leave
    // CompactTabBar's `selectedTab == index` highlight matching nothing.
    val safeTab = selectedTab.coerceIn(MainTab.entries.indices)

    // Profile drawer, opened by tapping the avatar in the 消息 header.
    //
    // Swipe-to-OPEN is deliberately off (gesturesEnabled is true only once open,
    // i.e. swipe-to-close): drawerContent is composed lazily below, and
    // DrawerState exposes no "drag started" signal — `isAnimationRunning` is false
    // during a finger drag — so an edge swipe would drag out an empty sheet.
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Identity for the 消息 header. TokenStore's getters are plain prefs reads, not
    // snapshot state, so they can never trigger a recomposition — hold them in
    // Compose state instead and refresh explicitly. One /users/me/ at startup is
    // required, not waste: the header is always on screen and `avatar_url` is a
    // short-lived signed URL (CLAUDE.md: re-fetch rather than cache long-term).
    val tokenStore = app.tokenStore
    fun readSelfName() = tokenStore.nickname?.takeIf { it.isNotBlank() }
        ?: tokenStore.phone.orEmpty()

    var selfName by remember { mutableStateOf(readSelfName()) }
    var selfAvatarUrl by remember { mutableStateOf(tokenStore.avatarUrl) }
    // Department line under the name in the 消息 header (Feishu shows the company;
    // we show the department). It has no TokenStore/`users/me/` source — only the
    // org directory carries it, on the current user's member record — so fetch it
    // once here and pass it down like the other identity fields.
    var selfDepartment by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        app.profileRepository.refreshProfile()
        selfName = readSelfName()
        selfAvatarUrl = tokenStore.avatarUrl
        tokenStore.userId?.let { uid ->
            app.directoryRepository.getMember(uid).onSuccess {
                selfDepartment = it.department?.name
            }
        }
    }
    // The drawer is the only place the user can change their nickname/avatar, so
    // re-read once it closes — ProfileScreen writes TokenStore, not this state.
    LaunchedEffect(drawerState.isOpen) {
        if (!drawerState.isOpen) {
            selfName = readSelfName()
            selfAvatarUrl = tokenStore.avatarUrl
        }
    }

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
                selfName = selfName,
                selfAvatarUrl = tokenStore.avatarUrl,
                selfDepartment = selfDepartment,
                onAvatarClick = { scope.launch { drawerState.open() } },
                onOpenChat = onOpenChat,
                onNewChat = onNewChat,
                onOpenSearch = onOpenSearch,
                onScanQrCode = onScanQrCode,
                onCreateMeeting = onCreateMeeting,
                onJoinMeeting = onJoinMeeting,
                // P8:当日有未结束日程时的「日程提醒」入口(列表首项,随列表滚动)。
                listHeader = reminderNearest?.let { ev ->
                    {
                        ReminderEntryRow(
                            nearest = ev,
                            now = reminderNow,
                            onClick = onOpenReminders,
                        )
                    }
                },
            )
        },
        TabItem(R.string.tab_calendar, Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth) {
            CalendarTabScreen(
                onEventClick = onEventClick,
                onCreateEvent = onCreateEvent,
                onOpenSettings = onOpenCalendarSettings,
            )
        },
        TabItem(R.string.tab_meeting, Icons.Filled.Videocam, Icons.Outlined.Videocam) {
            HomeScreen(
                onCreateMeeting = onCreateMeeting,
                onJoinMeeting = onJoinMeeting,
                onJoinSlug = onJoinSlug,
                onHistoryClick = onHistoryClick,
                onScheduledClick = onScheduledClick,
                onOpenSettings = onOpenMeetingSettings,
            )
        },
        TabItem(R.string.tab_contacts, Icons.Filled.Contacts, Icons.Outlined.Contacts) {
            ContactsTabScreen(onMemberClick = onMemberClick)
        },
        TabItem(R.string.tab_docs, Icons.Filled.Description, Icons.Outlined.Description) {
            DocsTabScreen(docsWebView)
        },
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Swipe-to-close only; see the drawerState declaration for why opening by
        // gesture is off.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            // Passing drawerState is what installs DrawerPredictiveBackHandler —
            // the overload without it does NOT handle back, so system Back would
            // fall through to the NavHost and exit the app instead of closing the
            // drawer. Square edge: the drawer IS the profile page, not a rounded
            // sheet floating over it.
            // Feishu-style: full-height page that leaves a narrow strip of the
            // underlying content peeking on the right. The drawerState overload of
            // ModalDrawerSheet doesn't apply DrawerDefaults.MaximumDrawerWidth, so
            // width is unconstrained by default — cap it to 80% of screen width.
            ModalDrawerSheet(
                drawerState = drawerState,
                drawerShape = RectangleShape,
                modifier = Modifier.fillMaxWidth(0.8f),
            ) {
                // ProfileScreen must stay composed even while closed — gating the
                // sheet's content composition on drawerState collapses the drawer's
                // drag anchors and makes open() snap straight back to closed. Defer
                // only its network via `active` instead: ModalNavigationDrawer
                // composes drawerContent eagerly (closed is just a negative offset),
                // so an unguarded LaunchedEffect(Unit) would fetch on every cold
                // start and — never being disposed — never re-fetch on reopen.
                ProfileScreen(
                    onSettingsClick = onSettingsClick,
                    onOpenAiHub = onOpenAiHub,
                    onOpenApproval = onOpenApproval,
                    active = drawerState.isOpen || drawerState.isAnimationRunning,
                )
            }
        },
    ) {
        Scaffold(
            bottomBar = {
                CompactTabBar(
                    tabs = tabs,
                    selectedTab = safeTab,
                    onTabSelected = { selectedTab = it },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                tabs[safeTab].content()
            }
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
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
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
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
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
