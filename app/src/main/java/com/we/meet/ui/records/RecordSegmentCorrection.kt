package com.we.meet.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

import com.we.meet.R
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.data.repository.RecordSourceChangedException
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens

import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import retrofit2.HttpException

/** Matches the server's bound, checked here so an over-long paste fails locally. */
private const val MAX_CORRECTION_LENGTH = 20_000

/** Owned by the record reader, so replacing a page does not discard edits. */
internal class OriginalCorrectionState(text: String, revision: Int) {
    val editing = mutableStateOf(false)
    val draft = mutableStateOf(text)
    val busy = mutableStateOf(false)
    val failed = mutableStateOf(false)
    val conflict = mutableStateOf(false)
    val editRevision = mutableStateOf(revision)
    var active = true
}

internal class OriginalCorrectionDrafts {
    private val states = mutableMapOf<String, OriginalCorrectionState>()
    fun get(segmentId: String, text: String, revision: Int) = states.getOrPut(segmentId) { OriginalCorrectionState(text, revision) }
    fun clear() {
        states.values.forEach { it.active = false; it.draft.value = ""; it.editing.value = false }
        states.clear()
    }
}

/**
 * The text a reader sees for one segment, plus the controls to correct it.
 *
 * The recogniser's own words stay reachable whenever the row has been corrected,
 * so an edit never silently replaces what was said. Editing is only offered when
 * [correctable] is true: an online transcript has no revision model and the
 * server refuses it, and a control whose only outcome is an error is worse than
 * no control.
 */
@Composable
internal fun CorrectableOriginalText(
    repository: MeetingRecordRepository,
    viewer: String,
    recordId: String,
    revision: Int,
    segmentId: String,
    text: String,
    originalText: String?,
    isCorrected: Boolean,
    correctable: Boolean,
    correctionRevision: Int,
    onCorrected: () -> Unit,
    onEditing: () -> Unit = {},
    draftState: OriginalCorrectionState? = null,
    writeScope: CoroutineScope? = null,
) {
    key(segmentId) {
        val state = draftState ?: remember { OriginalCorrectionState(text, correctionRevision) }
        var editing by state.editing
        var draft by state.draft
        var showingOriginal by remember { mutableStateOf(false) }
        var busy by state.busy
        var failed by state.failed
        var conflict by state.conflict
        var editRevision by state.editRevision
        val localScope = rememberCoroutineScope()
        val scope = writeScope ?: localScope

        fun submit(next: String?) {
            if (busy || !correctable || !state.active) return
            busy = true
            failed = false
            conflict = false
            scope.launch {
                val result = repository.correctOriginal(
                    viewer = viewer,
                    recordId = recordId,
                    revision = revision,
                    segmentId = segmentId,
                    text = next,
                    expectedRevision = if (next == null) correctionRevision else editRevision,
                )
                if (!state.active) return@launch
                busy = false
                if (result.isSuccess) {
                    editing = false
                    draft = ""
                    showingOriginal = false
                    // The projected text comes from the server, so re-read rather
                    // than patching this row locally.
                    onCorrected()
                } else {
                    failed = true
                    val error = result.exceptionOrNull()
                    conflict = error is RecordSourceChangedException || (error is HttpException && error.code() == 409)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            if (!editing) {
                Text(
                    if (showingOriginal && originalText != null) originalText else text,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (!editing && (correctable || isCorrected)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isCorrected) {
                        Text(
                            stringResource(R.string.records_correction_edited),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (originalText != null && originalText != text) {
                            TextButton(onClick = { showingOriginal = !showingOriginal }) {
                                Text(
                                    stringResource(
                                        if (showingOriginal) R.string.records_correction_hide_original
                                        else R.string.records_correction_show_original,
                                    )
                                )
                            }
                        }
                    }
                    if (correctable) {
                        TextButton(enabled = !busy, onClick = {
                            draft = text
                            editRevision = correctionRevision
                            failed = false
                            conflict = false
                            onEditing()
                            editing = true
                        }) {
                            Text(stringResource(R.string.records_correction_edit))
                        }
                    }
                    if (correctable && isCorrected) {
                        TextButton(enabled = !busy, onClick = { submit(null) }) {
                            Text(stringResource(R.string.records_correction_restore))
                        }
                    }
                }
            }

            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MAX_CORRECTION_LENGTH); failed = false },
                    enabled = !busy,
                    label = { Text(stringResource(R.string.records_correction_edit)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    TextButton(
                        enabled = correctable && !busy && draft.isNotBlank() && draft.trim() != text,
                        onClick = { submit(draft.trim()) },
                    ) {
                        Text(
                            stringResource(
                                if (busy) R.string.records_correction_saving
                                else R.string.records_correction_save,
                            )
                        )
                    }
                    TextButton(enabled = !busy, onClick = { editing = false; draft = "" }) {
                        Text(stringResource(R.string.records_correction_cancel))
                    }
                }
            }

            if (busy) WeMeetInlineLoading()
            if (failed) {
                Text(
                    stringResource(if (conflict) R.string.records_correction_conflict else R.string.records_correction_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Dimens.SpaceXs),
                )
            }
        }
    }
}
