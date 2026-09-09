package com.we.meet.feature.docs.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.data.net.DocumentDto
import com.we.meet.ui.components.WeMeetInlineLoading

/** A failed save keeps the entered title available for retry on either entry point. */
@Composable
internal fun DocsRenameDialog(
    doc: DocumentDto,
    onDismiss: () -> Unit,
    onConfirm: (String, (Boolean) -> Unit) -> Unit,
    titleRes: Int = R.string.docs_rename_title,
    confirmRes: Int = R.string.docs_rename_confirm,
) {
    var title by rememberSaveable(doc.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(doc.displayTitle, TextRange(0, doc.displayTitle.length)))
    }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val canSubmit = title.text.isNotBlank() && title.text.trim() != doc.displayTitle && !saving
    fun submit() {
        if (saving || title.text.isBlank() || title.text.trim() == doc.displayTitle) return
        saving = true
        failed = false
        onConfirm(title.text.trim()) { success ->
            saving = false
            failed = !success
            if (success) onDismiss()
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    label = { Text(stringResource(R.string.docs_create_hint)) },
                    enabled = !saving,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    isError = failed,
                    supportingText = { if (failed) Text(stringResource(R.string.docs_action_failed)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = canSubmit) {
                if (saving) WeMeetInlineLoading() else Text(stringResource(confirmRes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.docs_cancel)) }
        },
    )
}
