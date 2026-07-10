package com.we.meet.feature.im.ui.call

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.R
import com.we.meet.feature.im.call.CallEndReason
import com.we.meet.feature.im.call.CallInfo
import com.we.meet.feature.im.call.CallState
import com.we.meet.feature.im.data.GroupTile
import com.we.meet.feature.im.ui.common.GroupAvatar

/**
 * P1 一对一通话 — the single full-screen call route. Renders per CallController
 * state (Outgoing / Incoming / Connecting); Idle & Ended pop back (with a
 * reason toast for Ended). The screen is state-driven so an Incoming →
 * Connecting transition doesn't need a navigation hop.
 *
 * 回铃音 is P2 (拍板 #3) — the outgoing page is silent by design. The callee
 * side plays the system ringtone + vibrates while Incoming is on screen.
 */
@Composable
fun CallScreen(
    deps: ImDeps,
    onExit: () -> Unit,
) {
    val session = ImSession.get(deps)
    val controller = session.calls
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Pop when the call machine settles. Ended toasts its reason first.
    LaunchedEffect(state) {
        when (val s = state) {
            is CallState.Idle -> onExit()
            is CallState.Ended -> {
                endReasonText(context, s.reason)?.let {
                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                }
                onExit()
            }
            else -> Unit
        }
    }

    when (val s = state) {
        is CallState.Outgoing -> OutgoingContent(
            session = session,
            info = s.info,
            waitingAnswer = s.waitingAnswer,
            onCancel = controller::cancel,
        )
        is CallState.Incoming -> IncomingContent(
            session = session,
            info = s.info,
            onAccept = controller::accept,
            onReject = controller::reject,
        )
        is CallState.Connecting -> ConnectingContent(session = session, info = s.info)
        // Idle/Ended render nothing — the LaunchedEffect above is popping.
        else -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
    }
}

// ---- per-state content ----

@Composable
private fun OutgoingContent(
    session: ImSession,
    info: CallInfo,
    waitingAnswer: Boolean,
    onCancel: () -> Unit,
) {
    CallScaffold(
        session = session,
        peerUid = info.peerUid,
        fallbackName = info.peerName,
        statusText = when {
            waitingAnswer -> stringResource(R.string.im_call_waiting_answer)
            info.media == "video" -> stringResource(R.string.im_call_outgoing_video)
            else -> stringResource(R.string.im_call_outgoing_voice)
        },
        onBack = onCancel,
    ) {
        RoundActionButton(
            icon = Icons.Filled.CallEnd,
            label = stringResource(R.string.im_action_cancel),
            background = HangupRed,
            onClick = onCancel,
        )
    }
}

@Composable
private fun IncomingContent(
    session: ImSession,
    info: CallInfo,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    RingtoneAndVibration()
    CallScaffold(
        session = session,
        peerUid = info.peerUid,
        fallbackName = info.peerName,
        statusText = stringResource(
            if (info.media == "video") R.string.im_call_incoming_video
            else R.string.im_call_incoming_voice
        ),
        onBack = null, // back = reject, handled by the button row; no top-left exit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RoundActionButton(
                icon = Icons.Filled.CallEnd,
                label = stringResource(R.string.im_call_decline),
                background = HangupRed,
                onClick = onReject,
            )
            RoundActionButton(
                icon = Icons.Filled.Call,
                label = stringResource(R.string.im_call_accept),
                background = AcceptGreen,
                onClick = onAccept,
            )
        }
    }
}

@Composable
private fun ConnectingContent(session: ImSession, info: CallInfo) {
    CallScaffold(
        session = session,
        peerUid = info.peerUid,
        fallbackName = info.peerName,
        statusText = stringResource(R.string.im_call_connecting),
        onBack = null,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
    }
}

// ---- shared scaffold: avatar + name + status on top, action slot at bottom ----

@Composable
private fun CallScaffold(
    session: ImSession,
    peerUid: String,
    fallbackName: String,
    statusText: String,
    onBack: (() -> Unit)?,
    bottomBar: @Composable () -> Unit,
) {
    // Resolve the peer's display name/avatar; recompose when the directory fills in.
    LaunchedEffect(peerUid) { session.userDirectory.requestResolve(listOf(peerUid)) }
    val directoryVersion by session.userDirectory.version.collectAsStateWithLifecycle()
    val peer = remember(peerUid, directoryVersion) { session.userDirectory.get(peerUid) }
    val displayName = peer?.displayName?.takeIf { it.isNotBlank() } ?: fallbackName

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(4.dp)
                    .align(Alignment.TopStart),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GroupAvatar(
                tiles = listOf(GroupTile(peerUid, displayName, peer?.avatarUrl)),
                size = 112.dp,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 48.dp),
        ) {
            bottomBar()
        }
    }
}

@Composable
private fun RoundActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    background: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = background,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.size(64.dp),
            onClick = onClick,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** System ringtone + waveform vibration for as long as the composable is mounted. */
@Composable
private fun RingtoneAndVibration() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val ringtone: Ringtone? = runCatching {
            RingtoneManager.getRingtone(
                context,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            )?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }.getOrNull()

        val vibrator: Vibrator? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull()
        runCatching {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0),
            )
        }

        onDispose {
            runCatching { ringtone?.stop() }
            runCatching { vibrator?.cancel() }
        }
    }
}

private val HangupRed = Color(0xFFE5484D)
private val AcceptGreen = Color(0xFF30A46C)

private fun endReasonText(context: Context, reason: CallEndReason): String? = when (reason) {
    CallEndReason.DECLINED -> context.getString(R.string.im_call_end_declined)
    CallEndReason.BUSY -> context.getString(R.string.im_call_end_busy)
    CallEndReason.TIMEOUT -> context.getString(R.string.im_call_end_timeout)
    CallEndReason.UNREACHABLE -> context.getString(R.string.im_call_end_unreachable)
    CallEndReason.CANCELED_REMOTE -> context.getString(R.string.im_call_end_canceled_remote)
    CallEndReason.ANSWERED_ELSEWHERE -> context.getString(R.string.im_call_end_answered_elsewhere)
    CallEndReason.FAILED -> context.getString(R.string.im_call_end_failed)
    // Locally-initiated endings need no toast — the user did it themselves.
    CallEndReason.CANCELED, CallEndReason.DECLINED_LOCAL, CallEndReason.HUNG_UP -> null
}
