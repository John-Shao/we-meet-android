package com.we.meet.feature.docs.ui

import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Check
import com.we.meet.ui.components.WeMeetInlineErrorState

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.ui.components.SecondaryButton

import com.we.meet.feature.docs.util.docsRunCatching as runCatching

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
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
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private enum class SharePage { HOME, ACCESS, MEMBERS, INVITE }

/** Compact overview with access, membership and invitation pages in one sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocShareSheet(
    deps: DocsDeps,
    doc: DocumentDto,
    onDismiss: () -> Unit,
    onDocChanged: () -> Unit,
) {
    val vm: DocShareViewModel = viewModel(
        key = "share:${doc.id}",
        factory = viewModelFactory { initializer { DocShareViewModel(deps.docsRepository, doc) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var page by rememberSaveable(doc.id) { mutableStateOf(SharePage.HOME) }
    var inviteOrigin by rememberSaveable(doc.id) { mutableStateOf(SharePage.HOME) }
    var inviteRole by rememberSaveable(doc.id) { mutableStateOf("reader") }
    var recipientId by rememberSaveable(doc.id) { mutableStateOf<String?>(null) }
    var recipientName by rememberSaveable(doc.id) { mutableStateOf<String?>(null) }
    var recipientEmail by rememberSaveable(doc.id) { mutableStateOf<String?>(null) }
    var showLeave by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<DocsAccessDto?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    val goBack = { focus.clearFocus(); page = if (page == SharePage.INVITE) inviteOrigin else SharePage.HOME }
    val inviteSucceeded = {
        val message = if (recipientId != null) R.string.docs_share_member_added else R.string.docs_share_invite_success
        recipientId = null
        recipientName = null
        recipientEmail = null
        inviteRole = "reader"
        vm.onUserQueryChange("")
        focus.clearFocus()
        page = SharePage.MEMBERS
        onDocChanged()
        scope.launch { snackbar.showSnackbar(context.getString(message)) }
        Unit
    }
    LaunchedEffect(vm) { vm.errors.collect { snackbar.showSnackbar(context.getString(R.string.docs_action_failed)) } }
    LaunchedEffect(vm) { vm.load() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        properties = androidx.compose.material3.ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        // Handle navigation in the dialog's dispatcher before dismissing the whole sheet.
        androidx.activity.compose.BackHandler { if (page == SharePage.HOME) onDismiss() else goBack() }
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.85f)
                .imePadding().padding(bottom = Dimens.SpaceM),
        ) {
            DocsSheetHeader(
                title = stringResource(when (page) {
                    SharePage.HOME -> R.string.docs_share
                    SharePage.ACCESS -> R.string.docs_share_access_title
                    SharePage.MEMBERS -> R.string.docs_share_collaborators
                    SharePage.INVITE -> R.string.docs_share_invite_members
                }),
                onClose = onDismiss,
                subtitle = if (page == SharePage.HOME) doc.displayTitle.ifBlank { stringResource(R.string.docs_untitled) } else null,
                onBack = if (page == SharePage.HOME) null else goBack,
            )
            if (state.mutating || state.linkSaving) WeMeetInlineLoading()
            androidx.compose.material3.SnackbarHost(snackbar)
            // Reset scrolling when changing pages; drafts live above page content.
            androidx.compose.runtime.key(page) {
                LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = Dimens.SpaceM)) {
                    when (page) {
                        SharePage.HOME -> {
                            item("copy") {
                                Box(Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM)) {
                                    PrimaryButton(text = stringResource(R.string.docs_copy_link), onClick = {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(doc.displayTitle,
                                            com.we.meet.feature.docs.util.DocLinks.webUrl(deps.docsBaseUrl, doc.id)))
                                        scope.launch { snackbar.showSnackbar(context.getString(R.string.docs_link_copied)) }
                                    })
                                }
                            }
                            item("access") {
                                ShareNavigationRow(
                                    title = stringResource(R.string.docs_share_access_title),
                                    summary = stringResource(reachLabelRes(state.linkReach)) +
                                        if (state.linkReach != "restricted" && state.linkRole.isNotBlank())
                                            " ? " + stringResource(roleLabelRes(state.linkRole)) else "",
                                    onClick = { page = SharePage.ACCESS },
                                )
                            }
                            if (state.doc.abilities.retrieve) item("members") {
                                ShareNavigationRow(
                                    title = stringResource(R.string.docs_share_collaborators),
                                    summary = when {
                                        state.error -> stringResource(R.string.docs_load_error)
                                        !state.loaded -> stringResource(R.string.docs_share_loading_members)
                                        else -> androidx.compose.ui.res.pluralStringResource(R.plurals.docs_share_member_count, state.accesses.size, state.accesses.size)
                                    },
                                    onClick = { page = SharePage.MEMBERS },
                                )
                            }
                            if (state.doc.abilities.accessesManage) item("invite") {
                                Box(Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM)) {
                                    SecondaryButton(text = stringResource(R.string.docs_share_invite_members),
                                        onClick = { inviteOrigin = SharePage.HOME; page = SharePage.INVITE })
                                }
                            }
                            if (!state.doc.abilities.retrieve) item("request") {
                                Box(Modifier.padding(Dimens.ScreenPadding)) {
                                    if (state.requestedAccess) Text(stringResource(R.string.docs_share_ask_sent))
                                    else PrimaryButton(text = stringResource(R.string.docs_share_ask_access),
                                        loading = state.mutating, onClick = vm::requestAccess)
                                }
                            }
                        }
                        SharePage.ACCESS -> {
                            item("options") { LinkAccessOptions(state, vm::updateLink) }
                        }
                        SharePage.MEMBERS -> {
                            if (state.loading && !state.loaded) item("loading") { WeMeetInlineLoading() }
                            if (state.error) item("error") {
                                WeMeetInlineErrorState(onRetry = vm::load, message = stringResource(R.string.docs_load_error))
                            }
                            items(state.accesses, key = { "access-${it.id}" }) { access ->
                                AccessRow(access, !state.mutating,
                                    onChangeRole = { vm.updateAccessRole(access, it) },
                                    onRemove = { removeTarget = access })
                            }
                            if (state.loaded && state.accesses.isEmpty()) item("empty") {
                                ShareHint(stringResource(R.string.docs_share_no_members))
                            }
                            if (state.invitations.isNotEmpty()) item("pending-title") {
                                HorizontalDivider(Modifier.padding(horizontal = Dimens.ScreenPadding))
                                ShareHint(stringResource(R.string.docs_share_pending_invitations))
                            }
                            items(state.invitations, key = { "invitation-${it.id}" }) { invitation ->
                                InvitationRow(invitation, !state.mutating, onDelete = { vm.deleteInvitation(invitation) })
                            }
                            if (state.doc.abilities.accessesManage) item("invite") {
                                Box(Modifier.padding(Dimens.ScreenPadding)) {
                                    SecondaryButton(text = stringResource(R.string.docs_share_invite_members),
                                        onClick = { inviteOrigin = SharePage.MEMBERS; page = SharePage.INVITE })
                                }
                            }
                            if (state.doc.abilities.leave) item("leave") {
                                TextButton(onClick = { showLeave = true }, enabled = !state.mutating,
                                    modifier = Modifier.padding(horizontal = Dimens.ScreenPadding)) {
                                    Text(stringResource(R.string.docs_share_leave), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        SharePage.INVITE -> if (state.doc.abilities.accessesManage) {
                            val selected = recipientId != null || recipientEmail != null
                            if (selected) {
                                item("recipient") {
                                    Column(Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM)) {
                                        Text(recipientName ?: recipientEmail.orEmpty(), style = MaterialTheme.typography.titleMedium)
                                        if (recipientName != null && !recipientEmail.isNullOrBlank()) Text(recipientEmail.orEmpty(),
                                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        TextButton(enabled = !state.mutating, onClick = {
                                            recipientId = null; recipientName = null; recipientEmail = null
                                        }) { Text(stringResource(R.string.docs_share_change_recipient)) }
                                        HorizontalDivider()
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(stringResource(R.string.docs_share_invite_role), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                            RoleDropdown(inviteRole, SHARABLE_ROLES, { inviteRole = it }, !state.mutating)
                                        }
                                        PrimaryButton(
                                            text = stringResource(if (recipientId != null) R.string.docs_share_add_member else R.string.cd_docs_invite),
                                            loading = state.mutating,
                                            onClick = {
                                                val userId = recipientId
                                                if (userId != null) vm.addMember(userId, inviteRole, inviteSucceeded)
                                                else vm.invite(recipientEmail.orEmpty(), inviteRole, inviteSucceeded)
                                            },
                                        )
                                    }
                                }
                            } else {
                                item("search") {
                                    OutlinedTextField(
                                        value = state.userQuery, onValueChange = vm::onUserQueryChange,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
                                        enabled = !state.mutating,
                                        label = { Text(stringResource(R.string.docs_share_search_user)) },
                                        leadingIcon = { Icon(Icons.Outlined.PersonAdd, null) },
                                        singleLine = true,
                                    )
                                }
                                if (state.userSearching) item("searching") { WeMeetInlineLoading() }
                                items(state.userResults, key = { "user-${it.id}" }) { user ->
                                    UserResultRow(user, !state.mutating, onSelect = {
                                        recipientId = user.id
                                        recipientName = user.displayName
                                        recipientEmail = user.email
                                        focus.clearFocus()
                                    })
                                }
                                if (state.userSearchError) item("search-error") {
                                    WeMeetInlineErrorState(onRetry = { vm.onUserQueryChange(state.userQuery) },
                                        message = stringResource(R.string.docs_action_failed))
                                }
                                val email = state.userQuery.trim()
                                val isEmail = android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
                                if (isEmail && !state.userSearching && !state.userSearchError && state.userResults.none { it.email.equals(email, ignoreCase = true) }) item("email") {
                                    ShareNavigationRow(stringResource(R.string.docs_share_invite_section), email,
                                        enabled = !state.mutating, onClick = {
                                            recipientId = null; recipientName = null; recipientEmail = email
                                            focus.clearFocus()
                                        })
                                }
                                if (state.userQuery.trim().length < state.userSearchMinLength) item("hint") {
                                    ShareHint(stringResource(R.string.docs_share_invite_hint, state.userSearchMinLength))
                                } else if (!isEmail && !state.userSearching && !state.userSearchError && state.userResults.isEmpty()) item("empty") {
                                    ShareHint(stringResource(R.string.docs_share_no_users))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    removeTarget?.let { access ->
        DestructiveConfirmDialog(
            title = stringResource(R.string.cd_docs_remove_member),
            message = stringResource(R.string.docs_remove_member_message,
                access.user?.displayName ?: access.team ?: stringResource(R.string.docs_unknown_user)),
            confirmLabel = stringResource(R.string.cd_docs_remove_member), dismissLabel = stringResource(R.string.docs_cancel),
            onConfirm = { removeTarget = null; vm.removeAccess(access, onDocChanged) }, onDismiss = { removeTarget = null },
        )
    }
    if (showLeave) DestructiveConfirmDialog(
        title = stringResource(R.string.docs_share_leave_title), message = stringResource(R.string.docs_share_leave_message),
        confirmLabel = stringResource(R.string.docs_share_leave), dismissLabel = stringResource(R.string.docs_cancel),
        onConfirm = { showLeave = false; vm.leave { onDocChanged(); onDismiss() } }, onDismiss = { showLeave = false },
    )
}

@Composable
private fun ShareNavigationRow(title: String, summary: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
        .heightIn(min = Dimens.MinTouchTarget).padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.padding(start = Dimens.SpaceS))
    }
}

@Composable
private fun ShareHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkAccessOptions(state: DocShareViewModel.UiState, onChange: (String?, String?) -> Unit) {
    val canChange = state.doc.abilities.retrieve && state.doc.abilities.linkConfiguration
    val reaches = if (canChange) state.doc.abilities.linkSelectOptions.keys.toList() else listOf(state.linkReach)
    Column {
        reaches.forEach { reach ->
            Row(Modifier.fillMaxWidth().selectable(selected = state.linkReach == reach,
                enabled = canChange && !state.linkSaving, role = androidx.compose.ui.semantics.Role.RadioButton,
                onClick = { onChange(reach, null) })
                .heightIn(min = Dimens.MinTouchTarget).padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
                verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(selected = state.linkReach == reach, onClick = null,
                    enabled = canChange && !state.linkSaving)
                Column(Modifier.weight(1f).padding(start = Dimens.SpaceM)) {
                    Text(stringResource(reachLabelRes(reach)), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(when (reach) {
                        "public" -> R.string.docs_share_public_help
                        "authenticated" -> R.string.docs_share_authenticated_help
                        else -> R.string.docs_share_restricted_help
                    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.linkReach != "restricted") {
            HorizontalDivider(Modifier.padding(horizontal = Dimens.ScreenPadding))
            ShareHint(stringResource(R.string.docs_share_link_role))
            if (canChange) FlowRow(Modifier.padding(horizontal = Dimens.ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                state.doc.abilities.linkSelectOptions[state.linkReach].orEmpty().forEach { role ->
                    FilterChip(selected = state.linkRole == role, enabled = !state.linkSaving,
                        onClick = { onChange(null, role) }, label = { Text(stringResource(roleLabelRes(role))) })
                }
            } else ShareHint(stringResource(roleLabelRes(state.linkRole)))
        }
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
            .heightIn(min = Dimens.MinTouchTarget)
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
        if (access.abilities.setRoleTo.isNotEmpty() || access.abilities.destroy) {
            RoleDropdown(
                role = access.role.orEmpty(),
                enabled = enabled,
                roles = access.abilities.setRoleTo,
                onSelect = onChangeRole,
                onRemove = if (access.abilities.destroy) onRemove else null,
            )
        } else {
            Text(
                text = stringResource(roleLabelRes(access.role.orEmpty())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RoleDropdown(
    role: String,
    roles: List<String>,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
    onRemove: (() -> Unit)? = null,
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
            if (onRemove != null) {
                if (roles.isNotEmpty()) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cd_docs_remove_member), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.PersonRemove, null, tint = MaterialTheme.colorScheme.error) },
                    enabled = enabled,
                    onClick = { expanded = false; onRemove() },
                )
            }
        }
    }
}

@Composable
private fun UserResultRow(
    user: DocsUserDto,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget).clickable(enabled = enabled, onClick = onSelect)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(user.displayName.ifBlank { user.email.orEmpty() }, style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!user.email.isNullOrBlank()) Text(user.email.orEmpty(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
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
        val userSearchMinLength: Int = 3,
        val userResults: List<DocsUserDto> = emptyList(),
        val userSearching: Boolean = false,
        val userSearchError: Boolean = false,
        val requestedAccess: Boolean = false,
        val loaded: Boolean = false,
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

    init {
        if (doc.abilities.accessesManage) viewModelScope.launch {
            runCatching { repo.userSearchMinLength() }.onSuccess { minimum ->
                _state.update { it.copy(userSearchMinLength = minimum) }
                if (_state.value.userQuery.isNotBlank()) onUserQueryChange(_state.value.userQuery)
            }
        }
    }

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
                        loaded = true,
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
        val canSearch = query.trim().length >= _state.value.userSearchMinLength
        _state.update { it.copy(userQuery = query, userSearchError = false, userSearching = canSearch, userResults = emptyList()) }
        searchJob?.cancel()
        if (!canSearch) return
        searchJob = viewModelScope.launch {
            delay(300)
            _state.update { it.copy(userSearching = true) }
            runCatching { repo.searchUsers(query.trim(), documentId = doc.id) }
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

    fun addMember(userId: String, role: String, onSuccess: () -> Unit) = mutate {
        repo.createAccess(doc.id, userId, role)
        onSuccess()
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

private val SHARABLE_ROLES = listOf("reader", "commenter", "editor")
