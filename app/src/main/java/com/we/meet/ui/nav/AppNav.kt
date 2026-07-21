package com.we.meet.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.we.meet.WeMeetApp
import com.we.meet.R
import com.we.meet.ui.theme.Dimens
import com.we.meet.data.auth.SessionState
import com.we.meet.feature.assistant.aicall.ui.AssistantCallScreen
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.call.CallState
import com.we.meet.feature.im.call.CallUiEvent
import com.we.meet.feature.im.ui.call.CallScreen
import com.we.meet.push.CallNotifier
import com.we.meet.util.dialNumber
import com.we.meet.feature.im.ui.chat.ChatScreen
import com.we.meet.feature.im.ui.chat.DirectChatSettingsScreen
import com.we.meet.feature.im.ui.group.GroupInfoScreen
import com.we.meet.feature.im.ui.newchat.AddMembersScreen
import com.we.meet.feature.im.ui.newchat.NewChatScreen
import com.we.meet.feature.im.ui.search.MessageSearchScreen
import com.we.meet.ui.calendar.CreateEventScreen
import com.we.meet.ui.calendar.EventDetailScreen
import com.we.meet.ui.calendar.FreeBusyCompareScreen
import com.we.meet.ui.contacts.MemberDetailScreen
import com.we.meet.ui.login.LoginScreen
import com.we.meet.ui.login.WebLoginScreen
import com.we.meet.ui.main.MainTabScreen
import com.we.meet.ui.preview.PreviewMode
import com.we.meet.ui.preview.PreviewScreen
import com.we.meet.ui.qrscan.QrScanResult
import com.we.meet.ui.qrscan.QrScanScreen
import com.we.meet.ui.room.RoomScreen
import com.we.meet.ui.settings.AccountSecurityScreen
import com.we.meet.ui.settings.MeetingSettingsScreen
import com.we.meet.ui.settings.SettingsScreen
import com.we.meet.ui.waiting.WaitingRoomScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val ACCOUNT_SECURITY = "account_security"
    const val MEETING_SETTINGS = "meeting_settings"
    /**
     * `name` optionally seeds the meeting-name field in Create mode — populated
     * when 发起会议 came from a group chat ("{群名}的视频会议"), empty from the
     * home/tab entry. `audioOnly` starts the preview with the camera off and
     * hidden (used by 语音通话 from a 1:1 chat). Both are query params so they
     * stay optional (see [createPreview]).
     */
    const val CREATE_PREVIEW = "create_preview?name={name}&audioOnly={audioOnly}"
    /**
     * `slug` is an optional query parameter — empty when the user navigated
     * via the tab bar's "join meeting" entry, populated when they came in
     * via an App Links deep link. Compose Navigation 2.x treats the query
     * as part of the route pattern; the default value comes from the
     * navArgument below.
     */
    const val JOIN_PREVIEW = "join_preview?slug={slug}"
    const val QR_SCAN = "qr_scan"
    const val ASSISTANT_CALL = "assistant_call"
    const val AI_HUB = "ai_hub"
    const val APPROVAL = "approval"
    const val APPROVAL_SUBMIT = "approval_submit?templateId={templateId}"

    fun approvalSubmit(templateId: String? = null): String =
        if (templateId == null) "approval_submit"
        else "approval_submit?templateId=${URLEncoder.encode(templateId, StandardCharsets.UTF_8.name())}"

    // IM — full-screen chat routes above the tab scaffold.
    private const val IM_CHAT_BASE = "im_chat"
    const val IM_CHAT = "$IM_CHAT_BASE/{cid}?seq={seq}"
    /** P1-M3 全局搜索页(会话过滤 + 消息全文检索)。 */
    const val IM_SEARCH = "im_search"
    private const val IM_GROUP_INFO_BASE = "im_group_info"
    const val IM_GROUP_INFO = "$IM_GROUP_INFO_BASE/{cid}"
    const val IM_NEW_CHAT = "im_new_chat?peer={peer}"
    private const val IM_ADD_MEMBERS_BASE = "im_add_members"
    const val IM_ADD_MEMBERS = "$IM_ADD_MEMBERS_BASE/{cid}"
    private const val IM_DIRECT_SETTINGS_BASE = "im_direct_settings"
    const val IM_DIRECT_SETTINGS = "$IM_DIRECT_SETTINGS_BASE/{cid}"
    /** P1 一对一通话 — full-screen call UI (outgoing/incoming/connecting). */
    const val IM_CALL = "im_call"

    // Contacts / Calendar detail routes.
    private const val MEMBER_DETAIL_BASE = "member_detail"
    const val MEMBER_DETAIL = "$MEMBER_DETAIL_BASE/{userId}"
    private const val EVENT_DETAIL_BASE = "event_detail"
    const val EVENT_DETAIL = "$EVENT_DETAIL_BASE/{eventId}"
    /** P8「在消息列表提醒日程」:日程提醒页(今日/明日安排)。 */
    const val REMINDERS = "reminders"
    /** P8 日历设置页(列表提醒开关/周起始/默认时长/默认提醒)。 */
    const val CALENDAR_SETTINGS = "calendar_settings"
    const val CREATE_EVENT =
        "create_event?epochDay={epochDay}&eventId={eventId}&editScope={editScope}" +
            "&startSec={startSec}&endSec={endSec}&attendeeIds={attendeeIds}&srcCid={srcCid}"
    /** P8 忙闲对比页:ids=逗号分隔 we-meet uuid;title=页标题;srcCid=回发日程卡片的会话。 */
    const val FREE_BUSY = "free_busy?ids={ids}&title={title}&srcCid={srcCid}"

    /** New-chat picker, optionally seeded with a peer (从直聊「新建群聊」预选对端)。 */
    fun imNewChat(peerUserId: String? = null): String =
        if (peerUserId.isNullOrBlank()) "im_new_chat"
        else "im_new_chat?peer=${URLEncoder.encode(peerUserId, StandardCharsets.UTF_8.name())}"

    fun imChat(cid: String, seq: Long? = null): String {
        val base = "$IM_CHAT_BASE/${URLEncoder.encode(cid, StandardCharsets.UTF_8.name())}"
        // P1-M3 消息定位:搜索命中携带 seq,ChatScreen 加载至该条并高亮。
        return if (seq != null && seq > 0) "$base?seq=$seq" else base
    }

    fun imGroupInfo(cid: String): String =
        "$IM_GROUP_INFO_BASE/${URLEncoder.encode(cid, StandardCharsets.UTF_8.name())}"

    fun imAddMembers(cid: String): String =
        "$IM_ADD_MEMBERS_BASE/${URLEncoder.encode(cid, StandardCharsets.UTF_8.name())}"

    fun imDirectSettings(cid: String): String =
        "$IM_DIRECT_SETTINGS_BASE/${URLEncoder.encode(cid, StandardCharsets.UTF_8.name())}"

    fun memberDetail(userId: String): String =
        "$MEMBER_DETAIL_BASE/${URLEncoder.encode(userId, StandardCharsets.UTF_8.name())}"

    fun eventDetail(eventId: String): String =
        "$EVENT_DETAIL_BASE/${URLEncoder.encode(eventId, StandardCharsets.UTF_8.name())}"

    fun createEvent(epochDay: Long): String = "create_event?epochDay=$epochDay"

    /** P8 忙闲页预填创建:精确起止(epoch 秒)+ 预选参与者;可携 srcCid 回发卡片。 */
    fun createEventPrefilled(
        startSec: Long,
        endSec: Long,
        attendeeIds: List<String>,
        srcCid: String? = null,
    ): String {
        fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8.name())
        val base = "create_event?startSec=$startSec&endSec=$endSec" +
            "&attendeeIds=${enc(attendeeIds.joinToString(","))}"
        return if (srcCid.isNullOrBlank()) base else "$base&srcCid=${enc(srcCid)}"
    }

    /** P8 忙闲对比页(单聊「查看日历」/群聊「群成员日历」共用)。 */
    fun freeBusy(userIds: List<String>, title: String, srcCid: String? = null): String {
        fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8.name())
        val base = "free_busy?ids=${enc(userIds.joinToString(","))}&title=${enc(title)}"
        return if (srcCid.isNullOrBlank()) base else "$base&srcCid=${enc(srcCid)}"
    }

    fun editEvent(eventId: String, editScope: String? = null): String {
        val base = "create_event?eventId=${URLEncoder.encode(eventId, StandardCharsets.UTF_8.name())}"
        // P2-M2 重复子场次:携带编辑范围(one/following/all),单次/主事件不带。
        return if (editScope != null) "$base&editScope=$editScope" else base
    }

    private const val WAITING_ROOM_BASE = "waiting_room"
    const val WAITING_ROOM = "$WAITING_ROOM_BASE/{idOrSlug}/{name}/{mic}/{cam}"

    fun waitingRoom(idOrSlug: String, name: String, mic: Boolean, cam: Boolean): String {
        fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8.name())
        return "$WAITING_ROOM_BASE/${enc(idOrSlug)}/${enc(name)}/$mic/$cam"
    }

    /**
     * Build a CreatePreview route URL, optionally seeding the meeting name and
     * starting in audio-only mode (语音通话). Only appends the params actually
     * set, so the bare "create_preview" (home/tab entry) still matches.
     */
    fun createPreview(meetingName: String? = null, audioOnly: Boolean = false): String {
        val params = buildList {
            meetingName?.takeIf { it.isNotBlank() }?.let {
                add("name=${URLEncoder.encode(it, StandardCharsets.UTF_8.name())}")
            }
            if (audioOnly) add("audioOnly=true")
        }
        return if (params.isEmpty()) "create_preview"
        else "create_preview?${params.joinToString("&")}"
    }

    /** Build a JoinPreview route URL, optionally seeding the meeting-id input. */
    fun joinPreview(slug: String? = null): String {
        val s = slug.orEmpty()
        return if (s.isBlank()) "join_preview?slug=" else "join_preview?slug=$s"
    }

    private const val ROOM_BASE = "room"
    // peerUid / media are optional query params — set only when the room was
    // entered from a 1:1 call (drives the minimal voice-call UI). Absent for
    // meetings, so those match unchanged.
    const val ROOM = "$ROOM_BASE/{roomId}/{url}/{token}/{name}/{slug}/{host}/{createdAt}/{isAdmin}/{mic}/{cam}?peerUid={peerUid}&peerName={peerName}&media={media}&meet={meet}"

    private const val HISTORY_BASE = "history_detail"
    const val HISTORY_DETAIL = "$HISTORY_BASE/{roomId}"

    fun room(
        roomId: String, url: String, token: String, name: String, slug: String,
        host: String?, createdAtMs: Long, isAdmin: Boolean, mic: Boolean, cam: Boolean,
        peerUid: String? = null, peerName: String? = null, media: String? = null,
        meet: Boolean = false,
    ): String {
        fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8.name())
        // Empty host serialises as "" which decode() round-trips cleanly; the
        // receiver treats blank as null.
        val base =
            "$ROOM_BASE/${enc(roomId)}/${enc(url)}/${enc(token)}/${enc(name)}/${enc(slug)}/${enc(host.orEmpty())}/$createdAtMs/$isAdmin/$mic/$cam"
        val query = buildList {
            peerUid?.takeIf { it.isNotBlank() }?.let { add("peerUid=${enc(it)}") }
            peerName?.takeIf { it.isNotBlank() }?.let { add("peerName=${enc(it)}") }
            media?.takeIf { it.isNotBlank() }?.let { add("media=${enc(it)}") }
            // P4: accepted escalation invite — land in the multi-party form.
            if (meet) add("meet=true")
        }
        return if (query.isEmpty()) base else "$base?${query.joinToString("&")}"
    }

    fun historyDetail(roomId: String): String {
        val enc = URLEncoder.encode(roomId, StandardCharsets.UTF_8.name())
        return "$HISTORY_BASE/$enc"
    }

    // 搜索统一 M2:全局搜索「文档」命中的应用内查看器。
    private const val DOCS_VIEWER_BASE = "docs_viewer"
    const val DOCS_VIEWER = "$DOCS_VIEWER_BASE/{url}"

    fun docsViewer(url: String): String {
        val enc = URLEncoder.encode(url, StandardCharsets.UTF_8.name())
        return "$DOCS_VIEWER_BASE/$enc"
    }

    fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}

@Composable
fun AppNav() {
    val context = LocalContext.current
    val app = context.applicationContext as WeMeetApp
    val navController = rememberNavController()
    // Scope for one-shot host actions triggered from screen callbacks (e.g. the
    // P3 reveal-phone → system-dial handoff), which outlive a single frame.
    val dialScope = rememberCoroutineScope()

    // Guard against a Compose double-tap on any back button popping past
    // the root and leaving NavHost without a destination (blank screen).
    // `previousBackStackEntry` is non-null only when something sits below
    // the current entry — so the second tap, fired after the first pop
    // already shrank the stack to its root, simply no-ops.
    val safePop: () -> Unit = {
        if (navController.previousBackStackEntry != null) {
            navController.popBackStack()
        }
    }

    val startDestination = if (app.tokenStore.isLoggedIn()) Routes.HOME else Routes.LOGIN

    // Set by RoomScreen when the server disconnected us because the host
    // ended the meeting. Rendered as a bottom sheet overlay on top of
    // whatever NavHost is showing (typically Home after the auto pop).
    var hostEndedSheetVisible by remember { mutableStateOf(false) }

    // App-Links deep link handler. MainActivity writes the parsed slug onto
    // app.pendingJoinSlug from onCreate / onNewIntent. We forward it to
    // JoinPreview only if the user is already logged in — if they're sitting
    // on LoginScreen, dropping them onto a Preview they can't actually join
    // is jarring; the slug is simply discarded. (S2/S3 may refine this with
    // "remember slug across login" if it becomes a real ask.)
    LaunchedEffect(Unit) {
        app.pendingJoinSlug.collect { slug ->
            if (slug.isNullOrBlank()) return@collect
            // Reset first so a repeat deep link (same slug, second tap from
            // the same chat) still fires — collectors only see distinct
            // emissions, and the navigate below is fire-and-forget.
            app.pendingJoinSlug.value = null
            if (!app.tokenStore.isLoggedIn()) return@collect
            navController.navigate(Routes.joinPreview(slug)) {
                // Don't accumulate Preview screens if the user keeps tapping
                // links — keep one Preview at most on the stack above Home.
                launchSingleTop = true
                popUpTo(Routes.HOME)
            }
        }
    }

    // IM push deep link (wemeet://im?cid=...) — same stash/consume-once pattern
    // as pendingJoinSlug. Routes into the chat thread; requires login, else
    // discarded (a push for a signed-out account shouldn't exist anyway —
    // tokens re-bind on account switch).
    LaunchedEffect(Unit) {
        app.pendingChatCid.collect { cid ->
            if (cid.isNullOrBlank()) return@collect
            app.pendingChatCid.value = null
            if (!app.tokenStore.isLoggedIn()) return@collect
            navController.navigate(Routes.imChat(cid)) {
                launchSingleTop = true
                popUpTo(Routes.HOME)
            }
        }
    }

    // P1 一对一通话: watch the IM call machine and drive navigation.
    //   Idle → Outgoing/Incoming: push the full-screen call UI.
    //   EnterRoom event: jump into the LiveKit room (audio call → cam off),
    //     replacing the call screen if it's still on the stack.
    //   Error event: toast (busy-local / call failed).
    // Hooked lazily on the first back-stack tick where the user is logged in,
    // so ImSession isn't instantiated while sitting on the login screen.
    LaunchedEffect(Unit) {
        var hooked = false
        navController.currentBackStackEntryFlow.collect {
            if (hooked || !app.tokenStore.isLoggedIn()) return@collect
            hooked = true
            val calls = ImSession.get(app).calls
            launch {
                calls.state.collect { st ->
                    val onCallScreen =
                        navController.currentDestination?.route == Routes.IM_CALL
                    val wantsScreen = st is CallState.Outgoing ||
                        st is CallState.Incoming || st is CallState.Connecting
                    if (wantsScreen && !onCallScreen) {
                        navController.navigate(Routes.IM_CALL) { launchSingleTop = true }
                    }
                    // P2: the in-app call UI supersedes the push notification —
                    // clear it as soon as the call is on screen, and on every
                    // terminal (canceled while still ringing in the shade).
                    when (st) {
                        is CallState.Incoming -> CallNotifier.cancel(context, st.info.callId)
                        is CallState.Ended -> CallNotifier.cancel(context, st.info.callId)
                        else -> Unit
                    }
                }
            }
            launch {
                calls.events.collect { ev ->
                    when (ev) {
                        is CallUiEvent.EnterRoom -> {
                            navController.navigate(
                                Routes.room(
                                    roomId = ev.room.roomId,
                                    url = ev.room.livekitUrl,
                                    token = ev.room.livekitToken,
                                    name = ev.room.roomName,
                                    slug = ev.room.slug,
                                    // Caller created the room → owner/admin.
                                    host = null,
                                    createdAtMs = ev.room.createdAtMs,
                                    isAdmin = ev.info.outgoing,
                                    mic = true,
                                    cam = ev.info.media == "video",
                                    // Carry the peer + media so RoomScreen can
                                    // render the minimal voice-call UI (audio).
                                    peerUid = ev.info.peerUid,
                                    peerName = ev.info.peerName,
                                    media = ev.info.media,
                                    meet = ev.info.kind == "meet" || ev.info.kind == "group",
                                )
                            ) {
                                // Replace the call screen; no-op if it already popped.
                                popUpTo(Routes.IM_CALL) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                        is CallUiEvent.Error -> {
                            val msgRes = if (ev.message == "busy-local") {
                                com.we.meet.feature.im.R.string.im_call_busy_local
                            } else if (ev.message == "group-call-ended") {
                                com.we.meet.feature.im.R.string.im_group_card_ended_toast
                            } else {
                                com.we.meet.feature.im.R.string.im_call_end_failed
                            }
                            android.widget.Toast.makeText(context, msgRes, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.LOGIN) {
            val onLoggedIn = {
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                }
            }
            // WebView Keycloak unified login by default — it seeds the KC
            // session cookie the Docs tab SSOs with (p3-docs-app.md D1).
            // Flip WE_MEET_WEB_LOGIN=false to fall back to the native OTP.
            if (com.we.meet.BuildConfig.WE_MEET_WEB_LOGIN) {
                WebLoginScreen(onLoggedIn = onLoggedIn)
            } else {
                LoginScreen(onLoggedIn = onLoggedIn)
            }
        }

        composable(Routes.HOME) {
            MainTabScreen(
                onCreateMeeting = { navController.navigate(Routes.createPreview()) },
                onJoinMeeting = { navController.navigate(Routes.joinPreview()) },
                onJoinSlug = { slug -> navController.navigate(Routes.joinPreview(slug)) },
                onScanQrCode = { navController.navigate(Routes.QR_SCAN) },
                onHistoryClick = { roomId ->
                    navController.navigate(Routes.historyDetail(roomId))
                },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onOpenMeetingSettings = { navController.navigate(Routes.MEETING_SETTINGS) },
                onOpenAiHub = { navController.navigate(Routes.AI_HUB) },
                onOpenApproval = { navController.navigate(Routes.APPROVAL) },
                onOpenChat = { cid -> navController.navigate(Routes.imChat(cid)) },
                onNewChat = { navController.navigate(Routes.imNewChat()) },
                onOpenSearch = { navController.navigate(Routes.IM_SEARCH) },
                onMemberClick = { userId -> navController.navigate(Routes.memberDetail(userId)) },
                onEventClick = { eventId -> navController.navigate(Routes.eventDetail(eventId)) },
                onCreateEvent = { epochDay -> navController.navigate(Routes.createEvent(epochDay)) },
                // P8「在消息列表提醒日程」:置顶入口 → 日程提醒页。
                onOpenReminders = { navController.navigate(Routes.REMINDERS) },
                // P8:日历 tab 齿轮 → 日历设置页。
                onOpenCalendarSettings = { navController.navigate(Routes.CALENDAR_SETTINGS) },
            )
        }

        composable(Routes.ASSISTANT_CALL) {
            AssistantCallScreen(
                deps = app,
                onBack = rememberOnceOnly(safePop),
            )
        }

        composable(Routes.AI_HUB) {
            AiHubRoute(onBack = rememberOnceOnly(safePop)) {
                navController.navigate(Routes.ASSISTANT_CALL)
            }
        }

        composable(Routes.APPROVAL) {
            com.we.meet.ui.approval.ApprovalScreen(
                onBack = rememberOnceOnly(safePop),
                onCreate = { navController.navigate(Routes.approvalSubmit()) },
            )
        }

        composable(
            route = Routes.APPROVAL_SUBMIT,
            arguments = listOf(
                navArgument("templateId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            com.we.meet.ui.approval.SubmitApprovalScreen(
                presetTemplateId = entry.arguments?.getString("templateId")?.let { Routes.decode(it) },
                onBack = rememberOnceOnly(safePop),
                onDone = rememberOnceOnly(safePop),
            )
        }

        composable(
            route = Routes.IM_CHAT,
            arguments = listOf(
                navArgument("cid") { type = NavType.StringType },
                navArgument("seq") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { entry ->
            val cid = Routes.decode(entry.arguments?.getString("cid").orEmpty())
            val locateSeq = entry.arguments?.getLong("seq")?.takeIf { it > 0 }
            ChatScreen(
                deps = app,
                cid = cid,
                locateSeq = locateSeq,
                onBack = rememberOnceOnly(safePop),
                onOpenInfo = { navController.navigate(Routes.imGroupInfo(it)) },
                onOpenDirectSettings = { navController.navigate(Routes.imDirectSettings(it)) },
                onMemberClick = { userId -> navController.navigate(Routes.memberDetail(userId)) },
                // P8 日程卡片 → 日程详情页(EVENT_DETAIL 已有)。
                onOpenEvent = { eventId -> navController.navigate(Routes.eventDetail(eventId)) },
                onStartMeeting = { meetingName -> navController.navigate(Routes.createPreview(meetingName)) },
                // 1:1 通话 no longer flows through here — ChatScreen drives
                // CallController directly and the top-level collector below
                // pushes Routes.IM_CALL when the call machine leaves Idle.
                // P3 拨打电话: reveal the peer's full number (server notifies the
                // owner) then hand off to the system dialer.
                onDialPeer = { peerUserId ->
                    dialScope.launch {
                        val phone = app.directoryRepository.revealPhone(peerUserId).getOrNull()
                        if (phone.isNullOrBlank()) {
                            android.widget.Toast.makeText(
                                context, R.string.dial_no_number, android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            dialNumber(context, phone)
                        }
                    }
                },
            )
        }

        composable(Routes.IM_CALL) {
            CallScreen(deps = app, onExit = rememberOnceOnly(safePop))
        }

        composable(
            route = Routes.IM_GROUP_INFO,
            arguments = listOf(navArgument("cid") { type = NavType.StringType }),
        ) { entry ->
            val cid = Routes.decode(entry.arguments?.getString("cid").orEmpty())
            val groupCalendarTitle =
                androidx.compose.ui.res.stringResource(R.string.freebusy_group_title)
            GroupInfoScreen(
                deps = app,
                cid = cid,
                onBack = rememberOnceOnly(safePop),
                onLeftGroup = {
                    // Pop past the chat screen back to the tabs.
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
                onAddMembers = { navController.navigate(Routes.imAddMembers(it)) },
                // P8 群应用「群成员日历」→ 全员忙闲对比;srcCid 供创建后回发卡片。
                onOpenGroupCalendar = { memberIds ->
                    navController.navigate(
                        Routes.freeBusy(memberIds, groupCalendarTitle, cid),
                    )
                },
            )
        }

        composable(
            route = Routes.IM_DIRECT_SETTINGS,
            arguments = listOf(navArgument("cid") { type = NavType.StringType }),
        ) { entry ->
            val cid = Routes.decode(entry.arguments?.getString("cid").orEmpty())
            DirectChatSettingsScreen(
                deps = app,
                cid = cid,
                onBack = rememberOnceOnly(safePop),
                onCreateGroup = { peerUserId ->
                    // Open the group picker seeded with this direct chat's peer.
                    navController.navigate(Routes.imNewChat(peerUserId)) {
                        launchSingleTop = true
                    }
                },
                // P8 查看日历:我 + 对端 两列忙闲;srcCid 供创建后回发卡片。
                onViewCalendar = { peerUserId, peerName ->
                    navController.navigate(
                        Routes.freeBusy(listOf(peerUserId), peerName, cid),
                    )
                },
            )
        }

        composable(route = Routes.IM_SEARCH) {
            // 搜索统一 M2:app 层把 联系人/会议/文档 三个数据源以 provider
            // 注入(feature-im 不反向依赖 app 模块)。
            MessageSearchScreen(
                deps = app,
                onBack = rememberOnceOnly(safePop),
                onOpenChat = { cid, seq ->
                    navController.navigate(Routes.imChat(cid, seq))
                },
                searchContacts = { q ->
                    app.directoryRepository.searchMembers(q)
                        .getOrNull()?.members.orEmpty()
                        .take(8)
                        .map { m ->
                            com.we.meet.feature.im.ui.search.GlobalSearchContact(
                                userId = m.id,
                                name = m.fullName ?: m.shortName ?: m.email ?: m.id,
                                // 与 Web 全局搜索同款「职务 · 部门」,空则退 email。
                                subtitle = listOfNotNull(
                                    m.title?.takeIf { it.isNotBlank() },
                                    m.department?.name?.takeIf { it.isNotBlank() },
                                ).joinToString(" · ").ifBlank { m.email.orEmpty() }
                                    .takeIf { it.isNotBlank() },
                            )
                        }
                },
                searchMeetings = { q ->
                    val local = app.historyStore.entries.value
                        .filter { it.name.contains(q, ignoreCase = true) }
                        .take(8)
                        .map { e ->
                            com.we.meet.feature.im.ui.search.GlobalSearchMeeting(
                                roomId = e.roomId,
                                name = e.name,
                                timeMs = e.lastLeftAtMs ?: e.firstJoinedAtMs,
                            )
                        }
                    // 排期会议并入(Web fetchScheduledMeetings 口径:scheduled_at
                    // ≥今天且未关闭)。历史优先去重——已进过的会保持历史详情行为,
                    // 纯新增「没进过的排期会」;断网/失败静默退回纯本地。
                    val seen = local.map { it.roomId }.toSet()
                    val scheduled = runCatching {
                        val startOfToday = java.time.LocalDate.now()
                            .atStartOfDay(java.time.ZoneId.systemDefault())
                            .toInstant()
                        app.apiClient.roomApi.listMyRooms().results
                            .filter { r ->
                                r.id !in seen &&
                                    (r.name ?: "").contains(q, ignoreCase = true) &&
                                    r.closed_at == null &&
                                    !r.scheduled_at.isNullOrBlank()
                            }
                            .mapNotNull { r ->
                                val at = runCatching {
                                    java.time.OffsetDateTime.parse(r.scheduled_at)
                                        .toInstant()
                                }.getOrNull() ?: return@mapNotNull null
                                if (at < startOfToday) return@mapNotNull null
                                com.we.meet.feature.im.ui.search.GlobalSearchMeeting(
                                    roomId = r.id,
                                    name = r.name ?: "—",
                                    timeMs = at.toEpochMilli(),
                                    slug = r.slug,
                                    scheduled = true,
                                )
                            }
                            .sortedBy { it.timeMs }
                            .take(5)
                    }.getOrDefault(emptyList())
                    local + scheduled
                },
                searchDocs = { q ->
                    app.apiClient.searchApi.searchDocs(q).results
                        .filter { it.url.isNotBlank() }
                        .map { d ->
                            com.we.meet.feature.im.ui.search.GlobalSearchDoc(
                                title = d.title,
                                url = d.url,
                                updatedAt = d.updatedAt,
                            )
                        }
                },
                onOpenMeeting = { roomId ->
                    navController.navigate(Routes.historyDetail(roomId))
                },
                onOpenDoc = { url ->
                    navController.navigate(Routes.docsViewer(url))
                },
                // AI 日历引用:直开事件详情(EventDetailScreen 按 id 自加载)。
                onOpenEvent = { eventId ->
                    navController.navigate(Routes.eventDetail(eventId))
                },
                // 排期会议命中 → 进会预览(与首页「加入会议」同动线)。
                onOpenScheduled = { slug ->
                    navController.navigate(Routes.joinPreview(slug))
                },
                // P1-4 M3:AI 问答 SSE(鉴权 OkHttp,契约同 Web §D2)。
                askAi = { question ->
                    com.we.meet.data.api.globalAskStream(
                        okHttp = app.apiClient.okHttp,
                        baseUrl = com.we.meet.BuildConfig.WE_MEET_BASE_URL,
                        question = question,
                    )
                },
            )
        }

        composable(
            route = Routes.DOCS_VIEWER,
            arguments = listOf(navArgument("url") { type = NavType.StringType }),
        ) { entry ->
            val url = Routes.decode(entry.arguments?.getString("url").orEmpty())
            com.we.meet.ui.docs.DocsViewerScreen(
                url = url,
                onClose = rememberOnceOnly(safePop),
            )
        }

        composable(
            route = Routes.IM_NEW_CHAT,
            arguments = listOf(
                navArgument("peer") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val onceBack = rememberOnceOnly(safePop)
            val peer = entry.arguments?.getString("peer")?.let { Routes.decode(it) }
            NewChatScreen(
                deps = app,
                preselectUserId = peer,
                onChatReady = { cid ->
                    navController.navigate(Routes.imChat(cid)) {
                        // Replace the picker so back from the chat lands on the tabs.
                        popUpTo(Routes.IM_NEW_CHAT) { inclusive = true }
                    }
                },
                onCancel = onceBack,
            )
        }

        composable(
            route = Routes.IM_ADD_MEMBERS,
            arguments = listOf(navArgument("cid") { type = NavType.StringType }),
        ) { entry ->
            val cid = Routes.decode(entry.arguments?.getString("cid").orEmpty())
            val onceBack = rememberOnceOnly(safePop)
            AddMembersScreen(
                deps = app,
                cid = cid,
                onDone = onceBack,
                onCancel = onceBack,
            )
        }

        composable(
            route = Routes.MEMBER_DETAIL,
            arguments = listOf(navArgument("userId") { type = NavType.StringType }),
        ) { entry ->
            val userId = Routes.decode(entry.arguments?.getString("userId").orEmpty())
            MemberDetailScreen(
                userId = userId,
                onBack = rememberOnceOnly(safePop),
                onOpenChat = { cid -> navController.navigate(Routes.imChat(cid)) },
            )
        }

        // P8 日程提醒页:会话列表置顶入口点入。
        composable(Routes.REMINDERS) {
            com.we.meet.ui.calendar.reminder.ReminderScreen(
                onBack = rememberOnceOnly(safePop),
                onOpenSettings = { navController.navigate(Routes.CALENDAR_SETTINGS) },
                onEventClick = { id -> navController.navigate(Routes.eventDetail(id)) },
                onJoinSlug = { slug -> navController.navigate(Routes.joinPreview(slug)) },
            )
        }

        // P8 日历设置页:日历 tab 齿轮点入。
        composable(Routes.CALENDAR_SETTINGS) {
            com.we.meet.ui.calendar.CalendarSettingsScreen(
                onBack = rememberOnceOnly(safePop),
            )
        }

        composable(
            route = Routes.EVENT_DETAIL,
            arguments = listOf(navArgument("eventId") { type = NavType.StringType }),
        ) { entry ->
            val eventId = Routes.decode(entry.arguments?.getString("eventId").orEmpty())
            EventDetailScreen(
                eventId = eventId,
                onBack = rememberOnceOnly(safePop),
                onJoinSlug = { slug -> navController.navigate(Routes.joinPreview(slug)) },
                onEdit = { id, scope -> navController.navigate(Routes.editEvent(id, scope)) },
            )
        }

        composable(
            route = Routes.CREATE_EVENT,
            arguments = listOf(
                navArgument("epochDay") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument("eventId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("editScope") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                // P8 忙闲页预填(全带默认值,既有 createEvent/editEvent 调用零变化)。
                navArgument("startSec") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument("endSec") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument("attendeeIds") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("srcCid") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val srcCid = entry.arguments?.getString("srcCid")?.let { Routes.decode(it) }
            CreateEventScreen(
                initialEpochDay = entry.arguments?.getLong("epochDay")?.takeIf { it >= 0 },
                onClose = rememberOnceOnly(safePop),
                editEventId = entry.arguments?.getString("eventId")
                    ?.let { Routes.decode(it) },
                editScope = entry.arguments?.getString("editScope"),
                initialStartEpochSecond = entry.arguments?.getLong("startSec")
                    ?.takeIf { it > 0 },
                initialEndEpochSecond = entry.arguments?.getLong("endSec")
                    ?.takeIf { it > 0 },
                prefillAttendeeIds = entry.arguments?.getString("attendeeIds")
                    ?.let { Routes.decode(it) }
                    ?.split(',')
                    ?.filter { it.isNotBlank() }
                    .orEmpty(),
                // P8-M3:cid 随创建落库,后端在改期/加人/取消时向该会话推卡片。
                sourceConversationId = srcCid,
                // P8:IM 链路创建成功 → best-effort 回发 event-card 卡片(协议 v1,
                // 与 Web buildEventCardBody 一致);失败仅 log,不阻塞返回。
                onCreated = if (srcCid.isNullOrBlank()) null else { dto ->
                    val body = org.json.JSONObject().apply {
                        put("v", 1)
                        put("kind", "created")
                        put("event_id", dto.id)
                        put("title", dto.title)
                        put("start", dto.startAt)
                        put("end", dto.endAt)
                        put("all_day", dto.allDay)
                        put("attendee_count", dto.attendees.size)
                        put("organizer_name", dto.organizer?.fullName ?: "")
                    }.toString()
                    // 会话级 scope 发送:导航条目随 onClose pop 销毁,不能用它的 scope。
                    ImSession.get(app).sendMessageAsync(srcCid, body, "event-card")
                },
            )
        }

        // P8 忙闲对比页(单聊「查看日历」/群聊「群成员日历」共用)。
        composable(
            route = Routes.FREE_BUSY,
            arguments = listOf(
                navArgument("ids") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("title") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("srcCid") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val ids = Routes.decode(entry.arguments?.getString("ids").orEmpty())
                .split(',')
                .filter { it.isNotBlank() }
            val srcCid = entry.arguments?.getString("srcCid")?.let { Routes.decode(it) }
            val fallbackTitle = androidx.compose.ui.res.stringResource(R.string.freebusy_title)
            FreeBusyCompareScreen(
                userIds = ids,
                title = Routes.decode(entry.arguments?.getString("title").orEmpty())
                    .ifBlank { fallbackTitle },
                onBack = rememberOnceOnly(safePop),
                onCreateEvent = { startSec, endSec, attendeeIds ->
                    navController.navigate(
                        Routes.createEventPrefilled(startSec, endSec, attendeeIds, srcCid),
                    )
                },
            )
        }

        composable(Routes.QR_SCAN) {
            val onceClose = rememberOnceOnly(safePop)
            QrScanScreen(
                onDone = { _: QrScanResult ->
                    // Confirmed / Cancelled / Error all just return to home —
                    // the web side surfaces the confirmation, and on this
                    // device the toast/screenshot of state isn't worth a
                    // dedicated success page for v1.
                    onceClose()
                },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = rememberOnceOnly(safePop),
                // Sign-out redirects to login, popping HOME so back can't return
                // to the authed session.
                onSignedOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
                onOpenAccountSecurity = { navController.navigate(Routes.ACCOUNT_SECURITY) },
                // P8 设置收敛:模块设置从用户设置进,模块内齿轮是同页快捷入口。
                onOpenMeetingSettings = { navController.navigate(Routes.MEETING_SETTINGS) },
                onOpenCalendarSettings = { navController.navigate(Routes.CALENDAR_SETTINGS) },
            )
        }

        composable(Routes.MEETING_SETTINGS) {
            MeetingSettingsScreen(
                onBack = rememberOnceOnly(safePop),
            )
        }

        composable(Routes.ACCOUNT_SECURITY) {
            AccountSecurityScreen(
                onBack = rememberOnceOnly(safePop),
                // Deregister shares the same redirect-to-login path as sign-out,
                // popping HOME after the backend wipe succeeds.
                onAccountDeregistered = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.CREATE_PREVIEW,
            arguments = listOf(
                navArgument("name") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("audioOnly") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            val seedName = entry.arguments?.getString("name").orEmpty()
            PreviewScreen(
                mode = PreviewMode.Create,
                initialMeetingName = seedName.takeIf { it.isNotBlank() },
                audioOnly = entry.arguments?.getBoolean("audioOnly") == true,
                onEnterRoom = { roomId, url, token, name, slug, host, createdAtMs, isAdmin, mic, cam ->
                    navController.navigate(Routes.room(roomId, url, token, name, slug, host, createdAtMs, isAdmin, mic, cam)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onClose = rememberOnceOnly(safePop),
            )
        }

        composable(
            route = Routes.JOIN_PREVIEW,
            arguments = listOf(
                navArgument("slug") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val deepLinkSlug = entry.arguments?.getString("slug").orEmpty()
            PreviewScreen(
                mode = PreviewMode.Join,
                initialMeetingId = deepLinkSlug.takeIf { it.isNotBlank() },
                onEnterRoom = { roomId, url, token, name, slug, host, createdAtMs, isAdmin, mic, cam ->
                    navController.navigate(Routes.room(roomId, url, token, name, slug, host, createdAtMs, isAdmin, mic, cam)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onClose = rememberOnceOnly(safePop),
                onNeedsLobby = { idOrSlug, roomName, mic, cam ->
                    navController.navigate(Routes.waitingRoom(idOrSlug, roomName, mic, cam)) {
                        // Replace the Preview entry — once the user committed
                        // to "join" we don't want back-navigation to land
                        // them in the input page mid-wait.
                        popUpTo(Routes.JOIN_PREVIEW) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.WAITING_ROOM,
            arguments = listOf(
                navArgument("idOrSlug") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType },
                navArgument("mic") { type = NavType.BoolType },
                navArgument("cam") { type = NavType.BoolType },
            ),
        ) { entry ->
            val args = entry.arguments!!
            val idOrSlug = Routes.decode(args.getString("idOrSlug").orEmpty())
            val roomName = Routes.decode(args.getString("name").orEmpty())
            val mic = args.getBoolean("mic", true)
            val cam = args.getBoolean("cam", true)
            WaitingRoomScreen(
                idOrSlug = idOrSlug,
                roomName = roomName,
                mic = mic,
                cam = cam,
                onAccepted = { url, token, roomId ->
                    // Visitor-only path: no admin role, no historical
                    // server-side metadata. createdAtMs falls back to
                    // join-instant so the local history entry has a
                    // sensible timestamp rather than 1970.
                    navController.navigate(
                        Routes.room(
                            roomId = roomId,
                            url = url,
                            token = token,
                            name = roomName,
                            slug = idOrSlug,
                            host = null,
                            createdAtMs = System.currentTimeMillis(),
                            isAdmin = false,
                            mic = mic,
                            cam = cam,
                        )
                    ) { popUpTo(Routes.HOME) }
                },
                onCancel = rememberOnceOnly(safePop),
            )
        }

        composable(
            route = Routes.ROOM,
            arguments = listOf(
                navArgument("roomId") { type = NavType.StringType },
                navArgument("url") { type = NavType.StringType },
                navArgument("token") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType },
                navArgument("slug") { type = NavType.StringType },
                navArgument("host") { type = NavType.StringType },
                navArgument("createdAt") { type = NavType.LongType },
                navArgument("isAdmin") { type = NavType.BoolType },
                navArgument("mic") { type = NavType.BoolType },
                navArgument("cam") { type = NavType.BoolType },
                navArgument("peerUid") { type = NavType.StringType; defaultValue = "" },
                navArgument("peerName") { type = NavType.StringType; defaultValue = "" },
                navArgument("media") { type = NavType.StringType; defaultValue = "" },
                navArgument("meet") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val args = entry.arguments!!
            val decodedHost = Routes.decode(args.getString("host").orEmpty())
            RoomScreen(
                roomId = Routes.decode(args.getString("roomId").orEmpty()),
                livekitUrl = Routes.decode(args.getString("url").orEmpty()),
                livekitToken = Routes.decode(args.getString("token").orEmpty()),
                roomName = Routes.decode(args.getString("name").orEmpty()),
                roomSlug = Routes.decode(args.getString("slug").orEmpty()),
                host = decodedHost.takeIf { it.isNotBlank() },
                createdAtMs = args.getLong("createdAt"),
                isAdmin = args.getBoolean("isAdmin", false),
                initialMicEnabled = args.getBoolean("mic", true),
                initialCameraEnabled = args.getBoolean("cam", true),
                callPeerUid = Routes.decode(args.getString("peerUid").orEmpty()).takeIf { it.isNotBlank() },
                callPeerName = Routes.decode(args.getString("peerName").orEmpty()).takeIf { it.isNotBlank() },
                callMedia = Routes.decode(args.getString("media").orEmpty()).takeIf { it.isNotBlank() },
                callMeet = args.getBoolean("meet", false),
                onLeave = { hostEnded ->
                    if (hostEnded) hostEndedSheetVisible = true
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
            )
        }

        composable(
            route = Routes.HISTORY_DETAIL,
            arguments = listOf(
                navArgument("roomId") { type = NavType.StringType },
            ),
        ) { entry ->
            val args = entry.arguments!!
            com.we.meet.ui.history.HistoryDetailScreen(
                roomId = Routes.decode(args.getString("roomId").orEmpty()),
                onBack = rememberOnceOnly(safePop),
            )
        }
    }

    if (hostEndedSheetVisible) {
        HostEndedSheet(onDismiss = { hostEndedSheetVisible = false })
    }

    // Global session-expired handler. Any authed 401 caught by
    // SessionExpiredInterceptor flips this flag; we overlay a modal dialog
    // on top of whatever screen is visible and, on confirm, wipe the back
    // stack and navigate to Login.
    val sessionExpired by SessionState.expired.collectAsStateWithLifecycle()
    if (sessionExpired) {
        SessionExpiredDialog(
            onConfirm = {
                SessionState.reset()
                navController.navigate(Routes.LOGIN) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
}

/**
 * AI hub as a routed page (it lost its bottom tab to 日历/通讯录): the existing
 * AiHubScreen content under a back-navigable top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiHubRoute(onBack: () -> Unit, onOpenAssistantCall: () -> Unit) {
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(R.string.tab_ai)) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(modifier = Modifier.padding(padding)) {
            com.we.meet.ui.ai.AiHubScreen(onOpenAssistantCall = onOpenAssistantCall)
        }
    }
}

/**
 * Wraps a back/cancel callback so a double-tap fires it exactly once.
 *
 * Compose's nav animation keeps the outgoing destination's composable
 * alive briefly, so a fast second tap on the back arrow can re-enter
 * the same onClick lambda and trigger an extra `popBackStack()` — that
 * eats the layer below (mid-stack) or empties the back stack (blank
 * screen). Putting the guard inside each destination's `composable {}`
 * scope means the flag resets every time the destination is re-entered.
 */
@Composable
private fun rememberOnceOnly(action: () -> Unit): () -> Unit {
    var fired by remember { mutableStateOf(false) }
    return {
        if (!fired) {
            fired = true
            action()
        }
    }
}

@Composable
private fun SessionExpiredDialog(onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* non-dismissable — user must tap re-login */ },
        title = { Text(stringResource(R.string.session_expired_title)) },
        text = { Text(stringResource(R.string.session_expired_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.session_expired_action))
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    )
}

/**
 * Bottom sheet shown on Home after the server disconnected us because the
 * host ended the meeting. Auto-dismisses after 5 s; the button label shows
 * a live countdown until it does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostEndedSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var remaining by remember { mutableIntStateOf(5) }

    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1000L)
            remaining--
        }
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding)) {
            Text(
                text = stringResource(R.string.room_host_ended_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            )
            HorizontalDivider()
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.ButtonHeight),
            ) {
                Text(
                    text = if (remaining > 0)
                        stringResource(R.string.room_host_ended_ack_countdown, remaining)
                    else stringResource(R.string.room_host_ended_ack),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
