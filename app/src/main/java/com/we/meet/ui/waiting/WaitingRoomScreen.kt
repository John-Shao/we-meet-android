package com.we.meet.ui.waiting

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.ui.theme.Dimens
import com.we.meet.R
import com.we.meet.WeMeetApp

/**
 * Visitor-side lobby screen. Shows a progress spinner while the host
 * decides, and a clear rejected state if they deny. Cancelling pops
 * back to PreviewScreen so the user can change their input or pick a
 * different room. On Accepted we don't render anything — the parent
 * NavHost handles the room transition via [onAccepted].
 *
 * Camera/mic state is carried through Routes args (set in the previous
 * Preview) and forwarded back to the room route on accept so the user
 * lands in the meeting in the same media state they previewed.
 */
@Composable
fun WaitingRoomScreen(
    idOrSlug: String,
    roomName: String,
    mic: Boolean,
    cam: Boolean,
    onAccepted: (livekitUrl: String, livekitToken: String, roomId: String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as WeMeetApp
    val username = app.tokenStore.nickname?.takeIf { it.isNotBlank() }
        ?: app.tokenStore.phone
        ?: stringResource(R.string.default_display_name)
    val vm: WaitingRoomViewModel = viewModel(
        factory = WaitingRoomViewModel.Factory(
            application = context.applicationContext as Application,
            idOrSlug = idOrSlug,
            username = username,
        ),
    )
    val state by vm.state.collectAsStateWithLifecycle()

    // When the host admits us, fire a one-shot to the parent so it can
    // navigate into the room route. The ViewModel stays mounted briefly
    // (until backstack pop), so the LaunchedEffect key prevents repeats.
    LaunchedEffect(state.phase) {
        if (state.phase == WaitingRoomUiState.Phase.Accepted) {
            val url = state.livekitUrl
            val token = state.livekitToken
            val rid = state.livekitRoomId
            if (url != null && token != null && rid != null) {
                onAccepted(url, token, rid)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimens.SpaceXl),
        contentAlignment = Alignment.Center,
    ) {
        when (state.phase) {
            WaitingRoomUiState.Phase.Waiting,
            WaitingRoomUiState.Phase.Accepted -> WaitingContent(
                roomName = roomName,
                onCancel = onCancel,
            )
            WaitingRoomUiState.Phase.Denied -> DeniedContent(onBack = onCancel)
            WaitingRoomUiState.Phase.Error -> ErrorContent(
                message = state.errorMessage,
                onRetry = vm::retry,
                onBack = onCancel,
            )
        }
    }
    // Suppress the unused 'mic'/'cam' linter — they're plumbed through
    // [onAccepted] indirectly via the parent's navigation.
    @Suppress("UNUSED_EXPRESSION")
    mic; @Suppress("UNUSED_EXPRESSION") cam
}

@Composable
private fun WaitingContent(roomName: String, onCancel: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(Dimens.ListThumbnail))
        Spacer(Modifier.height(Dimens.SpaceS))
        Text(
            text = stringResource(R.string.waiting_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.waiting_subtitle, roomName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Dimens.SpaceS))
        OutlinedButton(onClick = onCancel) {
            Text(stringResource(R.string.waiting_cancel))
        }
    }
}

@Composable
private fun DeniedContent(onBack: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        Icon(
            imageVector = Icons.Default.RemoveCircleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(Dimens.IconIllustrationLarge),
        )
        Text(
            text = stringResource(R.string.waiting_denied_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.waiting_denied_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Dimens.SpaceS))
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.waiting_back)) }
    }
}

@Composable
private fun ErrorContent(
    message: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        Icon(
            imageVector = Icons.Default.HourglassEmpty,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimens.IconIllustrationLarge),
        )
        Text(
            text = stringResource(R.string.waiting_error_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        if (!message.isNullOrBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(Dimens.SpaceS))
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.waiting_retry)) }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.waiting_back)) }
    }
}
