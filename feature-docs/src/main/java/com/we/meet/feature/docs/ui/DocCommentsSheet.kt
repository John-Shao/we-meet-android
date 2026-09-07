package com.we.meet.feature.docs.ui

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.feature.docs.DocsDeps
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.data.DocsRepository
import com.we.meet.feature.docs.data.net.DocsCommentDto
import com.we.meet.feature.docs.data.net.DocsThreadDto
import com.we.meet.feature.docs.renderer.JsonInlineDto
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
@OptIn(ExperimentalMaterial3Api::class)
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
            initializer { DocCommentsViewModel(deps.docsRepository, docId) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var expandedThreadId by remember { mutableStateOf<String?>(null) }
    var replyTo by remember { mutableStateOf<DocsThreadDto?>(null) }
    var replyDraft by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showResolved by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.errors.collect { snackbar.showSnackbar(context.getString(R.string.docs_load_error)) }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            vm.load()
            while (true) {
                kotlinx.coroutines.delay(30_000)
                vm.refreshThreadsSilently()
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
            Text(
                text = stringResource(R.string.docs_comments),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
            )
            Row(Modifier.padding(horizontal = Dimens.ScreenPadding)) {
                androidx.compose.material3.FilterChip(selected = !showResolved, onClick = { showResolved = false },
                    label = { Text(stringResource(R.string.docs_comments)) })
                androidx.compose.material3.FilterChip(selected = showResolved, onClick = { showResolved = true },
                    label = { Text(stringResource(R.string.docs_resolved_label)) })
            }
            Box(
                Modifier
                    .padding(top = Dimens.SpaceM)
                    .weight(1f),
            ) {
                when {
                    state.loading -> WeMeetLoading()
                    state.error -> WeMeetErrorState(
                        onRetry = vm::load,
                        message = stringResource(R.string.docs_load_error),
                    )
                    state.threads.none { it.resolved == showResolved } -> com.we.meet.ui.components.WeMeetEmptyState(
                        title = stringResource(R.string.docs_comments_empty_title),
                        description = stringResource(R.string.docs_comments_empty_desc),
                    )
                    else -> LazyColumn {
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.docs_comment_hint)) },
                    maxLines = 4,
                )
                IconButton(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            vm.createThread(text) { draft = "" }
                        }
                    },
                    enabled = draft.isNotBlank() && !state.sending,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Done,
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
                    onValueChange = { replyDraft = it },
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
                            vm.reply(thread.id, text) { replyDraft = ""; replyTo = null }
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
    Surface(
        color = if (thread.resolved) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceXs),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            Modifier
                .clickable(onClick = onToggle)
                .padding(Dimens.SpaceM),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = thread.creator?.displayName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.docs_unknown_user),
                    style = MaterialTheme.typography.titleSmall,
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
            }
            if (!expanded) Text(
                text = commentBodyPlainText(first?.body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Dimens.SpaceXs),
            )
            if (expanded) {
                thread.comments.forEach { comment ->
                    CommentItem(
                        comment = comment,
                        onReact = { emoji -> onReact(comment.id, emoji) },
                        onDelete = { onDeleteComment(comment.id) },
                    )
                }
                Row {
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

@Composable
private fun CommentItem(
    comment: DocsCommentDto,
    onReact: (emoji: String) -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.padding(top = Dimens.SpaceS)) {
        Text(
            text = comment.user?.displayName?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.docs_unknown_user),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = commentBodyPlainText(comment.body),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (comment.abilities.destroy) {
            androidx.compose.material3.TextButton(onClick = onDelete) {
                Text(stringResource(R.string.docs_comment_delete))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            comment.reactions.forEach { reaction ->
                Text(
                    text = "${reaction.emoji} ${reaction.users.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(end = Dimens.SpaceXs)
                        .clickable(enabled = comment.abilities.react) { onReact(reaction.emoji) },
                )
            }
            PRESET_EMOJIS.forEach { emoji ->
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(horizontal = Dimens.SpaceXs)
                        .clickable(enabled = comment.abilities.react) { onReact(emoji) },
                )
            }
        }
    }
}

// ---- ViewModel ----

class DocCommentsViewModel(
    private val repo: DocsRepository,
    private val docId: String,
) : ViewModel() {

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

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            runCatching { repo.me() }
                .onSuccess { myUserId = it.id }
            runCatching { repo.threads(docId) }
                .onSuccess { threads -> _state.update { it.copy(threads = threads, loading = false) } }
                .onFailure { _state.update { it.copy(loading = false, error = true) } }
        }
    }

    /** 静默刷新线程:不动 `loading`,避免反应/解决等局部操作把整列表闪成 spinner。 */
    fun refreshThreadsSilently() {
        viewModelScope.launch {
            runCatching { repo.threads(docId) }
                .onSuccess { threads -> _state.update { it.copy(threads = threads) } }
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
