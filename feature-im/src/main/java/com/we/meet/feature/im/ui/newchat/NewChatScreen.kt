package com.we.meet.feature.im.ui.newchat

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.we.meet.core.directory.ui.ContactPicker
import com.we.meet.core.directory.ui.ContactPickerMode
import com.we.meet.core.directory.ui.PickedMember
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.R
import com.we.meet.feature.im.userMessageRes
import kotlinx.coroutines.launch

/**
 * "+ 新建" flow — a ContactPicker over the org directory:
 * one selection → create-or-get direct chat; several → name dialog → group.
 */
@Composable
fun NewChatScreen(
    deps: ImDeps,
    onChatReady: (cid: String) -> Unit,
    onCancel: () -> Unit,
    /** 从直聊「新建群聊」进入时预选的对端 userId(在选人器里预勾选)。 */
    preselectUserId: String? = null,
) {
    val session = remember(deps) { ImSession.get(deps) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val creationGuard = remember { ConversationCreationGuard() }

    var pickedForGroup by remember { mutableStateOf<List<PickedMember>?>(null) }
    var creating by remember { mutableStateOf(false) }

    fun fail(e: Throwable) {
        Toast.makeText(
            context,
            "${context.getString(R.string.im_create_chat_failed)}: " +
                context.getString(e.userMessageRes()),
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun submit(create: suspend () -> String) {
        if (!creationGuard.tryStart()) return
        creating = true
        scope.launch {
            try {
                runConversationCreation(
                    create = create,
                    refresh = { session.conversations.refresh() },
                    onReady = onChatReady,
                    onFailure = ::fail,
                    onRefreshFailure = { error ->
                        Log.w("NewChatScreen", "conversation refresh failed", error)
                    },
                )
            } finally {
                creationGuard.finish()
                creating = false
            }
        }
    }

    if (pickedForGroup == null) {
        ContactPicker(
            deps = deps,
            mode = ContactPickerMode.Multi,
            enabled = !creating,
            preselectUserIds = setOfNotNull(preselectUserId),
            onConfirm = { picked ->
                when {
                    picked.isEmpty() -> onCancel()
                    picked.size == 1 -> {
                        submit {
                            session.bridge
                                .createDirectByUserId(picked.first().userId)
                                .cid
                        }
                    }
                    else -> pickedForGroup = picked
                }
            },
            onDismiss = onCancel,
        )
    }

    pickedForGroup?.let { picked ->
        var name by remember {
            // Default group name: first few member names, Feishu-style.
            mutableStateOf(picked.take(3).joinToString("、") { it.displayName }.take(40))
        }
        AlertDialog(
            onDismissRequest = { if (!creating) pickedForGroup = null },
            title = { Text(stringResource(R.string.im_new_group_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    placeholder = { Text(stringResource(R.string.im_new_group_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        submit {
                            session.bridge
                                .createGroup(picked.map { it.userId }, name.trim())
                                .cid
                        }
                    },
                    enabled = name.trim().isNotEmpty() && !creating,
                ) {
                    if (creating) {
                        CircularProgressIndicator(modifier = Modifier)
                    } else {
                        Text(stringResource(R.string.im_action_confirm))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pickedForGroup = null }, enabled = !creating) {
                    Text(stringResource(R.string.im_action_cancel))
                }
            },
        )
    }
}
