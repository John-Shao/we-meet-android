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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.theme.Dimens
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
import com.we.meet.core.directory.data.ContactPrefs
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
        loadMember()
    }

    fun retry() = loadMember()

    private fun loadMember() {
        _ui.update { it.copy(loading = true, error = false) }
        viewModelScope.launch {
            weMeetApp.directoryRepository.getMember(userId)
                .onSuccess { m ->
                    _ui.update { it.copy(member = m, loading = false, error = false) }
                }
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
    // 两个 flag 都读共享集合而非卡片快照:在别处改了也会立刻反映。
    val starredIds by ContactPrefs.starredIds.collectAsStateWithLifecycle()
    val alertIds by ContactPrefs.specialAlertIds.collectAsStateWithLifecycle()
    val starredUpdatingIds by ContactPrefs.starredUpdatingIds.collectAsStateWithLifecycle()
    val alertUpdatingIds by ContactPrefs.specialAlertUpdatingIds.collectAsStateWithLifecycle()

    LaunchedEffect(vm) {
        vm.chatReady.collect { cid -> onOpenChat(cid) }
    }

    // 卡片刚拉回来的两个 flag 是服务端最新值(共享集合可能还没同步到别端的改动),
    // 拿它校准一次,免得开关显示成过期状态。
    LaunchedEffect(ui.member?.id, ui.member?.isStarred, ui.member?.specialAlert) {
        val member = ui.member ?: return@LaunchedEffect
        ContactPrefs.reconcile(member.id, member.isStarred, member.specialAlert)
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
            WeMeetTopBar(
                title = stringResource(R.string.member_detail_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                ui.loading -> WeMeetLoading()
                ui.error || ui.member == null -> WeMeetErrorState(
                    onRetry = vm::retry,
                    message = stringResource(R.string.contacts_load_error),
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
                    starredUpdating = userId in starredUpdatingIds,
                    onToggleStarred = { next ->
                        ContactPrefs.setStarred(userId, next) {
                            android.widget.Toast.makeText(
                                context,
                                R.string.starred_update_failed,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    specialAlert = userId in alertIds,
                    specialAlertUpdating = userId in alertUpdatingIds,
                    onToggleSpecialAlert = { next ->
                        ContactPrefs.setSpecialAlert(userId, next) {
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
    starredUpdating: Boolean,
    onToggleStarred: (Boolean) -> Unit,
    specialAlert: Boolean,
    specialAlertUpdating: Boolean,
    onToggleSpecialAlert: (Boolean) -> Unit,
) {
    // Only a real photo is worth enlarging — the initials fallback isn't, so the
    // tap-to-zoom affordance is gated on the member actually having an avatar.
    val hasAvatar = !member.avatarUrl.isNullOrBlank()
    var showAvatar by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.SpaceXl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Dimens.SpaceXxl))
        MemberAvatar(
            name = member.displayName,
            url = member.avatarUrl,
            cacheKey = "avatar:${member.id}",
            size = Dimens.AvatarXl,
            modifier = if (hasAvatar) {
                Modifier
                    .clip(RoundedCornerShape(Dimens.AvatarXl * 0.2f))
                    .clickable { showAvatar = true }
            } else Modifier,
        )
        Spacer(Modifier.height(Dimens.SpaceL))
        Text(
            text = member.displayName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(Dimens.SpaceXxl))

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
            ContactPrefRows(
                starred = starred,
                starredUpdating = starredUpdating,
                onToggleStarred = onToggleStarred,
                specialAlert = specialAlert,
                specialAlertUpdating = specialAlertUpdating,
                onToggleSpecialAlert = onToggleSpecialAlert,
            )
        }

        Spacer(Modifier.height(Dimens.SpaceXxl))
        if (!member.isSelf) {
            Button(
                onClick = onStartChat,
                enabled = !creatingChat,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                Spacer(Modifier.padding(start = Dimens.SpaceS))
                Text(stringResource(R.string.member_action_message))
            }
            if (chatError) {
                Text(
                    text = stringResource(R.string.member_message_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = Dimens.SpaceS),
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
                    .padding(Dimens.SpaceXl),
            )
        }
    }
}

/**
 * 「设为星标联系人」+「他的消息特别提醒」两个**互不影响**的开关(对标企业微信)。
 *
 * 星标只管归类,拨它不会改变通知行为;要「这个人的消息别漏」得单独开特别提醒。
 * 飞书把两者并成一个,再拿一页 override 开关去解耦,我们不走那条路 —— 所以这里
 * 是两行,而不是一行加一句「其实还会影响通知」的小字。
 *
 * 直接摊在详情页里(不像飞书那样再进一层「设置」子页):we-meet 没有那页的
 * 备注与描述 / 分享 / 举报,单为两个开关多跳一层不值。
 */
@Composable
private fun ContactPrefRows(
    starred: Boolean,
    starredUpdating: Boolean,
    onToggleStarred: (Boolean) -> Unit,
    specialAlert: Boolean,
    specialAlertUpdating: Boolean,
    onToggleSpecialAlert: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SwitchRow(
            label = stringResource(R.string.starred_toggle),
            hint = stringResource(R.string.starred_toggle_hint),
            checked = starred,
            enabled = !starredUpdating,
            onCheckedChange = onToggleStarred,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SwitchRow(
            label = stringResource(R.string.special_alert_toggle),
            hint = stringResource(R.string.special_alert_toggle_hint),
            checked = specialAlert,
            enabled = !specialAlertUpdating,
            onCheckedChange = onToggleSpecialAlert,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 一行开关 + 下面一句说明(说明解释这个开关到底会发生什么)。 */
@Composable
private fun SwitchRow(
    label: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimens.SpaceXs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Dimens.SpaceS),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimens.SpaceS),
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
                .padding(vertical = Dimens.SpaceS),
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
                Spacer(Modifier.padding(start = Dimens.SpaceM))
                when {
                    revealing -> CircularProgressIndicator(
                        strokeWidth = Dimens.BorderEmphasis,
                        modifier = Modifier.padding(start = Dimens.SpaceXs).height(Dimens.IconTiny),
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
