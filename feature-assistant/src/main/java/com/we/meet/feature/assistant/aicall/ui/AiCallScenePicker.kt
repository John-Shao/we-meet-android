package com.we.meet.feature.assistant.aicall.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.we.meet.feature.assistant.R
import com.we.meet.feature.assistant.aicall.model.AiAgentConfigResponse
import com.we.meet.feature.assistant.aicall.model.AiCallSelection
import com.we.meet.feature.assistant.scenes.AssistantScene
import com.we.meet.ui.components.SettingsRow
import com.we.meet.ui.theme.Dimens

/** One choice across built-in scenarios and the existing server prompt catalog. */
@Composable
fun AiCallScenePicker(
    config: AiAgentConfigResponse?,
    selection: AiCallSelection,
    onSelectScene: (String?) -> Unit,
    onSelectPrompt: (String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var choosing by remember { mutableStateOf(false) }
    val scene = AssistantScene.find(selection.sceneId)
    val prompts = config?.prompts.orEmpty()
    val prompt = prompts.firstOrNull { it.id == selection.promptId }
    val title = stringResource(R.string.assistant_call_scene_title)
    val defaultLabel = stringResource(R.string.assistant_call_scene_general)
    val label = scene?.let { stringResource(it.label) } ?: prompt?.label ?: defaultLabel
    val hint = when {
        scene != null -> stringResource(scene.description)
        prompt != null -> stringResource(R.string.assistant_call_scene_catalog_hint, prompt.label)
        else -> stringResource(R.string.assistant_call_scene_general_hint)
    }
    Column(modifier) {
        OutlinedButton(onClick = { choosing = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("call-scene-picker")) {
            Text(stringResource(R.string.assistant_call_scene_selection, label))
        }
        Text(hint, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Dimens.SpaceS))
    }
    if (choosing && enabled) AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text(title) },
        text = {
            LazyColumn {
                item(key = "general") {
                    SettingsRow(label = defaultLabel,
                        subtitle = stringResource(R.string.assistant_call_scene_general_hint),
                        modifier = Modifier.testTag("call-scene-general"),
                        onClick = { onSelectPrompt(null); choosing = false },
                        trailing = { RadioButton(selected = scene == null && prompt == null, onClick = null) })
                }
                items(AssistantScene.entries, key = { "scene:${it.id}" }) { option ->
                    SettingsRow(label = stringResource(option.label), subtitle = stringResource(option.description),
                        modifier = Modifier.testTag("call-scene-${option.id}"),
                        onClick = { onSelectScene(option.id); choosing = false },
                        trailing = { RadioButton(selected = scene == option, onClick = null) })
                }
                items(prompts, key = { "prompt:${it.id}" }) { option ->
                    SettingsRow(label = option.label,
                        subtitle = stringResource(R.string.assistant_call_scene_catalog_hint, option.label),
                        modifier = Modifier.testTag("call-scene-prompt-${option.id}"),
                        onClick = { onSelectPrompt(option.id); choosing = false },
                        trailing = { RadioButton(selected = scene == null && prompt == option, onClick = null) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}
