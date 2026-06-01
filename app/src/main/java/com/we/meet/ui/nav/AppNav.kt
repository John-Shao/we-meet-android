package com.we.meet.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import com.we.meet.ui.login.LoginScreen
import com.we.meet.ui.main.MainTabScreen
import com.we.meet.ui.preview.PreviewMode
import com.we.meet.ui.preview.PreviewScreen
import com.we.meet.ui.qrscan.QrScanResult
import com.we.meet.ui.qrscan.QrScanScreen
import com.we.meet.ui.room.RoomScreen
import com.we.meet.ui.settings.SettingsScreen
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
                onScanQrCode = { navController.navigate(Routes.QR_SCAN) },
                onHistoryClick = { roomId ->
                    navController.navigate(Routes.historyDetail(roomId))
                },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onOpenAssistantCall = { navController.navigate(Routes.ASSISTANT_CALL) },
                onSignedOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.ASSISTANT_CALL) {
            AssistantCallScreen(
                deps = app,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.QR_SCAN) {
            QrScanScreen(
                onDone = { _: QrScanResult ->
                    // Confirmed / Cancelled / Error all just return to home —
                    // the web side surfaces the confirmation, and on this
                    // device the toast/screenshot of state isn't worth a
                    // dedicated success page for v1.
                    navController.popBackStack()
                },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.CREATE_PREVIEW) {
            PreviewScreen(
                mode = PreviewMode.Create,
                onEnterRoom = { roomId, url, token, name, slug, host, createdAtMs, isAdmin, mic, cam ->
                    navController.navigate(Routes.room(roomId, url, token, name, slug, host, createdAtMs, isAdmin, mic, cam)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onClose = { navController.popBackStack() },
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
                onClose = { navController.popBackStack() },
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
                onBack = { navController.popBackStack() },
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
