package com.we.meet.feature.docs.ui

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocShareSheet(
    deps: DocsDeps,
    doc: DocumentDto,
    onDismiss: () -> Unit,
    onDocChanged: () -> Unit,
) {
    val vm: DocShareViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DocShareViewModel(deps.docsRepository, doc) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var showLeave by remember { mutableStateOf(false) }
    var inviteEmail by remember { mutableStateOf("") }
    var inviteRole by remember { mutableStateOf("reader") }

    LaunchedEffect(Unit) { vm.load() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Dimens.SpaceXl),
        ) {
            Text(
                text = stringResource(R.string.docs_share),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
            )

            when {
                state.loading -> Box(Modifier.padding(top = Dimens.SpaceM)) { WeMeetLoading() }
                state.error -> Box(Modifier.padding(top = Dimens.SpaceM)) {
                    WeMeetErrorState(
                        onRetry = vm::load,
                        message = stringResource(R.string.docs_load_error),
                    )
                }
                else -> LazyColumn {
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
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Dimens.ScreenPadding),
                            ) {
                                LINK_REACHES.forEach { reach ->
                                    FilterChip(
                                        selected = state.linkReach == reach,
                                        onClick = { vm.updateLink(reach = reach) },
                                        label = { Text(stringResource(reachLabelRes(reach)), softWrap = false) },
                                    )
                                }
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Dimens.ScreenPadding),
                            ) {
                                LINK_ROLES.forEach { role ->
                                    FilterChip(
                                        selected = state.linkRole == role,
                                        onClick = { vm.updateLink(role = role) },
                                        label = { Text(stringResource(roleLabelRes(role)), softWrap = false) },
                                    )
                                }
                            }
                        }
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
                            onChangeRole = { role -> vm.updateAccessRole(access, role) },
                            onRemove = { vm.removeAccess(access, onDocChanged) },
                        )
                    }

                    // 添加成员
                    item(key = "add-member") {
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
                        UserResultRow(user = user, onAdd = { vm.addMember(user) })
                    }

                    // 邀请(邮箱)
                    item(key = "invite") {
                        Text(
                            text = stringResource(R.string.docs_share_invite_section),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(
                                horizontal = Dimens.ScreenPadding,
                                vertical = Dimens.SpaceS,
                            ),
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dimens.ScreenPadding),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = inviteEmail,
                                onValueChange = { inviteEmail = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text(stringResource(R.string.docs_share_invite_email)) },
                                singleLine = true,
                            )
                            RoleDropdown(
                                role = inviteRole,
                                roles = SHARABLE_ROLES,
                                onSelect = { inviteRole = it },
                            )
                            IconButton(
                                onClick = {
                                    val email = inviteEmail.trim()
                                    if (email.isNotBlank()) {
                                        vm.invite(email, inviteRole)
                                        inviteEmail = ""
                                    }
                                },
                                enabled = inviteEmail.isNotBlank(),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PersonAdd,
                                    contentDescription = stringResource(R.string.cd_docs_invite),
                                )
                            }
                        }
                    }
                    items(state.invitations, key = { it.id }) { invitation ->
                        InvitationRow(
                            invitation = invitation,
                            onDelete = { vm.deleteInvitation(invitation) },
                        )
                    }

                    // 离开
                    item(key = "leave") {
                        TextButton(onClick = { showLeave = true }) {
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
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.Close,
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
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(roleLabelRes(role)), softWrap = false)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            roles.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(stringResource(roleLabelRes(candidate)), softWrap = false) },
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
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAdd)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = user.displayName.ifBlank { user.email.orEmpty() },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = user.email.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InvitationRow(
    invitation: DocsInvitationDto,
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
            IconButton(onClick = onDelete) {
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
        val requestedAccess: Boolean = false,
        val loading: Boolean = false,
        val error: Boolean = false,
    )

    private val _state = MutableStateFlow(
        UiState(
            doc = doc,
            linkReach = doc.computedLinkReach ?: doc.linkReach.orEmpty(),
            linkRole = doc.computedLinkRole ?: doc.linkRole.orEmpty(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            val accesses = runCatching { repo.accesses(doc.id) }.getOrNull()
            val invitations = runCatching { repo.invitations(doc.id) }.getOrNull()
            val myRequest = runCatching { repo.accessRequests(doc.id) }.getOrNull()
            if (accesses == null && invitations == null) {
                _state.update { it.copy(loading = false, error = true) }
            } else {
                _state.update {
                    it.copy(
                        accesses = accesses?.results ?: emptyList(),
                        invitations = invitations?.results ?: emptyList(),
                        requestedAccess = myRequest?.results?.isNotEmpty() == true,
                        loading = false,
                    )
                }
            }
        }
    }

    fun updateLink(reach: String? = null, role: String? = null) {
        val newReach = reach ?: _state.value.linkReach
        val newRole = role ?: _state.value.linkRole
        if (newReach.isBlank() || newRole.isBlank()) return
        _state.update { it.copy(linkReach = newReach, linkRole = newRole) }
        viewModelScope.launch {
            runCatching { repo.updateLinkConfiguration(doc.id, newReach, newRole) }
        }
    }

    fun onUserQueryChange(query: String) {
        _state.update { it.copy(userQuery = query, userResults = if (query.isBlank()) emptyList() else it.userResults) }
        searchJob?.cancel()
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(300)
            _state.update { it.copy(userSearching = true) }
            runCatching { repo.searchUsers(query, documentId = doc.id) }
                .onSuccess { users ->
                    _state.update { it.copy(userResults = users, userSearching = false) }
                }
                .onFailure { _state.update { it.copy(userSearching = false) } }
        }
    }

    fun addMember(user: DocsUserDto) {
        viewModelScope.launch {
            runCatching { repo.createAccess(doc.id, user.id, "reader") }
                .onSuccess { load() }
        }
    }

    fun updateAccessRole(access: DocsAccessDto, role: String) {
        viewModelScope.launch {
            runCatching { repo.updateAccess(doc.id, access.id, role) }
                .onSuccess { load() }
        }
    }

    fun removeAccess(access: DocsAccessDto, onDocChanged: () -> Unit) {
        viewModelScope.launch {
            runCatching { repo.deleteAccess(doc.id, access.id) }
                .onSuccess {
                    load()
                    onDocChanged()
                }
        }
    }

    fun invite(email: String, role: String) {
        viewModelScope.launch {
            runCatching { repo.createInvitation(doc.id, email, role) }
                .onSuccess { load() }
        }
    }

    fun deleteInvitation(invitation: DocsInvitationDto) {
        viewModelScope.launch {
            runCatching { repo.deleteInvitation(doc.id, invitation.id) }
                .onSuccess { load() }
        }
    }

    fun requestAccess() {
        viewModelScope.launch {
            runCatching { repo.createAccessRequest(doc.id, "reader") }
                .onSuccess { _state.update { it.copy(requestedAccess = true) } }
        }
    }

    fun leave(onLeft: () -> Unit) {
        viewModelScope.launch {
            runCatching { repo.leave(doc.id) }
                .onSuccess { onLeft() }
        }
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
