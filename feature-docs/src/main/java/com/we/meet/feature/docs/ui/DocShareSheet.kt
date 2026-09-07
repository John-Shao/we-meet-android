package com.we.meet.feature.docs.ui

import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Check
import com.we.meet.ui.components.WeMeetInlineErrorState

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.ui.components.SecondaryButton

import com.we.meet.feature.docs.util.docsRunCatching as runCatching

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.we.meet.feature.docs.DocsDeps
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.data.DocsRepository
import com.we.meet.feature.docs.data.net.DocsAccessDto
import com.we.meet.feature.docs.data.net.DocsInvitationDto
import com.we.meet.feature.docs.data.net.DocsUserDto
import com.we.meet.feature.docs.data.net.DocumentDto
import com.we.meet.ui.components.DestructiveConfirmDialog
import com.we.meet.ui.components.PrimaryButton
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 分享面板(设计文档 §4.4 分享):链接权限 / 成员 / 邀请 / 离开 / 申请访问。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DocShareSheet(
    deps: DocsDeps,
    doc: DocumentDto,
    onDismiss: () -> Unit,
    onDocChanged: () -> Unit,
) {
    val vm: DocShareViewModel = viewModel(
        key = "share:${doc.id}",
        factory = viewModelFactory {
            initializer { DocShareViewModel(deps.docsRepository, doc) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var showLeave by remember { mutableStateOf(false) }
    var inviteEmail by rememberSaveable(doc.id) { mutableStateOf("") }
    var inviteRole by rememberSaveable(doc.id) { mutableStateOf("reader") }

    val scope = rememberCoroutineScope()
    var removeTarget by remember { mutableStateOf<DocsAccessDto?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(vm) { vm.errors.collect { snackbar.showSnackbar(context.getString(R.string.docs_action_failed)) } }
    LaunchedEffect(vm) { vm.load() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth().fillMaxHeight(0.9f).imePadding()
                .padding(bottom = Dimens.SpaceM),
        ) {
            DocsSheetHeader(stringResource(R.string.docs_share), onDismiss,
                doc.displayTitle.ifBlank { stringResource(R.string.docs_untitled) })
            Box(Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS)) {
                SecondaryButton(text = stringResource(R.string.docs_copy_link), onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(doc.displayTitle,
                        com.we.meet.feature.docs.util.DocLinks.webUrl(deps.docsBaseUrl, doc.id)))
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.docs_link_copied)) }
                })
            }
            if (state.mutating || state.linkSaving) WeMeetInlineLoading()

            androidx.compose.material3.SnackbarHost(snackbar)
            when {
                state.loading && state.accesses.isEmpty() -> Box(Modifier.padding(top = Dimens.SpaceM)) { WeMeetLoading() }
                state.error && state.accesses.isEmpty() -> Box(Modifier.padding(top = Dimens.SpaceM)) {
                    WeMeetErrorState(
                        onRetry = vm::load,
                        message = stringResource(R.string.docs_load_error),
                    )
                }
                else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = Dimens.SpaceXl)) {
                    // 链接权限
                    if (state.doc.abilities.retrieve && state.doc.abilities.linkConfiguration) {
                        item(key = "link") {
                            Text(
                                text = stringResource(R.string.docs_share_link_section),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(
                                    horizontal = Dimens.ScreenPadding,
                                    vertical = Dimens.SpaceS,
                                ),
                            )
                            FlowRow(
                                Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
                                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                            ) {
                                state.doc.abilities.linkSelectOptions.keys.forEach { reach ->
                                    FilterChip(
                                        enabled = !state.linkSaving,
                                        selected = state.linkReach == reach,
                                        onClick = { vm.updateLink(reach = reach) },
                                        label = { Text(stringResource(reachLabelRes(reach)), softWrap = false) },
                                    )
                                }
                            }
                            FlowRow(
                                Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
                                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                            ) {
                                state.doc.abilities.linkSelectOptions[state.linkReach].orEmpty().forEach { role ->
                                    FilterChip(
                                        enabled = !state.linkSaving,
                                        selected = state.linkRole == role,
                                        onClick = { vm.updateLink(role = role) },
                                        label = { Text(stringResource(roleLabelRes(role)), softWrap = false) },
                                    )
                                }
                            }
                        }
                    }

                    item(key = "reach-help") {
                        Text(stringResource(when (state.linkReach) {
                            "public" -> R.string.docs_share_public_help
                            "authenticated" -> R.string.docs_share_authenticated_help
                            else -> R.string.docs_share_restricted_help
                        }), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM))
                        HorizontalDivider(Modifier.padding(horizontal = Dimens.ScreenPadding))
                    }
                    // 成员
                    item(key = "members-title") {
                        Text(
                            text = stringResource(R.string.docs_share_members_section),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(
                                horizontal = Dimens.ScreenPadding,
                                vertical = Dimens.SpaceS,
                            ),
                        )
                    }
                    items(state.accesses, key = { it.id }) { access ->
                        AccessRow(
                            access = access,
                            enabled = !state.mutating,
                            onChangeRole = { role -> vm.updateAccessRole(access, role) },
                            onRemove = { removeTarget = access },
                        )
                    }

                    // 添加成员
                    if (state.doc.abilities.accessesManage) item(key = "add-member") {
                        Text(
                            text = stringResource(R.string.docs_share_add_member),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(
                                horizontal = Dimens.ScreenPadding,
                                vertical = Dimens.SpaceS,
                            ),
                        )
                        OutlinedTextField(
                            value = state.userQuery,
                            onValueChange = vm::onUserQueryChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dimens.ScreenPadding),
                            placeholder = { Text(stringResource(R.string.docs_share_search_user)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.PersonAdd,
                                    contentDescription = null,
                                )
                            },
                            singleLine = true,
                        )
                    }
                    if (state.userSearching) {
                        item(key = "user-searching") {
                            Box(Modifier.padding(Dimens.SpaceM)) { WeMeetInlineLoading() }
                        }
                    }
                    items(state.userResults, key = { "user-${it.id}" }) { user ->
                        UserResultRow(user = user, enabled = !state.mutating, onAdd = { vm.addMember(user) })
                    }

                    if (state.userSearchError) item(key = "user-search-error") {
                        WeMeetInlineErrorState(onRetry = { vm.onUserQueryChange(state.userQuery) },
                            message = stringResource(R.string.docs_action_failed))
                    }
                    if (!state.userSearchError && state.userQuery.isNotBlank() && !state.userSearching && state.userResults.isEmpty()) item(key = "no-users") {
                        Text(stringResource(R.string.docs_share_no_users), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(Dimens.ScreenPadding))
                    }
                    // 邀请(邮箱)
                    if (state.doc.abilities.accessesManage) item(key = "invite") {
                        Text(
                            text = stringResource(R.string.docs_share_invite_section),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(
                                horizontal = Dimens.ScreenPadding,
                                vertical = Dimens.SpaceS,
                            ),
                        )
                        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding)) {
                            OutlinedTextField(
                                value = inviteEmail, onValueChange = { inviteEmail = it },
                                modifier = Modifier.fillMaxWidth(), enabled = !state.mutating,
                                label = { Text(stringResource(R.string.docs_share_invite_email)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true,
                            )
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                RoleDropdown(role = inviteRole, roles = SHARABLE_ROLES, enabled = !state.mutating,
                                    onSelect = { inviteRole = it })
                                TextButton(onClick = { vm.invite(inviteEmail.trim(), inviteRole) { inviteEmail = "" } },
                                    enabled = !state.mutating && android.util.Patterns.EMAIL_ADDRESS.matcher(inviteEmail.trim()).matches()) {
                                    Text(stringResource(R.string.cd_docs_invite))
                                }
                            }
                        }
                    }
                    items(state.invitations, key = { it.id }) { invitation ->
                        InvitationRow(
                            invitation = invitation,
                            enabled = !state.mutating,
                            onDelete = { vm.deleteInvitation(invitation) },
                        )
                    }

                    // 离开
                    if (state.doc.abilities.leave) item(key = "leave") {
                        TextButton(onClick = { showLeave = true }, enabled = !state.mutating) {
                            Text(
                                text = stringResource(R.string.docs_share_leave),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (!state.doc.abilities.retrieve && !state.requestedAccess) {
                        item(key = "ask-access") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
                            ) {
                                PrimaryButton(
                                    text = stringResource(R.string.docs_share_ask_access),
                                    loading = state.mutating,
                                    onClick = { vm.requestAccess() },
                                )
                            }
                        }
                    } else if (state.requestedAccess) {
                        item(key = "ask-sent") {
                            Text(
                                text = stringResource(R.string.docs_share_ask_sent),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
                            )
                        }
                    }
                    item(key = "spacer") { HorizontalDivider() }
                }
            }
        }
    }

    removeTarget?.let { access ->
        DestructiveConfirmDialog(
            title = stringResource(R.string.cd_docs_remove_member),
            message = stringResource(R.string.docs_remove_member_message,
                access.user?.displayName ?: access.team ?: stringResource(R.string.docs_unknown_user)),
            confirmLabel = stringResource(R.string.cd_docs_remove_member),
            dismissLabel = stringResource(R.string.docs_cancel),
            onConfirm = { removeTarget = null; vm.removeAccess(access, onDocChanged) },
            onDismiss = { removeTarget = null },
        )
    }

    if (showLeave) {
        DestructiveConfirmDialog(
            title = stringResource(R.string.docs_share_leave_title),
            message = stringResource(R.string.docs_share_leave_message),
            confirmLabel = stringResource(R.string.docs_share_leave),
            dismissLabel = stringResource(R.string.docs_cancel),
            onConfirm = {
                showLeave = false
                vm.leave {
                    onDocChanged()
                    onDismiss()
                }
            },
            onDismiss = { showLeave = false },
        )
    }
}

@Composable
private fun AccessRow(
    access: DocsAccessDto,
    enabled: Boolean,
    onChangeRole: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = access.user?.displayName?.takeIf { it.isNotBlank() }
                ?: access.team
                ?: stringResource(R.string.docs_unknown_user),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (access.abilities.setRoleTo.isNotEmpty()) {
            RoleDropdown(
                role = access.role.orEmpty(),
                enabled = enabled,
                roles = access.abilities.setRoleTo,
                onSelect = onChangeRole,
            )
        } else {
            Text(
                text = stringResource(roleLabelRes(access.role.orEmpty())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (access.abilities.destroy) {
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(
                    imageVector = Icons.Outlined.PersonRemove,
                    contentDescription = stringResource(R.string.cd_docs_remove_member),
                )
            }
        }
    }
}

@Composable
private fun RoleDropdown(
    role: String,
    roles: List<String>,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text(stringResource(roleLabelRes(role)), softWrap = false)
            Icon(Icons.Outlined.ExpandMore, null, modifier = Modifier.size(Dimens.IconSmall))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            roles.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(stringResource(roleLabelRes(candidate)), softWrap = false) },
                    enabled = enabled,
                    trailingIcon = { if (role == candidate) Icon(Icons.Outlined.Check, null) },
                    onClick = {
                        expanded = false
                        onSelect(candidate)
                    },
                )
            }
        }
    }
}

@Composable
private fun UserResultRow(
    user: DocsUserDto,
    enabled: Boolean,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget).clickable(enabled = enabled, onClick = onAdd)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(user.displayName.ifBlank { user.email.orEmpty() }, style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!user.email.isNullOrBlank()) Text(user.email.orEmpty(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.PersonAdd, stringResource(R.string.docs_share_add_member),
            modifier = Modifier.padding(start = Dimens.SpaceS).size(Dimens.IconMedium))
    }
}

@Composable
private fun InvitationRow(
    invitation: DocsInvitationDto,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = invitation.email,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(roleLabelRes(invitation.role.orEmpty())),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (invitation.abilities.destroy) {
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.cd_docs_remove_invitation),
                )
            }
        }
    }
}

// ---- ViewModel ----

class DocShareViewModel(
    private val repo: DocsRepository,
    private val doc: DocumentDto,
) : ViewModel() {

    data class UiState(
        val doc: DocumentDto = DocumentDto(),
        val linkReach: String = "",
        val linkRole: String = "",
        val accesses: List<DocsAccessDto> = emptyList(),
        val invitations: List<DocsInvitationDto> = emptyList(),
        val userQuery: String = "",
        val userResults: List<DocsUserDto> = emptyList(),
        val userSearching: Boolean = false,
        val userSearchError: Boolean = false,
        val requestedAccess: Boolean = false,
        val loading: Boolean = false,
        val mutating: Boolean = false,
        val linkSaving: Boolean = false,
        val error: Boolean = false,
    )

    private val _state = MutableStateFlow(
        UiState(
            doc = doc,
            linkReach = doc.linkReach.orEmpty(),
            linkRole = doc.linkRole.orEmpty(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    val errors = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    private var searchJob: Job? = null
    private var updatingLink = false

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            val accesses = runCatching { repo.allAccesses(doc.id) }.getOrNull()
            val invitations = if (doc.abilities.accessesManage) runCatching { repo.allInvitations(doc.id) }.getOrNull() else emptyList()
            val myRequest = runCatching { repo.accessRequests(doc.id) }.getOrNull()
            if (accesses == null || invitations == null) {
                _state.update { it.copy(loading = false, error = true) }
            } else {
                _state.update {
                    it.copy(
                        accesses = accesses.orEmpty(),
                        invitations = invitations.orEmpty(),
                        requestedAccess = myRequest?.results?.isNotEmpty() == true,
                        loading = false,
                    )
                }
            }
        }
    }

    fun updateLink(reach: String? = null, role: String? = null) {
        if (updatingLink) return
        val prevReach = _state.value.linkReach
        val prevRole = _state.value.linkRole
        val newReach = reach ?: prevReach
        if (!_state.value.doc.abilities.linkSelectOptions.containsKey(newReach)) return
        val options = _state.value.doc.abilities.linkSelectOptions[newReach].orEmpty()
        val newRole = if (newReach == "restricted") null else role ?: prevRole.takeIf { it in options } ?: options.firstOrNull()
        if (newReach != "restricted" && newRole !in options) return
        // 乐观更新;失败回滚,避免 chip 显示服务端并未生效的值。
        _state.update { it.copy(linkReach = newReach, linkRole = newRole.orEmpty()) }
        updatingLink = true
        _state.update { it.copy(linkSaving = true) }
        viewModelScope.launch {
            runCatching { repo.updateLinkConfiguration(doc.id, newReach, newRole) }
                .onFailure { _state.update { it.copy(linkReach = prevReach, linkRole = prevRole) }; errors.tryEmit(Unit) }
            updatingLink = false
            _state.update { it.copy(linkSaving = false) }
        }
    }

    fun onUserQueryChange(query: String) {
        _state.update { it.copy(userQuery = query, userSearchError = false, userSearching = query.isNotBlank(), userResults = if (query.isBlank()) emptyList() else it.userResults) }
        searchJob?.cancel()
        if (query.isBlank()) { _state.update { it.copy(userSearching = false) }; return }
        searchJob = viewModelScope.launch {
            delay(300)
            _state.update { it.copy(userSearching = true) }
            runCatching { repo.searchUsers(query, documentId = doc.id) }
                .onSuccess { users ->
                    // 仅在当前 query 仍是本次请求时应用结果,避免旧响应覆盖新输入。
                    if (_state.value.userQuery == query) {
                        _state.update { it.copy(userResults = users, userSearching = false) }
                    }
                }
                .onFailure { if (_state.value.userQuery == query) _state.update { it.copy(userSearching = false, userSearchError = true) } }
        }
    }

    /** Only one membership mutation at a time; failures remain visible in the sheet. */
    private fun mutate(action: suspend () -> Unit) {
        if (_state.value.mutating) return
        _state.update { it.copy(mutating = true) }
        viewModelScope.launch {
            try { runCatching { action() }.onFailure { errors.tryEmit(Unit) } }
            finally { _state.update { it.copy(mutating = false) } }
        }
    }

    fun addMember(user: DocsUserDto) = mutate {
        repo.createAccess(doc.id, user.id, "reader")
        onUserQueryChange("")
        load()
    }

    fun updateAccessRole(access: DocsAccessDto, role: String) = mutate {
        repo.updateAccess(doc.id, access.id, role)
        load()
    }

    fun removeAccess(access: DocsAccessDto, onDocChanged: () -> Unit) = mutate {
        repo.deleteAccess(doc.id, access.id)
        load()
        onDocChanged()
    }

    fun invite(email: String, role: String, onSuccess: () -> Unit) = mutate {
        repo.createInvitation(doc.id, email, role)
        onSuccess()
        load()
    }

    fun deleteInvitation(invitation: DocsInvitationDto) = mutate {
        repo.deleteInvitation(doc.id, invitation.id)
        load()
    }

    fun requestAccess() = mutate {
        repo.createAccessRequest(doc.id, "reader")
        _state.update { it.copy(requestedAccess = true) }
    }

    fun leave(onLeft: () -> Unit) = mutate {
        repo.leave(doc.id)
        onLeft()
    }

}

// ---- role/reach label helpers ----

internal fun roleLabelRes(role: String): Int = when (role) {
    "reader" -> R.string.docs_role_reader
    "commenter" -> R.string.docs_role_commenter
    "editor" -> R.string.docs_role_editor
    "administrator" -> R.string.docs_role_administrator
    "owner" -> R.string.docs_role_owner
    else -> R.string.docs_role_reader
}

internal fun reachLabelRes(reach: String): Int = when (reach) {
    "public" -> R.string.docs_reach_public
    "authenticated" -> R.string.docs_reach_authenticated
    else -> R.string.docs_reach_restricted
}

private val LINK_REACHES = listOf("restricted", "authenticated", "public")
private val LINK_ROLES = listOf("reader", "commenter", "editor")
private val SHARABLE_ROLES = listOf("reader", "commenter", "editor")
