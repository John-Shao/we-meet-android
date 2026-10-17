package com.we.meet.ui.ai

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.ui.theme.Dimens

/** Only complete, retained audio is offered for replay. */
@Composable
internal fun BilingualReplayButton(state: BilingualState, rowId: String, onReplay: (String) -> Unit) {
    if (rowId !in state.replayable) return
    TextButton(
        onClick = { onReplay(rowId) },
        enabled = !state.replaying && (!state.active || state.phase == BilingualPhase.LISTENING),
        modifier = Modifier.testTag("bilingual-replay-$rowId"),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null)
        Spacer(Modifier.size(Dimens.SpaceXs))
        Text(stringResource(R.string.bilingual_replay))
    }
}
