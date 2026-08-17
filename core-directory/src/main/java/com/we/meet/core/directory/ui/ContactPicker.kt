package com.we.meet.core.directory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.we.meet.ui.theme.Dimens
import com.we.meet.core.directory.DirectoryDeps
import com.we.meet.core.directory.R
import com.we.meet.core.directory.data.DirectoryRepository
import com.we.meet.core.directory.data.MemberDto
import com.we.meet.core.directory.net.DirectoryNetwork
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

enum class ContactPickerMode { Single, Multi }

/** A confirmed selection — carries what the bridge/calendar endpoints accept. */
data class PickedMember(
    /** we-meet user uuid → `peer_user_id` / `member_user_ids` / `attendee_ids`. */
    val userId: String,
    val displayName: String,
    val email: String?,
    /** Short-lived presigned URL; render immediately, never persist. */
    val avatarUrl: String?,
)

/**
 * Org-member picker rendered as a modal bottom sheet: debounced search on top,
 * member rows below. Single mode confirms on row tap; Multi mode collects
 * checkboxed selections behind a confirm button.
 *
 * Self-contained: builds its own repository from [deps], handles loading/empty/
 * error states internally. State lives in plain remembers (not a ViewModel) so
 * the picker can be hosted inside dialogs/sheets from any module without
 * ViewModelStoreOwner friction — its lifetime equals sheet visibility.
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ContactPicker(
    deps: DirectoryDeps,
    mode: ContactPickerMode,
    /** False while the host is submitting the selection; blocks duplicate actions. */
    enabled: Boolean = true,
    excludeSelf: Boolean = true,
    excludeUserIds: Set<String> = emptySet(),
    /** Multi 模式下预勾选的 userId(如从直聊「新建群聊」带入对端);加载到即选中一次。 */
    preselectUserIds: Set<String> = emptySet(),
    /** P5 统一邀请面板:成员列表与确认钮之间的自定义区(会议号/复制链接等)。
     * 默认 null——既有调用方零变化。 */
    footer: (@Composable () -> Unit)? = null,
    /** P5.1(实测问题3):把参会人页「搜索或呼叫」框已输入的词带进来当初始
     * 搜索,输入不白打。默认空——既有调用方零变化。 */
    initialQuery: String = "",
    /** Include accepted cross-organization contacts; never account-search here. */
    includeExternal: Boolean = false,
    onConfirm: (List<PickedMember>) -> Unit,
    onDismiss: () -> Unit,
) {
    val repository = remember(deps) { DirectoryRepository(DirectoryNetwork.directoryApi(deps)) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var query by remember { mutableStateOf(initialQuery) }
    var members by remember { mutableStateOf<List<MemberDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    val selected = remember { mutableStateOf<Map<String, PickedMember>>(linkedMapOf()) }

    // 预勾选:成员加载到后,把 preselectUserIds 里出现的成员选中一次(seeded 后不再
    // 重复,尊重用户随后的取消)。仅 Multi 模式有意义(Single 点选即确认)。
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(members) {
        if (seeded || preselectUserIds.isEmpty()) return@LaunchedEffect
        val add = members.filter { it.id in preselectUserIds && it.id !in selected.value }
        if (add.isNotEmpty()) {
            selected.value = selected.value + add.associate { it.id to it.toPicked() }
            seeded = true
        }
    }

    LaunchedEffect(repository, reloadTick) {
        snapshotFlow { query }
            .debounce(300)
            .distinctUntilChanged()
            .collect { q ->
                loading = true
                error = false
                val result = if (q.isBlank()) repository.allMembers() else repository.searchMembers(q)
                val external = if (includeExternal) {
                    repository.listExternalContacts().getOrDefault(emptyList())
                        .filter { contact ->
                            q.isBlank() || listOf(
                                contact.displayName,
                                contact.organization?.name.orEmpty(),
                            ).any { it.contains(q, ignoreCase = true) }
                        }
                        .map { it.toMember() }
                } else {
                    emptyList()
                }
                result
                    .onSuccess { page ->
                        members = (page.members + external).distinctBy { it.id }.filter { m ->
                            (!excludeSelf || !m.isSelf) && m.id !in excludeUserIds
                        }
                    }
                    .onFailure { error = true }
                loading = false
            }
    }

    ModalBottomSheet(
        onDismissRequest = { if (enabled) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = Dimens.ScreenPadding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                enabled = enabled,
                placeholder = { Text(stringResource(R.string.picker_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (mode == ContactPickerMode.Multi && selected.value.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Dimens.SpaceS),
                ) {
                    items(selected.value.values.toList(), key = { it.userId }) { picked ->
                        InputChip(
                            selected = true,
                            onClick = { selected.value = selected.value - picked.userId },
                            enabled = enabled,
                            label = { Text(picked.displayName, maxLines = 1) },
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    error -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = Dimens.SpaceXxl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.picker_load_error),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(
                            onClick = { reloadTick++ },
                            enabled = enabled,
                            modifier = Modifier.padding(top = Dimens.SpaceS),
                        ) {
                            Text(stringResource(R.string.picker_retry))
                        }
                    }

                    members.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.picker_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(members, key = { it.id }) { member ->
                            MemberRow(
                                member = member,
                                mode = mode,
                                checked = member.id in selected.value,
                                enabled = enabled,
                                onClick = {
                                    val picked = member.toPicked()
                                    if (mode == ContactPickerMode.Single) {
                                        onConfirm(listOf(picked))
                                    } else {
                                        selected.value =
                                            if (member.id in selected.value) {
                                                selected.value - member.id
                                            } else {
                                                selected.value + (member.id to picked)
                                            }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            footer?.invoke()

            if (mode == ContactPickerMode.Multi) {
                Button(
                    onClick = { onConfirm(selected.value.values.toList()) },
                    enabled = enabled && selected.value.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dimens.SpaceM),
                ) {
                    Text(stringResource(R.string.picker_confirm_count, selected.value.size))
                }
            } else {
                Spacer(Modifier.padding(bottom = Dimens.SpaceM))
            }
        }
    }
}

@Composable
private fun MemberRow(
    member: MemberDto,
    mode: ContactPickerMode,
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = Dimens.SpaceS),
    ) {
        MemberAvatar(
            name = member.displayName,
            url = member.avatarUrl,
            cacheKey = "avatar:${member.id}",
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = Dimens.SpaceM),
        ) {
            Text(
                member.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(
                stringResource(R.string.picker_external_tag).takeIf { member.external },
                member.title?.takeIf { it.isNotBlank() },
                member.department?.name?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (mode == ContactPickerMode.Multi) {
            Spacer(Modifier.width(Dimens.SpaceS))
            Checkbox(
                checked = checked,
                onCheckedChange = { onClick() },
                enabled = enabled,
            )
        }
    }
}

private fun MemberDto.toPicked() = PickedMember(
    userId = id,
    displayName = displayName,
    email = email,
    avatarUrl = avatarUrl,
)
