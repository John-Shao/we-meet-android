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

    LaunchedEffect(cid) {
        val uids = runCatching { session.client.listMembers(cid) }
            .getOrNull()?.map { it.uid }.orEmpty()
        val resolved = session.userDirectory.resolve(uids)
        excludeIds = resolved.values.mapNotNull { it.id.takeIf { id -> id.isNotBlank() } }.toSet()
    }

    val exclude = excludeIds ?: return // roster still resolving; picker mounts right after

    ContactPicker(
        deps = deps,
        mode = ContactPickerMode.Multi,
        excludeUserIds = exclude,
        onConfirm = { picked ->
            if (picked.isEmpty()) {
                onCancel()
                return@ContactPicker
            }
            scope.launch {
                runCatching { session.bridge.addMembers(cid, picked.map { it.userId }) }
                    .onSuccess { onDone() }
                    .onFailure { e ->
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
