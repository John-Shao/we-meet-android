package com.we.meet.feature.docs.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
) {
    val vm: DocCommentsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DocCommentsViewModel(deps.docsRepository, docId) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var expandedThreadId by remember { mutableStateOf<String?>(null) }
    var replyTo by remember { mutableStateOf<DocsThreadDto?>(null) }
    var replyDraft by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.load() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(bottom = Dimens.SpaceXl),
        ) {
            Text(
                text = stringResource(R.string.docs_comments),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
            )
            Box(
                Modifier
                    .padding(top = Dimens.SpaceM)
                    .weight(1f, fill = false),
            ) {
                when {
                    state.loading -> WeMeetLoading()
                    state.error -> WeMeetErrorState(
                        onRetry = vm::load,
                        message = stringResource(R.string.docs_load_error),
                    )
                    state.threads.isEmpty() -> com.we.meet.ui.components.WeMeetEmptyState(
                        title = stringResource(R.string.docs_comments_empty_title),
                        description = stringResource(R.string.docs_comments_empty_desc),
                    )
                    else -> LazyColumn {
                        items(state.threads, key = { it.id }) { thread ->
                            ThreadItem(
                                thread = thread,
                                expanded = expandedThreadId == thread.id,
                                onToggle = {
                                    expandedThreadId = if (expandedThreadId == thread.id) null else thread.id
                                },
                                onReply = { replyTo = thread },
                                onToggleResolved = { vm.setResolved(thread.id, !thread.resolved) },
                                onReact = { commentId, emoji -> vm.toggleReaction(commentId, emoji) },
                            )
                        }
                    }
                }
            }
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
                            vm.createThread(text)
                            draft = ""
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
                            vm.reply(thread.id, text)
                            replyDraft = ""
                        }
                        replyTo = null
                    },
                    enabled = replyDraft.isNotBlank(),
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
                    text = stringResource(R.string.docs_comments_count, thread.comments.size),
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
            Text(
                text = commentBodyText(first?.body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Dimens.SpaceXs),
            )
            if (expanded) {
                thread.comments.forEach { comment ->
                    CommentItem(
                        comment = comment,
                        onReact = { emoji -> onReact(comment.id, emoji) },
                    )
                }
                Row {
                    androidx.compose.material3.TextButton(onClick = onReply) {
                        Text(stringResource(R.string.docs_reply))
                    }
                    androidx.compose.material3.TextButton(onClick = onToggleResolved) {
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
) {
    Column(Modifier.padding(top = Dimens.SpaceS)) {
        Text(
            text = comment.user?.displayName?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.docs_unknown_user),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = commentBodyText(comment.body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            comment.reactions.forEach { reaction ->
                Text(
                    text = "${reaction.emoji} ${reaction.users.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(end = Dimens.SpaceXs)
                        .clickable { onReact(reaction.emoji) },
                )
            }
            PRESET_EMOJIS.forEach { emoji ->
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(horizontal = Dimens.SpaceXs)
                        .clickable { onReact(emoji) },
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

    fun createThread(text: String) {
        if (_state.value.sending) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true) }
            runCatching { repo.createThread(docId, textInlines(text)) }
                .onSuccess { thread ->
                    _state.update { it.copy(threads = listOf(thread) + it.threads, sending = false) }
                }
                .onFailure { _state.update { it.copy(sending = false) } }
        }
    }

    fun reply(threadId: String, text: String) {
        viewModelScope.launch {
            runCatching { repo.createComment(docId, threadId, textInlines(text)) }
                .onSuccess { comment ->
                    _state.update { state ->
                        state.copy(
                            threads = state.threads.map { thread ->
                                if (thread.id == threadId) {
                                    thread.copy(comments = thread.comments + comment)
                                } else {
                                    thread
                                }
                            },
                        )
                    }
                }
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
                if (mine) repo.removeReaction(docId, threadId, commentId, emoji)
                else repo.addReaction(docId, threadId, commentId, emoji)
            }.onSuccess { load() }
        }
    }

    private fun textInlines(text: String): Any = listOf(
        mapOf("type" to "text", "text" to text, "styles" to emptyMap<String, Any?>()),
    )
}

private val commentMoshi: Moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

/** BlockNote inline JSON(或纯字符串)→ 纯文本。 */
private fun commentBodyText(body: Any?): String {
    if (body == null) return ""
    if (body is String) return body
    val inlines = runCatching {
        commentMoshi.adapter<List<JsonInlineDto>>(
            com.squareup.moshi.Types.newParameterizedType(List::class.java, JsonInlineDto::class.java),
        ).fromJsonValue(body)
    }.getOrNull() ?: return ""
    return inlines.flatMap(::inlineTexts).joinToString("")
}

private fun inlineTexts(inline: JsonInlineDto): List<String> {
    val text = inline.text.orEmpty()
    val nested = (inline.content as? List<*>)?.filterIsInstance<JsonInlineDto>()
        ?.flatMap(::inlineTexts) ?: emptyList()
    return listOf(text) + nested
}

private val PRESET_EMOJIS = listOf("👍", "❤️", "😂", "🎉")
