package com.we.meet.feature.assistant.scenes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.we.meet.feature.assistant.R
import com.we.meet.feature.assistant.aicall.ui.CallSettingsDropdown
import com.we.meet.ui.theme.Dimens

@Composable
fun TranslationScenePicker(
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scene = TranslationScene.find(selected)
    val options = TranslationScene.entries
    Column(modifier) {
        Text(stringResource(R.string.assistant_scene_title), style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceS))
        CallSettingsDropdown(
            value = stringResource(scene?.label ?: R.string.assistant_translation_scene_general),
            options = listOf(stringResource(R.string.assistant_translation_scene_general)) + options.map { stringResource(it.label) },
            onSelect = { onSelect(if (it == 0) null else options[it - 1].id) },
            enabled = enabled,
            modifier = Modifier.testTag("assistant-scene-picker"),
            optionTag = { if (it == 0) "assistant-scene-custom" else "assistant-scene-${options[it - 1].id}" },
        )
        Text(stringResource(R.string.assistant_scene_translation_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Dimens.SpaceS))
    }
}
