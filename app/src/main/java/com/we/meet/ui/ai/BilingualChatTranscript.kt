package com.we.meet.ui.ai

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.we.meet.ui.theme.Dimens

/** Language sides stay fixed even when consecutive messages use the same direction. */
@Composable
internal fun BilingualChatTranscript(state: BilingualState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    var followLatest by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) followLatest = false
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollBackward }.collect { (scrolling, awayFromLatest) ->
            if (!scrolling && !awayFromLatest) followLatest = true
        }
    }
    LaunchedEffect(state.rows.lastOrNull(), followLatest) {
        if (followLatest && state.rows.isNotEmpty()) listState.animateScrollToItem(0)
    }
    val firstByLanguage = remember(state.rows) { state.rows.distinctBy { it.sourceLanguage }.map { it.id }.toSet() }
    // With the newest row at index zero, growing translations stay at the bottom;
    // stable keys preserve the reader's position while they browse older messages.
    LazyColumn(
        modifier.testTag("bilingual-chat"), state = listState, reverseLayout = true,
        contentPadding = PaddingValues(vertical = Dimens.SpaceL),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
    ) {
        items(state.rows.asReversed(), key = { it.id }) { row ->
            val onRight = row.sourceLanguage == state.pair.source
            val colors = MaterialTheme.colorScheme
            Box(Modifier.fillMaxWidth(), contentAlignment = if (onRight) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft) {
                Column(Modifier.fillMaxWidth(0.86f),
                    horizontalAlignment = if (onRight) AbsoluteAlignment.Right else AbsoluteAlignment.Left,
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                    if (row.id in firstByLanguage) {
                        Text(
                            stringResource(BilingualLanguages.label(row.sourceLanguage)),
                            Modifier.padding(horizontal = Dimens.SpaceS),
                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                        )
                    }
                    Surface(
                        modifier = Modifier.testTag("bilingual-chat-${row.id}"),
                        color = if (onRight) colors.primaryContainer else colors.surfaceContainerHigh,
                        contentColor = if (onRight) colors.onPrimaryContainer else colors.onSurface,
                        shape = RoundedCornerShape(
                            topStart = Dimens.CornerL, topEnd = Dimens.CornerL,
                            bottomStart = if (onRight) Dimens.CornerL else Dimens.CornerXs,
                            bottomEnd = if (onRight) Dimens.CornerXs else Dimens.CornerL,
                        ),
                    ) {
                        Column(Modifier.padding(Dimens.SpaceM), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                            if (row.source.isNotBlank()) SelectionContainer {
                                Text(row.source, style = MaterialTheme.typography.bodySmall,
                                    color = if (onRight) colors.onPrimaryContainer.copy(alpha = 0.78f) else colors.onSurfaceVariant)
                            }
                            if (row.text.isNotBlank()) SelectionContainer {
                                Text(row.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}
