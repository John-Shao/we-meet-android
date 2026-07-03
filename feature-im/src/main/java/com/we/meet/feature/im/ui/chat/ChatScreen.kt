package com.we.meet.feature.im.ui.chat

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jusi.lightim.ConnectionState
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.R
import com.we.meet.feature.im.data.ChatUploadException
import com.we.meet.feature.im.ui.common.ConnectionStatusBar
import com.we.meet.feature.im.ui.common.ErrorBanner
import com.we.meet.feature.im.vm.ChatEvent
import com.we.meet.feature.im.vm.ChatViewModel
import kotlinx.coroutines.launch

/** Full-screen chat thread (no bottom tab bar) — app-level route `im_chat/{cid}`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    deps: ImDeps,
    cid: String,
    onBack: () -> Unit,
    onOpenInfo: (cid: String) -> Unit,
) {
    val vm: ChatViewModel =
        viewModel(key = "chat-$cid", factory = remember(deps, cid) { ChatViewModel.Factory(deps, cid) })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()
    // Recompose name labels when new identities resolve.
    val directoryVersion by vm.directoryVersion.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var lightboxKey by remember { mutableStateOf<String?>(null) }
    var showReceipts by remember { mutableStateOf(false) }
    // Long-press target for the action menu; and the message being replied to.
    var actionTarget by remember { mutableStateOf<com.jusi.lightim.Message?>(null) }
    var replyTarget by remember { mutableStateOf<com.jusi.lightim.Message?>(null) }

    // Read marking only while RESUMED — a backgrounded chat must not eat unread.
    LifecycleResumeEffect(Unit) {
        vm.setVisible(true)
        onPauseOrDispose { vm.setVisible(false) }
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                ChatEvent.RemovedFromConversation -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.im_removed_from_group),
                        Toast.LENGTH_SHORT,
                    ).show()
                    onBack()
                }
            }
        }
    }

    ui.uploadError?.let { code ->
        val msgRes = when (code) {
            ChatUploadException.Code.InvalidType -> R.string.im_upload_invalid_type
            ChatUploadException.Code.TooLarge -> R.string.im_upload_too_large
            ChatUploadException.Code.UploadError -> R.string.im_upload_failed
        }
        LaunchedEffect(code) {
            Toast.makeText(context, context.getString(msgRes), Toast.LENGTH_SHORT).show()
            vm.dismissUploadError()
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { vm.sendImage(it) } }
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { vm.sendFile(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = ui.title.ifBlank { stringResource(R.string.im_untitled_chat) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (ui.isGroup) {
                        IconButton(onClick = { onOpenInfo(cid) }) {
                            Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.im_group_info))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            ConnectionStatusBar(state = connection, onRetry = null)
            ui.error?.let { ErrorBanner(it) }

            val listState = rememberLazyListState()
            // reverseLayout: index 0 = visual bottom = newest message.
            val reversed = remember(ui.messages, directoryVersion) { ui.messages.asReversed() }

            // Auto-stick to the newest message when it arrives while at/near bottom.
            LaunchedEffect(ui.messages.lastOrNull()?.mid) {
                if (listState.firstVisibleItemIndex <= 1) {
                    listState.animateScrollToItem(0)
                }
            }
            // Top sentinel: fetch older pages as the user scrolls up.
            LaunchedEffect(listState, ui.hasMore) {
                if (!ui.hasMore) return@LaunchedEffect
                androidx.compose.runtime.snapshotFlow {
                    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                }.collect { lastVisible ->
                    if (lastVisible != null && lastVisible >= reversed.size - 3) {
                        vm.loadOlder()
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (ui.messages.isEmpty() && ui.pending.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.im_chat_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                } else {
                    val latestOwnSeq = ui.messages.lastOrNull { it.senderUid == ui.selfUid }?.seq
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // Pending uploads pin under the newest message (visual bottom).
                        items(ui.pending.asReversed(), key = { "pending-${it.localId}" }) { pending ->
                            PendingRow(kind = pending.kind)
                        }
                        items(reversed, key = { it.mid }) { message ->
                            val isOwn = message.senderUid == ui.selfUid
                            val sender = vm.resolveUser(message.senderUid)
                            val receipt = if (isOwn && message.seq == latestOwnSeq) {
                                receiptLabel(
                                    isGroup = ui.isGroup,
                                    readCount = vm.readCountFor(message.seq),
                                    memberCount = (ui.memberUids.size - 1).coerceAtLeast(0),
                                )
                            } else null
                            MessageBubble(
                                message = message,
                                isOwn = isOwn,
                                isGroup = ui.isGroup,
                                senderName = sender?.displayName,
                                senderAvatarUrl = sender?.avatarUrl?.takeIf { it.isNotBlank() },
                                receiptLabel = receipt,
                                onReceiptClick = if (receipt != null && ui.isGroup) {
                                    { showReceipts = true }
                                } else null,
                                onImageClick = { key -> lightboxKey = key },
                                onFileClick = { key, _ ->
                                    scope.launch {
                                        val url = vm.resolveMediaUrl(key)
                                        if (url != null) {
                                            runCatching {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                )
                                            }.onFailure {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.im_file_open_failed),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    }
                                },
                                resolveMediaUrl = { key -> vm.resolveMediaUrl(key) },
                                recalled = message.mid in ui.recalledMids,
                                reactions = ui.reactions[message.mid].orEmpty(),
                                onLongPress = if (message.mid !in ui.recalledMids) {
                                    { actionTarget = message }
                                } else null,
                            )
                        }
                    }
                }
            }

            MessageInputBar(
                canSend = connection == ConnectionState.CONNECTED,
                sentTick = ui.sentTick,
                replyPreview = replyTarget?.let { rt ->
                    val name = vm.resolveUser(rt.senderUid)?.displayName.orEmpty()
                    ReplyPreview(name, vm.snippetPreview(rt))
                },
                onClearReply = { replyTarget = null },
                onSend = { text ->
                    val rt = replyTarget
                    if (rt != null) {
                        vm.sendQuote(rt, text)
                        replyTarget = null
                    } else {
                        vm.sendText(text)
                    }
                },
                onPickImage = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onPickFile = { pickFile.launch(arrayOf("*/*")) },
            )
        }
    }

    actionTarget?.let { target ->
        MessageActionSheet(
            canRecall = vm.canRecall(target),
            myReactions = QUICK_REACTIONS.filter { vm.hasMyReaction(target.mid, it) }.toSet(),
            onReact = { emoji -> vm.toggleReaction(target, emoji) },
            onCopy = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(vm.snippetPreview(target)))
            },
            onReply = { replyTarget = target },
            onRecall = { vm.recall(target) },
            onDismiss = { actionTarget = null },
        )
    }

    lightboxKey?.let { key ->
        ImageLightbox(
            objectKey = key,
            resolveMediaUrl = { vm.resolveMediaUrl(it) },
            onDismiss = { lightboxKey = null },
        )
    }

    if (showReceipts && ui.isGroup) {
        val latestOwnSeq = ui.messages.lastOrNull { it.senderUid == ui.selfUid }?.seq
        if (latestOwnSeq != null) {
            ReadReceiptSheet(
                memberUids = ui.memberUids.filterNot { it == ui.selfUid },
                readMarkers = ui.readMarkers,
                seq = latestOwnSeq,
                resolveUser = { vm.resolveUser(it) },
                onDismiss = { showReceipts = false },
            )
        }
    }
}

/** Direct: 已读/未读. Group: "n/m人已读" (tap opens the roster sheet). */
@Composable
private fun receiptLabel(
    isGroup: Boolean,
    readCount: Int,
    memberCount: Int,
): String = if (!isGroup) {
    stringResource(if (readCount > 0) R.string.im_read else R.string.im_unread)
} else {
    stringResource(R.string.im_read_count, readCount, memberCount)
}

@Composable
private fun PendingRow(kind: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp))
        Text(
            text = stringResource(
                if (kind == "image") R.string.im_sending_image else R.string.im_sending_file
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Sender display name + text snippet of the message being replied to. */
data class ReplyPreview(val sender: String, val snippet: String)

@Composable
private fun MessageInputBar(
    canSend: Boolean,
    sentTick: Int,
    replyPreview: ReplyPreview?,
    onClearReply: () -> Unit,
    onSend: (String) -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    // Clear only after the ViewModel confirms an acked send, so a failed send
    // keeps the user's draft.
    LaunchedEffect(sentTick) {
        if (sentTick > 0) text = ""
    }
    Column {
        if (replyPreview != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${replyPreview.sender}: ${replyPreview.snippet}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClearReply, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.im_reply_cancel),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPickImage, enabled = canSend) {
            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = stringResource(R.string.im_attach_image))
        }
        IconButton(onClick = onPickFile, enabled = canSend) {
            Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.im_attach_file))
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.im_input_placeholder)) },
            enabled = canSend,
            maxLines = 4,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        )
        IconButton(
            onClick = { onSend(text) },
            enabled = canSend && text.isNotBlank(),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.im_input_send),
                tint = if (canSend && text.isNotBlank()) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
            )
        }
    }
    }
}
