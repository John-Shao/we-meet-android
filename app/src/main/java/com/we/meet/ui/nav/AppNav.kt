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
import com.we.meet.data.auth.SessionState
import com.we.meet.feature.assistant.aicall.ui.AssistantCallScreen
import com.we.meet.feature.im.ui.chat.ChatScreen
import com.we.meet.feature.im.ui.group.GroupInfoScreen
import com.we.meet.feature.im.ui.newchat.AddMembersScreen
import com.we.meet.feature.im.ui.newchat.NewChatScreen
import com.we.meet.ui.calendar.CreateEventScreen
import com.we.meet.ui.calendar.EventDetailScreen
import com.we.meet.ui.contacts.MemberDetailScreen
import com.we.meet.ui.login.LoginScreen
import com.we.meet.ui.main.MainTabScreen
import com.we.meet.ui.preview.PreviewMode
import com.we.meet.ui.preview.PreviewScreen
import com.we.meet.ui.qrscan.QrScanResult
import com.we.meet.ui.qrscan.QrScanScreen
import com.we.meet.ui.room.RoomScreen
import com.we.meet.ui.settings.SettingsScreen
import com.we.meet.ui.waiting.WaitingRoomScreen
import kotlinx.coroutines.delay
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val CREATE_PREVIEW = "create_preview"
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

    // IM — full-screen chat routes above the tab scaffold.
    private const val IM_CHAT_BASE = "im_chat"
    const val IM_CHAT = "$IM_CHAT_BASE/{cid}"
    private const val IM_GROUP_INFO_BASE = "im_group_info"
    const val IM_GROUP_INFO = "$IM_GROUP_INFO_BASE/{cid}"
    const val IM_NEW_CHAT = "im_new_chat"
    private const val IM_ADD_MEMBERS_BASE = "im_add_members"
    const val IM_ADD_MEMBERS = "$IM_ADD_MEMBERS_BASE/{cid}"

    // Contacts / Calendar detail routes.
    private const val MEMBER_DETAIL_BASE = "member_detail"
    const val MEMBER_DETAIL = "$MEMBER_DETAIL_BASE/{userId}"
    private const val EVENT_DETAIL_BASE = "event_detail"
    const val EVENT_DETAIL = "$EVENT_DETAIL_BASE/{eventId}"
    const val CREATE_EVENT = "create_event?epochDay={epochDay}"

    fun imChat(cid: String): String =
        "$IM_CHAT_BASE/${URLEncoder.encode(cid, StandardCharsets.UTF_8.name())}"

    fun imGroupInfo(cid: String): String =
        "$IM_GROUP_INFO_BASE/${URLEncoder.encode(cid, StandardCharsets.UTF_8.name())}"

    fun imAddMembers(cid: String): String =
        "$IM_ADD_MEMBERS_BASE/${URLEncoder.encode(cid, StandardCharsets.UTF_8.name())}"

    fun memberDetail(userId: String): String =
        "$MEMBER_DETAIL_BASE/${URLEncoder.encode(userId, StandardCharsets.UTF_8.name())}"

    fun eventDetail(eventId: String): String =
        "$EVENT_DETAIL_BASE/${URLEncoder.encode(eventId, StandardCharsets.UTF_8.name())}"

    fun createEvent(epochDay: Long): String = "create_event?epochDay=$epochDay"

    private const val WAITING_ROOM_BASE = "waiting_room"
    const val WAITING_ROOM = "$WAITING_ROOM_BASE/{idOrSlug}/{name}/{mic}/{cam}"

    fun waitingRoom(idOrSlug: String, name: String, mic: Boolean, cam: Boolean): String {
        fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8.name())
        return "$WAITING_ROOM_BASE/${enc(idOrSlug)}/${enc(name)}/$mic/$cam"
    }

    /** Build a JoinPreview route URL, optionally seeding the meeting-id input. */
    fun joinPreview(slug: String? = null): String {
        val s = slug.orEmpty()
        return if (s.isBlank()) "join_preview?slug=" else "join_preview?slug=$s"
    }

    private const val ROOM_BASE = "room"
    const val ROOM = "$ROOM_BASE/{roomId}/{url}/{token}/{name}/{slug}/{host}/{createdAt}/{isAdmin}/{mic}/{cam}"

    private const val HISTORY_BASE = "history_detail"
    const val HISTORY_DETAIL = "$HISTORY_BASE/{roomId}"

    fun room(
        roomId: String, url: String, token: String, name: String, slug: String,
        host: String?, createdAtMs: Long, isAdmin: Boolean, mic: Boolean, cam: Boolean,
    ): String {
        fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8.name())
        // Empty host serialises as "" which decode() round-trips cleanly; the
        // receiver treats blank as null.
        return "$ROOM_BASE/${enc(roomId)}/${enc(url)}/${enc(token)}/${enc(name)}/${enc(slug)}/${enc(host.orEmpty())}/$createdAtMs/$isAdmin/$mic/$cam"
    }

    fun historyDetail(roomId: String): String {
        val enc = URLEncoder.encode(roomId, StandardCharsets.UTF_8.name())
        return "$HISTORY_BASE/$enc"
    }

    fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}

@Composable
fun AppNav() {
    val context = LocalContext.current
    val app = context.applicationContext as WeMeetApp
    val navController = rememberNavController()

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

    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.LOGIN) {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            MainTabScreen(
                onCreateMeeting = { navController.navigate(Routes.CREATE_PREVIEW) },
                onJoinMeeting = { navController.navigate(Routes.joinPreview()) },
                onJoinSlug = { slug -> navController.navigate(Routes.joinPreview(slug)) },
                onScanQrCode = { navController.navigate(Routes.QR_SCAN) },
                onHistoryClick = { roomId ->
                    navController.navigate(Routes.historyDetail(roomId))
                },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onOpenAiHub = { navController.navigate(Routes.AI_HUB) },
                onSignedOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
                onOpenChat = { cid -> navController.navigate(Routes.imChat(cid)) },
                onNewChat = { navController.navigate(Routes.IM_NEW_CHAT) },
                onMemberClick = { userId -> navController.navigate(Routes.memberDetail(userId)) },
                onEventClick = { eventId -> navController.navigate(Routes.eventDetail(eventId)) },
                onCreateEvent = { epochDay -> navController.navigate(Routes.createEvent(epochDay)) },
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

        composable(
            route = Routes.IM_CHAT,
            arguments = listOf(navArgument("cid") { type = NavType.StringType }),
        ) { entry ->
            val cid = Routes.decode(entry.arguments?.getString("cid").orEmpty())
            ChatScreen(
                deps = app,
                cid = cid,
                onBack = rememberOnceOnly(safePop),
                onOpenInfo = { navController.navigate(Routes.imGroupInfo(it)) },
            )
        }

        composable(
            route = Routes.IM_GROUP_INFO,
            arguments = listOf(navArgument("cid") { type = NavType.StringType }),
        ) { entry ->
            val cid = Routes.decode(entry.arguments?.getString("cid").orEmpty())
            GroupInfoScreen(
                deps = app,
                cid = cid,
                onBack = rememberOnceOnly(safePop),
                onLeftGroup = {
                    // Pop past the chat screen back to the tabs.
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
                onAddMembers = { navController.navigate(Routes.imAddMembers(it)) },
            )
        }

        composable(Routes.IM_NEW_CHAT) {
            val onceBack = rememberOnceOnly(safePop)
            NewChatScreen(
                deps = app,
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

        composable(
            route = Routes.EVENT_DETAIL,
            arguments = listOf(navArgument("eventId") { type = NavType.StringType }),
        ) { entry ->
            val eventId = Routes.decode(entry.arguments?.getString("eventId").orEmpty())
            EventDetailScreen(
                eventId = eventId,
                onBack = rememberOnceOnly(safePop),
                onJoinSlug = { slug -> navController.navigate(Routes.joinPreview(slug)) },
            )
        }

        composable(
            route = Routes.CREATE_EVENT,
            arguments = listOf(
                navArgument("epochDay") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) { entry ->
            CreateEventScreen(
                initialEpochDay = entry.arguments?.getLong("epochDay")?.takeIf { it >= 0 },
                onClose = rememberOnceOnly(safePop),
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
                // Deregister flow lives in Settings → Account. After the
                // backend wipe succeeds we share the same redirect-to-login
                // path the regular Sign-out button uses.
                onAccountDeregistered = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.CREATE_PREVIEW) {
            PreviewScreen(
                mode = PreviewMode.Create,
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
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
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
                    .height(52.dp),
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
