package com.we.meet.ui.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.we.meet.ui.theme.Dimens

/** Each participant reads all turns in their own language; direction remains automatic. */
@Composable
internal fun BilingualFaceToFace(state: BilingualState, onReplay: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        LanguagePanel(state, state.pair.target, Modifier.weight(1f).rotate(180f).testTag("bilingual-facing-partner"), onReplay)
        HorizontalDivider(Modifier.padding(vertical = Dimens.SpaceS))
        LanguagePanel(state, state.pair.source, Modifier.weight(1f).testTag("bilingual-facing-self"), onReplay)
    }
}

internal fun BilingualRow.forLanguage(language: String): String? = when (language) {
    sourceLanguage -> source
    targetLanguage -> text
    else -> null
}

@Composable
private fun LanguagePanel(state: BilingualState, language: String, modifier: Modifier, onReplay: (String) -> Unit) {
    val list = rememberLazyListState()
    val visible = state.rows.filter { it.forLanguage(language) != null }
    LaunchedEffect(visible.lastOrNull()) { if (visible.isNotEmpty()) list.animateScrollToItem(visible.lastIndex) }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(Dimens.SpaceM)) {
            Text(stringResource(BilingualLanguages.label(language)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                items(visible, key = { it.id }) { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                        SelectionContainer {
                            Text(row.forLanguage(language).orEmpty(), style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Normal)
                        }
                        if (language == row.targetLanguage) BilingualReplayButton(state, row.id, onReplay)
                    }
                }
            }
        }
    }
}
