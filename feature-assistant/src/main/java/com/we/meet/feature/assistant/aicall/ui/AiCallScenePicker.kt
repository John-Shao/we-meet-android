package com.we.meet.feature.assistant.aicall.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.we.meet.feature.assistant.R
import com.we.meet.feature.assistant.aicall.model.AiAgentConfigResponse
import com.we.meet.feature.assistant.aicall.model.AiCallSelection
import com.we.meet.feature.assistant.scenes.AssistantScene
import com.we.meet.ui.theme.Dimens

/** Scene choices and instructions come from the server catalog. */
@Composable
fun AiCallScenePicker(
    config: AiAgentConfigResponse?,
    selection: AiCallSelection,
    onSelectScene: (String?) -> Unit,
    onSelectPrompt: (String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val prompts = config?.prompts.orEmpty()
    val prompt = prompts.firstOrNull { it.id == selection.promptId }
    val scene = AssistantScene.find(prompt?.code?.removePrefix("call.scene."))
    val title = stringResource(R.string.assistant_call_scene_title)
    val defaultLabel = stringResource(R.string.assistant_call_scene_general)
    val label = prompt?.label ?: defaultLabel
    val hint = when {
        scene != null -> stringResource(scene.description)
        prompt != null -> stringResource(R.string.assistant_call_scene_catalog_hint, prompt.label)
        else -> stringResource(R.string.assistant_call_scene_general_hint)
    }
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceS))
        CallSettingsDropdown(
            value = label,
            options = listOf(defaultLabel) + prompts.map { it.label },
            onSelect = { index ->
                onSelectPrompt(if (index == 0) null else prompts[index - 1].id)
            },
            enabled = enabled,
            modifier = Modifier.testTag("call-scene-picker"),
        )
        Text(hint, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Dimens.SpaceS))
    }
}
