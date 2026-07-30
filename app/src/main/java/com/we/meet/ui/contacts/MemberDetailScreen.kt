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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.we.meet.util.dialNumber
import com.we.meet.core.directory.data.MemberDto
import com.we.meet.core.directory.data.StarredContacts
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
    /** Full phone after 显示 (P3). Null = still masked; "" = revealed-but-empty. */
    val revealedPhone: String? = null,
    val revealing: Boolean = false,
    /** 显示 request failed (vs. genuinely empty) — one-shot, drives a Toast. */
    val revealError: Boolean = false,
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

    /**
     * P3: reveal the member's full phone. Revealing another member's number
     * notifies them (server-side). Idempotent per screen — once revealed it
     * stays revealed. Returns via state; callers read [MemberDetailUiState.revealedPhone].
     */
    fun revealPhone() {
        val st = _ui.value
        if (st.revealing || st.revealedPhone != null) return
        _ui.update { it.copy(revealing = true, revealError = false) }
        viewModelScope.launch {
            // Distinguish failure from a genuinely empty result: on failure keep
            // revealedPhone null so the 显示 button stays (and the owner isn't
            // wrongly presented as having no number), and flag an error toast.
            weMeetApp.directoryRepository.revealPhone(userId)
                .onSuccess { phone -> _ui.update { it.copy(revealing = false, revealedPhone = phone) } }
                .onFailure { _ui.update { it.copy(revealing = false, revealError = true) } }
        }
    }

    fun consumeRevealError() = _ui.update { it.copy(revealError = false) }

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
    val context = LocalContext.current
    // 星标状态读共享集合而非 member.isStarred 快照:在别处改了也会立刻反映。
    val starredIds by StarredContacts.ids.collectAsStateWithLifecycle()

    LaunchedEffect(vm) {
        vm.chatReady.collect { cid -> onOpenChat(cid) }
    }

    // 卡片刚拉回来的 is_starred 是服务端最新值(共享集合可能还没同步到别端的改动),
    // 拿它校准一次,免得开关显示成过期状态。
    LaunchedEffect(ui.member?.id, ui.member?.isStarred) {
        val member = ui.member ?: return@LaunchedEffect
        StarredContacts.reconcile(member.id, member.isStarred)
    }

    LaunchedEffect(ui.revealError) {
        if (ui.revealError) {
            android.widget.Toast.makeText(
                context, R.string.member_reveal_failed, android.widget.Toast.LENGTH_SHORT,
            ).show()
            vm.consumeRevealError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.member_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
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
                    revealedPhone = ui.revealedPhone,
                    revealing = ui.revealing,
                    onRevealPhone = { vm.revealPhone() },
                    starred = userId in starredIds,
                    onToggleStarred = { next ->
                        StarredContacts.setStarred(userId, next) {
                            android.widget.Toast.makeText(
                                context,
                                R.string.starred_update_failed,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
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
    revealedPhone: String?,
    revealing: Boolean,
    onRevealPhone: () -> Unit,
    starred: Boolean,
    onToggleStarred: (Boolean) -> Unit,
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
        PhoneRow(
            masked = member.phone,
            isSelf = member.isSelf,
            revealedPhone = revealedPhone,
            revealing = revealing,
            onReveal = onRevealPhone,
        )

        if (!member.isSelf) {
            StarredRow(starred = starred, onToggle = onToggleStarred)
        }

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

/**
 * 「设为星标联系人」+ 开关,下面一句说明星标到底有什么用。
 *
 * 直接摊在详情页里(不像飞书那样再进一层「设置」子页):we-meet 没有那页的
 * 备注与描述 / 分享 / 举报,单为一个开关多跳一层不值。说明也不做成跳转 ——
 * 通知设置在「设置 › 通知」里,从联系人详情横跳过去反而绕。
 */
@Composable
private fun StarredRow(
    starred: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.starred_toggle),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(checked = starred, onCheckedChange = onToggle)
        }
        Text(
            text = stringResource(R.string.starred_toggle_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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

/**
 * Phone row (P3): shows the masked number with a 「显示」 action; tapping it
 * reveals the full number (server-side, which notifies the owner). Once
 * revealed, a 「拨打」 action opens the system dialer. Own card / no number →
 * plain masked value (self is already full) or the row is hidden.
 */
@Composable
private fun PhoneRow(
    masked: String?,
    isSelf: Boolean,
    revealedPhone: String?,
    revealing: Boolean,
    onReveal: () -> Unit,
) {
    if (masked.isNullOrBlank()) return
    val context = LocalContext.current
    // Self card carries the full number already; others start masked.
    val shown = revealedPhone?.takeIf { it.isNotBlank() } ?: masked
    val isRevealed = isSelf || revealedPhone != null
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.member_label_phone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = shown, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(start = 12.dp))
                when {
                    revealing -> CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(start = 4.dp).height(16.dp),
                    )
                    // Revealed-but-empty (owner had no number after all).
                    revealedPhone != null && revealedPhone.isBlank() -> Unit
                    isRevealed -> TextButton(onClick = { dialNumber(context, shown) }) {
                        Text(stringResource(R.string.member_phone_call))
                    }
                    else -> TextButton(onClick = onReveal) {
                        Text(stringResource(R.string.member_phone_reveal))
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
