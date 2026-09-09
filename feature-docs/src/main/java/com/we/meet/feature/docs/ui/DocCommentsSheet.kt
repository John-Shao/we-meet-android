package com.we.meet.feature.docs.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.we.meet.feature.docs.util.formatIsoTime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow

import com.we.meet.feature.docs.util.docsRunCatching as runCatching
import kotlinx.coroutines.flow.collectLatest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.we.meet.feature.docs.DocsDeps
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.data.DocsRepository
import com.we.meet.feature.docs.data.net.DocsCommentDto
import com.we.meet.feature.docs.data.net.DocsThreadDto
import com.we.meet.ui.components.WeMeetEmptyState
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 评论/线程 BottomSheet(设计文档 §4.4 评论):列表 + 发表 + 回复 + 解决 + 表情。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DocCommentsSheet(
    deps: DocsDeps,
    docId: String,
    onDismiss: () -> Unit,
    onOpenComment: (String) -> Unit = {},
) {
    val vm: DocCommentsViewModel = viewModel(
        key = "comments:$docId",
        factory = viewModelFactory {
            initializer { DocCommentsViewModel(deps.docsRepository, docId, createSavedStateHandle()) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val draft = vm.draft
    var expandedThreadId by remember { mutableStateOf<String?>(null) }
    var replyTo by remember { mutableStateOf<DocsThreadDto?>(null) }
    val replyDraft = vm.replyDrafts[replyTo?.id].orEmpty()
    var deleteTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showResolved by rememberSaveable { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.errors.collect { snackbar.showSnackbar(context.getString(R.string.docs_action_failed)) }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            com.we.meet.feature.docs.util.docsConnectivity(context).collectLatest { online ->
                if (online) {
                    vm.refresh(manual = true)
                    while (true) {
                        kotlinx.coroutines.delay(vm.pollDelayMillis)
                        vm.refresh()
                    }
                } else vm.onOffline()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .imePadding()
                .padding(bottom = Dimens.SpaceXl),
        ) {
            DocsSheetHeader(stringResource(R.string.docs_comments), onDismiss)
            FlowRow(Modifier.padding(horizontal = Dimens.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                androidx.compose.material3.FilterChip(selected = !showResolved, onClick = { showResolved = false },
                    label = { Text(stringResource(R.string.docs_comments_open, state.threads.count { !it.resolved })) })
                androidx.compose.material3.FilterChip(selected = showResolved, onClick = { showResolved = true },
                    label = { Text(stringResource(R.string.docs_comments_resolved, state.threads.count { it.resolved })) })
            }
            Box(
                Modifier
                    .padding(top = Dimens.SpaceM)
                    .weight(1f),
            ) {
                when {
                    state.loading -> WeMeetLoading()
                    state.error && state.threads.isEmpty() -> WeMeetErrorState(
                        onRetry = vm::load,
                        message = stringResource(R.string.docs_load_error),
                    )
                    state.threads.none { it.resolved == showResolved } -> com.we.meet.ui.components.WeMeetEmptyState(
                        title = stringResource(if (showResolved) R.string.docs_comments_resolved_empty else R.string.docs_comments_empty_title),
                        description = if (showResolved) null else stringResource(R.string.docs_comments_empty_desc),
                    )
                    else -> LazyColumn(contentPadding = PaddingValues(bottom = Dimens.SpaceM)) {
                        items(state.threads.filter { it.resolved == showResolved }, key = { it.id }) { thread ->
                            ThreadItem(
                                thread = thread,
                                expanded = expandedThreadId == thread.id,
                                onToggle = {
                                    expandedThreadId = if (expandedThreadId == thread.id) null else thread.id
                                },
                                onReply = { replyTo = thread },
                                onToggleResolved = { vm.setResolved(thread.id, !thread.resolved) },
                                onReact = { commentId, emoji -> vm.toggleReaction(commentId, emoji) },
                                onViewInDoc = { onOpenComment(thread.id) },
                                onDeleteComment = { deleteTarget = thread.id to it },
                            )
                        }
                    }
                }
            }
            androidx.compose.material3.SnackbarHost(snackbar)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = vm::updateDraft,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.docs_comment_hint)) },
                    maxLines = 4,
                    enabled = !state.sending,
                )
                IconButton(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            vm.createThread(text) { vm.updateDraft("") }
                        }
                    },
                    enabled = draft.isNotBlank() && !state.sending,
                ) {
                    if (state.sending) com.we.meet.ui.components.WeMeetInlineLoading() else Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Send,
                        contentDescription = stringResource(R.string.cd_docs_send_comment),
                    )
                }
            }
        }
    }

    deleteTarget?.let { (threadId, commentId) ->
        com.we.meet.ui.components.DestructiveConfirmDialog(
            title = stringResource(R.string.docs_comment_delete),
            message = stringResource(R.string.docs_comment_delete_message),
            confirmLabel = stringResource(R.string.docs_delete_confirm),
            dismissLabel = stringResource(R.string.docs_cancel),
            onConfirm = { deleteTarget = null; vm.deleteComment(threadId, commentId) },
            onDismiss = { deleteTarget = null },
        )
    }

    replyTo?.let { thread ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { replyTo = null },
            title = { Text(stringResource(R.string.docs_reply)) },
            text = {
                OutlinedTextField(
                    value = replyDraft,
                    enabled = !state.sending,
                    onValueChange = { vm.updateReplyDraft(thread.id, it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.docs_comment_hint)) },
                    maxLines = 4,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val text = replyDraft.trim()
                        if (text.isNotEmpty()) {
                            vm.reply(thread.id, text) { vm.updateReplyDraft(thread.id, ""); replyTo = null }
                        }
                    },
                    enabled = replyDraft.isNotBlank() && !state.sending,
                ) {
                    Text(stringResource(R.string.docs_reply_confirm))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { replyTo = null }) {
                    Text(stringResource(R.string.docs_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThreadItem(
    thread: DocsThreadDto,
    expanded: Boolean,
    onToggle: () -> Unit,
    onReply: () -> Unit,
    onToggleResolved: () -> Unit,
    onReact: (commentId: String, emoji: String) -> Unit,
    onViewInDoc: () -> Unit,
    onDeleteComment: (String) -> Unit,
) {
    val first = thread.comments.firstOrNull()
    val expansionLabel = stringResource(if (expanded) R.string.docs_thread_collapse else R.string.docs_thread_expand)
    Surface(
        color = if (thread.resolved) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceXs),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(Dimens.BorderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier
                .clickable(onClickLabel = expansionLabel, onClick = onToggle)
                .padding(Dimens.SpaceM),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = thread.creator?.displayName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.docs_unknown_user),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.docs_comments_count, (thread.comments.size - 1).coerceAtLeast(0)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Dimens.SpaceS),
                )
                if (thread.resolved) {
                    Text(
                        text = stringResource(R.string.docs_resolved_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Dimens.SpaceS),
                    )
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                    modifier = Modifier.padding(start = Dimens.SpaceS).size(Dimens.IconSmall),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!expanded) Text(
                text = commentBodyPlainText(first?.body),
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Dimens.SpaceXs),
            )
            if (expanded) {
                thread.comments.forEach { comment ->
                    CommentItem(
                        comment = comment,
                        showAuthor = comment.id != first?.id,
                        onReact = { emoji -> onReact(comment.id, emoji) },
                        onDelete = { onDeleteComment(comment.id) },
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                    androidx.compose.material3.TextButton(onClick = onReply) {
                        Text(stringResource(R.string.docs_reply))
                    }
                    androidx.compose.material3.TextButton(onClick = onViewInDoc) {
                        Text(stringResource(R.string.docs_view_in_doc))
                    }
                    androidx.compose.material3.TextButton(onClick = onToggleResolved, enabled = if (thread.resolved) thread.abilities.unresolve else thread.abilities.resolve) {
                        Icon(
                            imageVector = if (thread.resolved) {
                                Icons.Outlined.RemoveDone
                            } else {
                                Icons.Outlined.Done
                            },
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.IconSmall),
                        )
                        Text(
                            stringResource(
                                if (thread.resolved) R.string.docs_reopen else R.string.docs_resolve,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommentItem(
    comment: DocsCommentDto,
    showAuthor: Boolean,
    onReact: (emoji: String) -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = Dimens.SpaceS)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (showAuthor) Text(comment.user?.displayName?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.docs_unknown_user), style = MaterialTheme.typography.titleSmall)
                if (!comment.createdAt.isNullOrBlank()) Text(formatIsoTime(comment.createdAt),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (comment.abilities.destroy) Box {
                IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.cd_docs_more)) }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.docs_comment_delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onDelete() })
                }
            }
        }
        Text(commentBodyPlainText(comment.body), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
            comment.reactions.forEach { reaction ->
                TextButton(onClick = { onReact(reaction.emoji) }, enabled = comment.abilities.react) {
                    Text("${reaction.emoji} ${reaction.users.size}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            PRESET_EMOJIS.filterNot { emoji -> comment.reactions.any { it.emoji == emoji } }.forEach { emoji ->
                TextButton(onClick = { onReact(emoji) }, enabled = comment.abilities.react) {
                    Text(emoji, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// ---- ViewModel ----

class DocCommentsViewModel(
    private val repo: DocsRepository,
    private val docId: String,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    var draft by mutableStateOf(savedState.get<String>("draft").orEmpty())
        private set
    val replyDrafts = androidx.compose.runtime.mutableStateMapOf<String, String>().apply {
        putAll(savedState.get<HashMap<String, String>>("replies").orEmpty())
    }
    fun updateDraft(value: String) { draft = value; savedState["draft"] = value }
    fun updateReplyDraft(threadId: String, value: String) {
        if (value.isEmpty()) replyDrafts.remove(threadId) else replyDrafts[threadId] = value
        savedState["replies"] = HashMap(replyDrafts)
    }

    data class UiState(
        val threads: List<DocsThreadDto> = emptyList(),
        val loading: Boolean = false,
        val error: Boolean = false,
        val sending: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val errors = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    private var myUserId: String? = null

    var pollDelayMillis = 30_000L
        private set
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()
    fun load() { viewModelScope.launch { refresh(manual = true) } }
    fun onOffline() { _state.update { if (it.threads.isEmpty()) it.copy(error = true, loading = false) else it } }
    fun refreshThreadsSilently() { viewModelScope.launch { refresh() } }
    suspend fun refresh(manual: Boolean = false) {
        refreshMutex.lock()
        try {
            if (manual) _state.update { it.copy(loading = it.threads.isEmpty(), error = false) }
            if (myUserId == null) runCatching { repo.me() }.onSuccess { myUserId = it.id }
            runCatching { repo.threads(docId) }
                .onSuccess { threads ->
                    pollDelayMillis = 30_000
                    _state.update { it.copy(threads = threads, error = false) }
                }
                .onFailure {
                    pollDelayMillis = (pollDelayMillis * 2).coerceAtMost(300_000)
                    if (manual) _state.update { it.copy(error = true) }
                }
        } finally {
            _state.update { it.copy(loading = false) }
            refreshMutex.unlock()
        }
    }

    fun createThread(text: String, onSuccess: () -> Unit) {
        if (_state.value.sending) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true) }
            runCatching { repo.createThread(docId, textInlines(text)) }
                .onSuccess { thread ->
                    _state.update { it.copy(threads = listOf(thread) + it.threads, sending = false) }
                    onSuccess()
                }
                .onFailure { _state.update { it.copy(sending = false) }; errors.tryEmit(Unit) }
        }
    }

    fun reply(threadId: String, text: String, onSuccess: () -> Unit) {
        if (_state.value.sending) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true) }
            runCatching { repo.createComment(docId, threadId, textInlines(text)) }
                .onSuccess { comment ->
                    _state.update { state ->
                        state.copy(
                            sending = false,
                            threads = state.threads.map { thread ->
                                if (thread.id == threadId) {
                                    thread.copy(comments = thread.comments + comment)
                                } else {
                                    thread
                                }
                            },
                        )
                    }
                    onSuccess()
                }
                .onFailure { _state.update { it.copy(sending = false) }; errors.tryEmit(Unit) }
        }
    }

    fun setResolved(threadId: String, resolved: Boolean) {
        viewModelScope.launch {
            runCatching { repo.setThreadResolved(docId, threadId, resolved) }
                .onSuccess {
                    _state.update { state ->
                        state.copy(
                            threads = state.threads.map { if (it.id == threadId) it.copy(resolved = resolved) else it },
                        )
                    }
                }
                .onFailure { errors.tryEmit(Unit) }
        }
    }

    fun deleteComment(threadId: String, commentId: String) {
        viewModelScope.launch {
            runCatching { repo.deleteComment(docId, threadId, commentId) }
                .onSuccess {
                    _state.update { state -> state.copy(threads = state.threads.mapNotNull { thread ->
                        if (thread.id != threadId) thread else thread.copy(comments = thread.comments.filterNot { it.id == commentId })
                            .takeIf { it.comments.isNotEmpty() }
                    }) }
                }
                .onFailure { errors.tryEmit(Unit) }
        }
    }

    fun toggleReaction(commentId: String, emoji: String) {
        viewModelScope.launch {
            val threadId = _state.value.threads.firstOrNull { t ->
                t.comments.any { it.id == commentId }
            }?.id ?: return@launch
            val comment = _state.value.threads.flatMap { it.comments }.firstOrNull { it.id == commentId }
                ?: return@launch
            val mine = comment.reactions.firstOrNull { r ->
                r.emoji == emoji && myUserId != null && r.users.any { it.id == myUserId }
            } != null
            runCatching {
                repo.toggleReaction(docId, threadId, commentId, emoji, mine)
            }.onSuccess { refreshThreadsSilently() }.onFailure { errors.tryEmit(Unit) }
        }
    }

    private fun textInlines(text: String): Any = commentTextToBlocks(text)
}

private val PRESET_EMOJIS = listOf("👍", "❤️", "😂", "🎉")
