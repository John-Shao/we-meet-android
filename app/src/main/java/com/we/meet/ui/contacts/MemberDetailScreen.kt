package com.we.meet.ui.contacts

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.we.meet.core.directory.ui.avatarCacheKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.data.MemberDto
import com.we.meet.core.directory.ui.MemberAvatar
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MemberDetailUiState(
    val member: MemberDto? = null,
    val loading: Boolean = true,
    val creatingChat: Boolean = false,
    val error: Boolean = false,
    val chatError: Boolean = false,
)

class MemberDetailViewModel(
    app: Application,
    private val userId: String,
) : AndroidViewModel(app) {

    private val weMeetApp = app as WeMeetApp

    private val _ui = MutableStateFlow(MemberDetailUiState())
    val ui: StateFlow<MemberDetailUiState> = _ui.asStateFlow()

    /** Emits the cid once the direct conversation is ready. */
    private val _chatReady = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val chatReady: SharedFlow<String> = _chatReady.asSharedFlow()

    init {
        viewModelScope.launch {
            weMeetApp.directoryRepository.getMember(userId)
                .onSuccess { m -> _ui.update { it.copy(member = m, loading = false) } }
                .onFailure { _ui.update { it.copy(loading = false, error = true) } }
        }
    }

    fun startChat() {
        if (_ui.value.creatingChat) return
        _ui.update { it.copy(creatingChat = true, chatError = false) }
        viewModelScope.launch {
            runCatching {
                weMeetApp.apiClient.imBridgeApi
                    .createDirectConversation(mapOf("peer_user_id" to userId))
            }
                .onSuccess { res ->
                    _ui.update { it.copy(creatingChat = false) }
                    _chatReady.tryEmit(res.cid)
                }
                .onFailure {
                    _ui.update { it.copy(creatingChat = false, chatError = true) }
                }
        }
    }

    companion object {
        fun factory(app: Application, userId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { MemberDetailViewModel(app, userId) }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberDetailScreen(
    userId: String,
    onBack: () -> Unit,
    onOpenChat: (cid: String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val vm: MemberDetailViewModel = viewModel(
        key = "member-$userId",
        factory = MemberDetailViewModel.factory(app, userId),
    )
    val ui by vm.ui.collectAsStateWithLifecycle()

    LaunchedEffect(vm) {
        vm.chatReady.collect { cid -> onOpenChat(cid) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.member_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                ui.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                ui.error || ui.member == null -> Text(
                    text = stringResource(R.string.contacts_load_error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> MemberDetailBody(
                    member = ui.member!!,
                    creatingChat = ui.creatingChat,
                    chatError = ui.chatError,
                    onStartChat = { vm.startChat() },
                )
            }
        }
    }
}

@Composable
private fun MemberDetailBody(
    member: MemberDto,
    creatingChat: Boolean,
    chatError: Boolean,
    onStartChat: () -> Unit,
) {
    // Only a real photo is worth enlarging — the initials fallback isn't, so the
    // tap-to-zoom affordance is gated on the member actually having an avatar.
    val hasAvatar = !member.avatarUrl.isNullOrBlank()
    var showAvatar by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        MemberAvatar(
            name = member.displayName,
            url = member.avatarUrl,
            cacheKey = "avatar:${member.id}",
            size = 88.dp,
            modifier = if (hasAvatar) {
                Modifier
                    .clip(RoundedCornerShape(88.dp * 0.2f))
                    .clickable { showAvatar = true }
            } else Modifier,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = member.displayName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(28.dp))

        InfoRow(stringResource(R.string.member_label_department), member.department?.name)
        InfoRow(stringResource(R.string.member_label_title), member.title)
        InfoRow(stringResource(R.string.member_label_email), member.email)

        Spacer(Modifier.height(32.dp))
        if (!member.isSelf) {
            Button(
                onClick = onStartChat,
                enabled = !creatingChat,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                Spacer(Modifier.padding(start = 8.dp))
                Text(stringResource(R.string.member_action_message))
            }
            if (chatError) {
                Text(
                    text = stringResource(R.string.member_message_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    if (showAvatar && hasAvatar) {
        AvatarViewerDialog(
            name = member.displayName,
            url = member.avatarUrl!!,
            cacheKey = "avatar:${member.id}",
            onDismiss = { showAvatar = false },
        )
    }
}

/**
 * Full-screen avatar viewer: the tapped photo enlarged on a dark scrim, dismissed
 * by tapping anywhere or system Back. Reuses [avatarCacheKey] so it renders from
 * the same Coil cache entry the thumbnail already fetched (presigned avatar URLs
 * rotate their signature, but the path-based key is stable).
 */
@Composable
private fun AvatarViewerDialog(
    name: String,
    url: String,
    cacheKey: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val key = avatarCacheKey(url, cacheKey)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .memoryCacheKey(key)
                    .diskCacheKey(key)
                    .build(),
                contentDescription = name,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
