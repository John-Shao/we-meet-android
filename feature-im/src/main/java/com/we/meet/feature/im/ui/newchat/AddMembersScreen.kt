package com.we.meet.feature.im.ui.newchat

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.we.meet.core.directory.ui.ContactPicker
import com.we.meet.core.directory.ui.ContactPickerMode
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.R
import com.we.meet.feature.im.userMessageRes
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetLoading
import kotlinx.coroutines.launch

/**
 * 拉人进群 — ContactPicker excluding current members. The roster arrives as IM
 * uids; they resolve to we-meet user ids via UserDirectory before exclusion.
 */
@Composable
fun AddMembersScreen(
    deps: ImDeps,
    cid: String,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val session = remember(deps) { ImSession.get(deps) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var excludeIds by remember { mutableStateOf<Set<String>?>(null) }
    var rosterLoadFailed by remember { mutableStateOf(false) }
    var rosterReloadTick by remember { mutableStateOf(0) }
    var submitting by remember { mutableStateOf(false) }

    LaunchedEffect(cid, rosterReloadTick) {
        excludeIds = null
        rosterLoadFailed = false
        runCatching {
            val uids = session.client.listMembers(cid).map { it.uid }
            val resolved = session.userDirectory.resolve(uids)
            resolved.values.mapNotNull { member ->
                member.id.takeIf(String::isNotBlank)
            }.toSet()
        }.onSuccess {
            excludeIds = it
        }.onFailure {
            rosterLoadFailed = true
        }
    }

    if (rosterLoadFailed) {
        WeMeetErrorState(
            message = context.getString(R.string.im_group_members_load_failed),
            onRetry = { rosterReloadTick += 1 },
        )
        return
    }
    val exclude = excludeIds
    if (exclude == null) {
        WeMeetLoading()
        return
    }

    ContactPicker(
        deps = deps,
        mode = ContactPickerMode.Multi,
        excludeUserIds = exclude,
        enabled = !submitting,
        onConfirm = { picked ->
            if (picked.isEmpty()) {
                onCancel()
                return@ContactPicker
            }
            submitting = true
            scope.launch {
                runCatching { session.bridge.addMembers(cid, picked.map { it.userId }) }
                    .onSuccess { onDone() }
                    .onFailure { e ->
                        submitting = false
                        Toast.makeText(
                            context,
                            "${context.getString(R.string.im_create_chat_failed)}: " +
                                context.getString(e.userMessageRes()),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
            }
        },
        onDismiss = onCancel,
    )
}
