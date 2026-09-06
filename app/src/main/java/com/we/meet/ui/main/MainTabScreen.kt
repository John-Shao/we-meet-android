package com.we.meet.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.ui.theme.Dimens
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.ui.chat.ForwardCreateGroupFlow
import com.we.meet.feature.im.ui.chat.ForwardPicker
import com.we.meet.feature.im.ui.list.ConversationListScreen
import com.we.meet.ui.calendar.CalendarTabScreen
import com.we.meet.ui.calendar.reminder.ReminderEntryRow
import com.we.meet.ui.calendar.reminder.ReminderWindow
import com.we.meet.ui.calendar.reminder.loadReminderWindow
import com.we.meet.ui.calendar.reminder.nearest
import android.view.ViewGroup
import android.webkit.WebView
import com.we.meet.BuildConfig
import com.we.meet.ui.contacts.ContactsTabScreen
import com.we.meet.ui.docs.DocsTabScreen
import com.we.meet.ui.docs.createDocsWebView
import com.we.meet.ui.docs.loadDocsTabEntry
import com.we.meet.ui.docs.postToDocs
import com.we.meet.ui.theme.WeMeetTheme
import com.we.meet.ui.home.HomeScreen
import com.we.meet.ui.docs.DocsWebViewClient
import com.we.meet.feature.docs.ui.DocsHomeScreen
import com.we.meet.feature.docs.ui.DocsNavController
import com.we.meet.feature.docs.ui.DocsNavDrawer
import com.we.meet.ui.profile.ProfileScreen
import com.we.meet.ui.tasks.TaskNavController
import com.we.meet.ui.tasks.TaskNavigationDrawer
import com.we.meet.ui.tasks.TaskScreen
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bottom tabs, in bar order. Feishu-style: 消息 · 日历 · 会议 · 通讯录.
 *
 * There is deliberately no 我的 tab — the profile page lives behind the avatar
 * in the 消息 header (see the [ModalNavigationDrawer] below), matching Feishu.
 * That means 消息 is the ONLY route to the profile page.
 */
enum class MainTab { Messages, Calendar, Meeting, Contacts, Docs, Tasks }

/** 分享云文档到聊天(入口 B)待处理请求:docs WebView 发来的一条「分享到聊天」。 */
private data class ShareDocRequest(val docId: String, val title: String, val url: String)

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
    onScanQrCode: () -> Unit,
    onHistoryClick: (roomId: String) -> Unit,
    /** P8:预约会议行 → 预约详情页。 */
    onScheduledClick: (slug: String, name: String, scheduledAtIso: String) -> Unit,
    /** 预约会议关联了日程 → 走统一的日程详情(一场会一个详情页)。 */
    onScheduledEventClick: (eventId: String) -> Unit,
    onSettingsClick: () -> Unit,
    onOpenMeetingSettings: () -> Unit,
    onOpenAiHub: () -> Unit,
    onOpenApproval: () -> Unit,
    onOpenChat: (cid: String) -> Unit,
    onNewChat: () -> Unit,
    onOpenSearch: () -> Unit,
    onMemberClick: (userId: String) -> Unit,
    /** 通讯录顶部的「星标联系人」入口。 */
    onOpenStarredContacts: () -> Unit,
    /** 通讯录顶部的「我的群组」入口(零后端,复用会话列表数据)。 */
    onOpenMyGroups: () -> Unit,
    onEventClick: (eventId: String) -> Unit,
    onCreateEvent: (epochDay: Long) -> Unit,
    /** 日历预选时段确认后按精确起止(epoch 秒)进创建表单。 */
    onCreateEventAt: (startEpochSecond: Long, endEpochSecond: Long) -> Unit,
    onCreateEventInRoom: (
        startEpochSecond: Long,
        endEpochSecond: Long,
        meetingRoomId: String,
    ) -> Unit,
    /** P8「在消息列表提醒日程」:置顶入口点开日程提醒页。 */
    onOpenReminders: () -> Unit,
    /** 日历主页进入日历管理中心。 */
    onOpenCalendarManagement: () -> Unit,
    /** 会议室页进入全局日历偏好设置。 */
    onOpenCalendarSettings: () -> Unit,
    /** 任务页齿轮与系统设置中的入口共用同一个任务设置页。 */
    onOpenTaskSettings: () -> Unit,
    /** 云文档原生化(M1):原生 tab 内的路由回调 —— 详情/搜索/回收站。 */
    onOpenDocDetail: (docId: String) -> Unit,
    onOpenDocsSearch: () -> Unit,
    onOpenDocsTrash: () -> Unit,
) {
    // Default to the Messages tab.
    var selectedTab by rememberSaveable { mutableIntStateOf(MainTab.Messages.ordinal) }
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WeMeetApp

    // 云文档原生化开关(M1):默认 true → tab 用 feature-docs 原生主页;false →
    // 回退常驻 WebView(p3-docs-app.md D6 同款保险丝)。WebView 实例只在兜底
    // 模式下创建,原生模式下不预加载、不占内存。
    val docsNative = BuildConfig.WE_MEET_DOCS_NATIVE

    // 分享云文档到聊天(入口 B)待处理请求状态:WebView 桥回调写入,选择器消费。
    var shareDocRequest by remember { mutableStateOf<ShareDocRequest?>(null) }
    var shareDocCreateGroup by remember { mutableStateOf(false) }

    // 云文档 WebView 提升到 tab 层持有:tabs 是 `tabs[safeTab].content()` 重组切换,
    // 若放进 content lambda,每次切 tab 都会重建 WebView、重走加载 + KC SSO 重定向。
    // remember 一次跨 tab 存活;MainTabScreen 退出(登出)时销毁,避免泄漏。
    val docsWebView: WebView? = if (!docsNative) {
        val docsDark = WeMeetTheme.isDark
        // deferInitialLoad:进站 URL 要先向后端换一张 Docs 登录票据(suspend),构造时
        // 拿不到 —— 直接 load 老入口只会先闪一下没有登录态的那条链路。
        val webView =
            remember { createDocsWebView(ctx, darkTheme = docsDark, deferInitialLoad = true) }
        LaunchedEffect(webView) { loadDocsTabEntry(ctx, webView) }
        DisposableEffect(Unit) {
            onDispose {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
        }
        // 分享云文档到聊天(入口 B):docs WebView 内点「分享到聊天」→ DocsHostBridge
        // 收到 postEvent → DocsWebViewClient.onShareDoc → 这里弹会话选择器。
        DisposableEffect(webView) {
            val client = webView.webViewClient as? DocsWebViewClient
            client?.onShareDoc = { docId, title, url ->
                shareDocRequest = ShareDocRequest(docId, title, url)
            }
            // 能力握手:docs 挂载后发 wemeet-embed-hello,这里回一条宣告 App 能提供什么。
            // 挂在这一层而不是 DocsTabScreen —— WebView 在 MainTabScreen 就开始预加载,
            // 用户可能压根还没点进云文档 tab,握手早已发生。
            client?.onEmbedHello = { replyDocsHostHello(webView) }
            // docs 里点搜索 / 按 Ctrl+K:它的自带搜索已收敛,转到 App 自己的全局搜索
            // (那里本来就含文档源,命中进 DocsViewerScreen)。
            client?.onOpenSearch = { onOpenSearch() }
            onDispose {
                client?.onShareDoc = null
                client?.onEmbedHello = null
                client?.onOpenSearch = null
            }
        }
        // 运行时切换深浅:UA 是创建时固化的,靠注入 postMessage 让常驻 docs 立即跟随
        // (docs ConfigProvider 内嵌时监听 wemeet-theme 消息)。首帧主题已由 UA 覆盖。
        LaunchedEffect(docsDark) {
            val scheme = if (docsDark) "dark" else "light"
            webView.evaluateJavascript(
                "window.postMessage({type:'wemeet-theme',theme:'$scheme'},'*')",
                null,
            )
        }
        webView
    } else {
        null
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
    val calendarTimezoneMode by app.settingsStore.calendarTimezoneMode.collectAsStateWithLifecycle()
    val calendarFixedTimezone by app.settingsStore.calendarFixedTimezone.collectAsStateWithLifecycle()
    val calendarZone = remember(calendarTimezoneMode, calendarFixedTimezone) {
        app.settingsStore.calendarZoneId()
    }
    var reminderWindow by remember { mutableStateOf<ReminderWindow?>(null) }
    var reminderNow by remember(calendarZone) {
        mutableStateOf(java.time.ZonedDateTime.now(calendarZone))
    }
    LaunchedEffect(reminderEnabled, calendarZone) {
        if (!reminderEnabled) {
            reminderWindow = null
            return@LaunchedEffect
        }
        while (true) {
            runCatching { loadReminderWindow(app.apiClient.calendarApi, calendarZone) }
                .onSuccess { reminderWindow = it }
            kotlinx.coroutines.delay(60_000)
        }
    }
    LaunchedEffect(reminderEnabled, calendarZone) {
        if (!reminderEnabled) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(30_000)
            reminderNow = java.time.ZonedDateTime.now(calendarZone)
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

    // Task navigation drawer — hoisted here (like the profile drawer) so its
    // scrim covers the whole screen including the bottom tab bar. The controller
    // is registered by TaskScreen (which owns the TaskViewModel + nav state).
    val taskNavDrawerState = rememberDrawerState(DrawerValue.Closed)
    val taskNavScope = rememberCoroutineScope()
    var taskNavController by remember { mutableStateOf<TaskNavController?>(null) }
    var taskDetailVisible by remember { mutableStateOf(false) }
    // Close the task drawer when the user leaves the Tasks tab.
    LaunchedEffect(safeTab) {
        if (safeTab != MainTab.Tasks.ordinal && taskNavDrawerState.isOpen) {
            taskNavDrawerState.close()
        }
        if (safeTab != MainTab.Tasks.ordinal) taskDetailVisible = false
    }

    // 云文档二级导航抽屉 —— 与 task 抽屉同款提升到本层(遮罩覆盖底部导航栏),
    // 控制器由 DocsHomeScreen 经 onRegisterDocsNav 注册(它持有 DocsHomeViewModel)。
    val docsNavDrawerState = rememberDrawerState(DrawerValue.Closed)
    val docsNavScope = rememberCoroutineScope()
    var docsNavController by remember { mutableStateOf<DocsNavController?>(null) }
    LaunchedEffect(safeTab) {
        if (safeTab != MainTab.Docs.ordinal && docsNavDrawerState.isOpen) {
            docsNavDrawerState.close()
        }
    }

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
                selfAvatarUrl = selfAvatarUrl,
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
                onCreateEventAt = onCreateEventAt,
                onCreateEventInRoom = onCreateEventInRoom,
                onOpenManagement = onOpenCalendarManagement,
                onOpenSettings = onOpenCalendarSettings,
            )
        },
        TabItem(R.string.tab_meeting, Icons.Filled.Videocam, Icons.Outlined.Videocam) {
            HomeScreen(
                onCreateMeeting = onCreateMeeting,
                onJoinMeeting = onJoinMeeting,
                onHistoryClick = onHistoryClick,
                onScheduledClick = onScheduledClick,
                onScheduledEventClick = onScheduledEventClick,
                // 预约会议 = 创建日程:复用日历的创建日程入口,默认落在今天。
                onScheduleMeeting = { onCreateEvent(java.time.LocalDate.now().toEpochDay()) },
                onOpenSettings = onOpenMeetingSettings,
            )
        },
        TabItem(R.string.tab_contacts, Icons.Filled.Contacts, Icons.Outlined.Contacts) {
            ContactsTabScreen(
                onMemberClick = onMemberClick,
                onOpenStarred = onOpenStarredContacts,
                onOpenMyGroups = onOpenMyGroups,
            )
        },
        TabItem(R.string.tab_docs, Icons.Filled.Description, Icons.Outlined.Description) {
            if (docsNative) {
                DocsHomeScreen(
                    deps = app,
                    onOpenDoc = onOpenDocDetail,
                    onOpenSearch = onOpenDocsSearch,
                    onOpenTrash = onOpenDocsTrash,
                    onRegisterDocsNav = { docsNavController = it },
                    onOpenNavDrawer = { docsNavScope.launch { docsNavDrawerState.open() } },
                )
            } else {
                docsWebView?.let { DocsTabScreen(it) }
            }
        },
        TabItem(R.string.tab_tasks, Icons.Filled.TaskAlt, Icons.Outlined.TaskAlt) {
            TaskScreen(
                ownerName = selfName,
                app = app,
                onOpenSettings = onOpenTaskSettings,
                onOpenTaskNav = { taskNavScope.launch { taskNavDrawerState.open() } },
                onRegisterTaskNav = { taskNavController = it },
                onDetailVisibilityChanged = { taskDetailVisible = it },
            )
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
        // Task nav drawer — hoisted to cover the whole screen (incl. the bottom
        // tab bar), matching the profile drawer. The content reads the shared
        // TaskViewModel through the controller TaskScreen registered.
        ModalNavigationDrawer(
            drawerState = taskNavDrawerState,
            gesturesEnabled = taskNavDrawerState.isOpen,
            drawerContent = {
                ModalDrawerSheet(
                    drawerState = taskNavDrawerState,
                    drawerShape = RectangleShape,
                    modifier = Modifier.fillMaxWidth(0.8f),
                ) {
                    taskNavController
                        ?.takeIf { safeTab == MainTab.Tasks.ordinal }
                        ?.let { c ->
                            val ui by c.vm.ui.collectAsStateWithLifecycle()
                            TaskNavigationDrawer(
                                selectedView = ui.view,
                                selectedListId = ui.selectedList?.id,
                                taskLists = ui.taskLists,
                                archivedTaskLists = ui.archivedTaskLists,
                                archivedTaskListsLoading = ui.archivedListsLoading,
                                restoringArchivedTaskList = ui.navigationMutating,
                                listGroups = ui.listGroups,
                                taskGroups = ui.taskGroups,
                                taskGroupMutating = ui.navigationMutating,
                                selectedGroupId = ui.selectedGroupId,
                                assignedCount = ui.navigationCounts.assigned,
                                followingCount = ui.navigationCounts.following,
                                createdCount = ui.navigationCounts.created,
                                allCount = ui.navigationCounts.all,
                                standaloneCount = ui.navigationCounts.standalone,
                                onDismiss = { taskNavScope.launch { taskNavDrawerState.close() } },
                                onSelectView = {
                                    c.vm.setView(it)
                                    taskNavScope.launch { taskNavDrawerState.close() }
                                },
                                onSelectTaskGroup = {
                                    c.vm.selectGroup(it.id)
                                    taskNavScope.launch { taskNavDrawerState.close() }
                                },
                                onCreateTaskGroup = { name, onCreated ->
                                    c.vm.createTaskGroup(name, onCreated = onCreated)
                                },
                                onRenameTaskGroup = { group, name, onRenamed ->
                                    c.vm.renameTaskGroup(group, name, onRenamed)
                                },
                                onMoveTaskGroup = c.vm::moveTaskGroup,
                                onDeleteTaskGroup = { group, onDeleted ->
                                    c.vm.deleteTaskGroup(group, onDeleted)
                                },
                                onOpenActivity = {
                                    c.onOpenActivity()
                                    taskNavScope.launch { taskNavDrawerState.close() }
                                },
                                onSelectList = { list ->
                                    c.vm.selectList(list.id)
                                    taskNavScope.launch { taskNavDrawerState.close() }
                                },
                                onNewGroup = { c.onNewGroup() },
                                onNewList = { c.onNewList() },
                                onShowArchivedTaskListsChange = { show ->
                                    if (show) c.vm.loadArchivedTaskLists()
                                },
                                onRestoreArchivedTaskList = c.vm::restoreTaskList,
                                onGroupAction = { c.onGroupAction(it) },
                                onListAction = { c.onListAction(it) },
                            )
                        }
                }
            },
        ) {
            // 云文档二级导航抽屉 —— 与 task 抽屉同款宿主在此(遮罩覆盖底部导航栏)。
            ModalNavigationDrawer(
                drawerState = docsNavDrawerState,
                gesturesEnabled = docsNavDrawerState.isOpen,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerState = docsNavDrawerState,
                        drawerShape = RectangleShape,
                        modifier = Modifier.fillMaxWidth(0.8f),
                    ) {
                        docsNavController
                            ?.takeIf { safeTab == MainTab.Docs.ordinal }
                            ?.let { c ->
                                val docsUi by c.viewModel.state.collectAsStateWithLifecycle()
                                DocsNavDrawer(
                                    selectedFilter = docsUi.filter,
                                    allCount = docsUi.allCount,
                                    mineCount = docsUi.mineCount,
                                    sharedCount = docsUi.sharedCount,
                                    trashCount = docsUi.trashCount,
                                    onDismiss = { docsNavScope.launch { docsNavDrawerState.close() } },
                                    onSelectFilter = { filter ->
                                        c.viewModel.setFilter(filter)
                                        docsNavScope.launch { docsNavDrawerState.close() }
                                    },
                                    onOpenTrash = {
                                        docsNavScope.launch { docsNavDrawerState.close() }
                                        c.onOpenTrash()
                                    },
                                )
                            }
                    }
                },
            ) {
                Scaffold(
                    bottomBar = {
                        if (!(safeTab == MainTab.Tasks.ordinal && taskDetailVisible)) {
                            CompactTabBar(
                                tabs = tabs,
                                selectedTab = safeTab,
                                onTabSelected = { selectedTab = it },
                            )
                        }
                    },
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .padding(padding)
                            .consumeWindowInsets(padding),
                    ) {
                        tabs[safeTab].content()
                    }
                }
            }
        }
    }

    // 分享云文档到聊天(入口 B):每个目标先确认 doc-card 已发出，再授予
    // 对应会话成员只读权限。发送失败绝不能留下无聊天记录的授权。
    shareDocRequest?.let { req ->
        val docCardBody = JSONObject()
            .put("v", 1)
            .put("doc_id", req.docId)
            .put("title", req.title)
            .put("url", req.url)
            .toString()
        ForwardPicker(
            deps = app,
            targets = imSession.allForwardTargets(),
            onForward = { cids ->
                scope.launch {
                    val delivered = cids.filter {
                        imSession.sendMessage(it, docCardBody, "doc-card").isSuccess
                    }
                    // Only recipients of a successfully persisted card may
                    // receive access; authorization itself remains best-effort.
                    if (imSession.grantDocAccess(req.docId, delivered)) {
                        docsWebView?.let { notifyDocsAccessUpdated(it, req.docId) }
                    }
                }
                shareDocRequest = null
            },
            onCreateGroupForward = { shareDocCreateGroup = true },
            onDismiss = { shareDocRequest = null },
        )
        if (shareDocCreateGroup) {
            ForwardCreateGroupFlow(
                deps = app,
                onCreated = { newCid ->
                    scope.launch {
                        if (imSession.sendMessage(newCid, docCardBody, "doc-card").isSuccess &&
                            imSession.grantDocAccess(req.docId, listOf(newCid))
                        ) {
                            docsWebView?.let { notifyDocsAccessUpdated(it, req.docId) }
                        }
                    }
                    shareDocCreateGroup = false
                    shareDocRequest = null
                },
                onCancel = { shareDocCreateGroup = false },
            )
        }
    }

}

/**
 * 「分享到聊天」的授权发生在 docs **之外**(原生直连 meet 后端),WebView 里
 * 那个分享弹窗的成员列表缓存无从知晓,回到弹窗仍是分享前的「与 N 位用户分享」。
 * 授权成功后注入一条 postMessage,docs 侧 useDocAccessRefreshBridge 收到即失效
 * 相关查询重新拉取 —— 与 web iframe 走同一条消息通道、同一个 type。
 *
 * 注:必须在主线程调用(WebView 的硬性要求);调用点都在 rememberCoroutineScope
 * 的 Main 上下文里,挂起点恢复后仍在主线程。payload 用 JSONObject 拼,避免把
 * docId 直接插进 JS 源码。
 */
private fun notifyDocsAccessUpdated(webView: android.webkit.WebView, docId: String) {
    postToDocs(
        webView,
        JSONObject()
            .put("type", "wemeet-doc-access-updated")
            .put("docId", docId),
    )
}

/**
 * 回应 docs 的 `wemeet-embed-hello`,宣告 App 这一侧提供了哪些能力。
 *
 * docs 据此才决定要不要把自带入口收掉 —— 譬如只有看到 `global-search`,它才会把
 * 搜索按钮的点击改成派发给宿主。**老 App 不发这条 → docs 什么都不收 → 行为与
 * 改动前一致**,所以 docs 镜像可以先于 App 上线,不需要三端同步发版。
 *
 * 不宣告 `route-sync`:App 没有地址栏,同步站内路由无意义。
 */
private fun replyDocsHostHello(webView: android.webkit.WebView) {
    postToDocs(
        webView,
        JSONObject()
            .put("type", "wemeet-host-hello")
            .put("protocol", 1)
            .put("platform", "app")
            .put("features", JSONArray(listOf("global-search", "shell-nav"))),
    )
}

@Composable
private fun CompactTabBar(
    tabs: List<TabItem>,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        tabs.forEachIndexed { index, tab ->
            val selected = selectedTab == index
            NavigationBarItem(
                modifier = Modifier.testTag("main-tab-${MainTab.entries[index].name.lowercase()}"),
                selected = selected,
                onClick = { onTabSelected(index) },
                icon = {
                    BadgedBox(
                        badge = {
                            if (tab.badgeCount > 0) {
                                Badge {
                                    Text(if (tab.badgeCount > 99) "99+" else tab.badgeCount.toString())
                                }
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = null,
                        )
                    }
                },
                label = { Text(stringResource(tab.labelRes)) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
        }
    }
}
