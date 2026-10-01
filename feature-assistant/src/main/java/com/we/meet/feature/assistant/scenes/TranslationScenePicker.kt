package com.we.meet.feature.assistant.scenes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.we.meet.feature.assistant.R
import com.we.meet.ui.components.SettingsRow
import com.we.meet.ui.theme.Dimens

@Composable
fun TranslationScenePicker(
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var choosing by remember { mutableStateOf(false) }
    val scene = TranslationScene.find(selected)
    val options = TranslationScene.entries
    Column(modifier) {
        OutlinedButton(onClick = { choosing = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("assistant-scene-picker")) {
            Text(stringResource(R.string.assistant_scene_title) + ": " +
                stringResource(scene?.label ?: R.string.assistant_translation_scene_general))
        }
        Text(stringResource(R.string.assistant_scene_translation_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Dimens.SpaceS))
    }
    if (choosing && enabled) AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text(stringResource(R.string.assistant_scene_title)) },
        text = {
            LazyColumn {
                item {
                    SettingsRow(label = stringResource(R.string.assistant_translation_scene_general),
                        modifier = Modifier.testTag("assistant-scene-custom"),
                        onClick = { onSelect(null); choosing = false },
                        trailing = { RadioButton(selected = scene == null, onClick = null) })
                }
                items(options, key = { it.id }) { option ->
                    SettingsRow(label = stringResource(option.label),
                        modifier = Modifier.testTag("assistant-scene-${option.id}"),
                        onClick = { onSelect(option.id); choosing = false },
                        trailing = { RadioButton(selected = scene == option, onClick = null) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}
