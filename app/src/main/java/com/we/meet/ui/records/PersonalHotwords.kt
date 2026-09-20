package com.we.meet.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.PersonalHotwordsDto
import com.we.meet.data.repository.RecordingUploadRepository
import com.we.meet.data.repository.mergePersonalHotwords
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch
import retrofit2.HttpException

@Composable
internal fun PersonalHotwords(repository: RecordingUploadRepository, viewer: String, value: String, disabled: Boolean, onApply: (String) -> Unit) {
    key(viewer) { VocabularyEditor(repository, viewer, value, disabled, onApply) }
}

@Composable
private fun VocabularyEditor(repository: RecordingUploadRepository, viewer: String, value: String, disabled: Boolean, onApply: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var base by remember { mutableStateOf<PersonalHotwordsDto?>(null) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    fun read() {
        if (disabled || busy) return
        open = true; busy = true; base = null; message = null
        scope.launch {
            try {
                repository.personalHotwords(viewer).onSuccess { base = it; draft = it.words.joinToString("\n") }
                    .onFailure { draft = ""; message = R.string.personal_hotwords_error }
            } finally { busy = false }
        }
    }
    if (!open) {
        TextButton(enabled = !disabled, onClick = { read() }) { Text(stringResource(R.string.personal_hotwords_title)) }
        return
    }
    val locked = disabled || busy
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        Text(stringResource(R.string.personal_hotwords_title))
        Text(stringResource(R.string.personal_hotwords_hint))
        base?.let { saved ->
            OutlinedTextField(value = draft, onValueChange = { draft = it.take(4000); message = null }, enabled = !locked,
                label = { Text(stringResource(R.string.personal_hotwords_editor)) }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = !locked, onClick = { draft = value; message = null }) { Text(stringResource(R.string.personal_hotwords_copy_current)) }
            TextButton(enabled = !locked && draft != saved.words.joinToString("\n"), onClick = {
                if (!busy && !disabled) {
                    busy = true; message = null
                    scope.launch {
                        try {
                            repository.savePersonalHotwords(viewer, draft, saved.revision).onSuccess {
                                base = it; draft = it.words.joinToString("\n"); message = R.string.personal_hotwords_saved
                            }.onFailure { error ->
                                val code = (error as? HttpException)?.code()
                                if (code in listOf(401, 403, 404)) { base = null; draft = "" }
                                message = when {
                                    code == 409 -> R.string.personal_hotwords_conflict
                                    code == 400 || error is IllegalArgumentException -> R.string.personal_hotwords_limit
                                    else -> R.string.personal_hotwords_error
                                }
                            }
                        } finally { busy = false }
                    }
                }
            }) { Text(stringResource(R.string.personal_hotwords_save)) }
            TextButton(enabled = !locked && saved.words.isNotEmpty() && draft == saved.words.joinToString("\n"), onClick = {
                try { onApply(mergePersonalHotwords(value, saved.words)); message = R.string.personal_hotwords_applied }
                catch (_: IllegalArgumentException) { message = R.string.personal_hotwords_limit }
            }) { Text(stringResource(R.string.personal_hotwords_apply)) }
        }
        message?.let { Text(stringResource(it)) }
        TextButton(enabled = !locked, onClick = { read() }) { Text(stringResource(if (base == null) R.string.records_refresh else R.string.personal_hotwords_reload)) }
        TextButton(enabled = !busy, onClick = { open = false; base = null; draft = ""; message = null }) { Text(stringResource(R.string.personal_hotwords_close)) }
    }
}
