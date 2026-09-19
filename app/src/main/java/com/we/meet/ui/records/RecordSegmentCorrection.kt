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
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens

import kotlinx.coroutines.launch

/** Matches the server's bound, checked here so an over-long paste fails locally. */
private const val MAX_CORRECTION_LENGTH = 20_000

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
    onCorrected: () -> Unit,
) {
    key(segmentId, text) {
        var editing by remember { mutableStateOf(false) }
        var draft by remember { mutableStateOf(text) }
        var showingOriginal by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        fun submit(next: String?) {
            if (busy) return
            busy = true
            failed = false
            scope.launch {
                val result = repository.correctOriginal(
                    viewer = viewer,
                    recordId = recordId,
                    revision = revision,
                    segmentId = segmentId,
                    text = next,
                )
                busy = false
                if (result.isSuccess) {
                    editing = false
                    showingOriginal = false
                    // The projected text comes from the server, so re-read rather
                    // than patching this row locally.
                    onCorrected()
                } else {
                    failed = true
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            if (correctable && !editing) {
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
                    TextButton(enabled = !busy, onClick = { draft = text; failed = false; editing = true }) {
                        Text(stringResource(R.string.records_correction_edit))
                    }
                    if (isCorrected) {
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
                        enabled = !busy && draft.isNotBlank() && draft.trim() != text,
                        onClick = { submit(draft.trim()) },
                    ) {
                        Text(
                            stringResource(
                                if (busy) R.string.records_correction_saving
                                else R.string.records_correction_save,
                            )
                        )
                    }
                    TextButton(enabled = !busy, onClick = { editing = false }) {
                        Text(stringResource(R.string.records_correction_cancel))
                    }
                }
            } else {
                Text(
                    if (showingOriginal && originalText != null) originalText else text,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            if (busy) WeMeetInlineLoading()
            if (failed) {
                Text(
                    stringResource(R.string.records_correction_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Dimens.SpaceXs),
                )
            }
        }
    }
}
