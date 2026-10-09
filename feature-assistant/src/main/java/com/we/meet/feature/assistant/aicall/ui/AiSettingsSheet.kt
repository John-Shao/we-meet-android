package com.we.meet.feature.assistant.aicall.ui

import com.we.meet.ui.theme.Dimens
import com.we.meet.feature.assistant.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.we.meet.feature.assistant.aicall.model.AiAgentConfigResponse
import com.we.meet.feature.assistant.aicall.model.AiCallSelection
import com.we.meet.feature.assistant.history.AssistantHistoryPreference
import com.we.meet.feature.assistant.history.AssistantHistoryStore

/** Shared call settings; camera state only controls the published media. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsSheet(
    config: AiAgentConfigResponse?,
    selection: AiCallSelection,
    historyStore: AssistantHistoryStore?,
    historyEnabled: Boolean,
    onSelectTransport: (com.we.meet.feature.assistant.aicall.model.AiCallTransport) -> Unit,
    onSelectVoice: (String?) -> Unit,
    onSelectPrompt: (String?) -> Unit,
    onSelectScene: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = Dimens.SpaceXl)) {
            Spacer(modifier = Modifier.height(Dimens.SpaceM))
            AiCallScenePicker(config, selection, onSelectScene, onSelectPrompt,
                modifier = Modifier.padding(horizontal = Dimens.SpaceXl), enabled = historyEnabled)
            Spacer(modifier = Modifier.height(Dimens.SpaceM))
            SectionLabel(stringResource(R.string.assistant_call_transport))
            val transportLabels = listOf(
                stringResource(R.string.assistant_call_transport_webrtc),
                stringResource(R.string.assistant_call_transport_aoq),
            )
            CallSettingsDropdown(
                transportLabels[selection.transport.ordinal],
                transportLabels,
                { onSelectTransport(com.we.meet.feature.assistant.aicall.model.AiCallTransport.entries[it]) },
                historyEnabled,
            )
            CallConfigSection(config, selection, onSelectVoice, enabled = historyEnabled)
            Spacer(modifier = Modifier.height(Dimens.SpaceM))
            AssistantHistoryPreference(historyStore, kind = "call", enabled = historyEnabled,
                horizontalPadding = Dimens.SpaceXl)
            Spacer(modifier = Modifier.height(Dimens.SpaceM))
        }
    }
}

@Composable
private fun CallConfigSection(
    config: AiAgentConfigResponse?,
    selection: AiCallSelection,
    onSelectVoice: (String?) -> Unit,
    enabled: Boolean,
) {
    val profile = config?.callProfile()
    val resolved = config?.resolveSelection(selection)
    val voices = profile?.voices.orEmpty()

    SectionLabel(stringResource(R.string.assistant_section_model))
    OutlinedTextField(
        value = "qwen3.8-omni-flash-realtime",
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceXl),
    )
    Spacer(modifier = Modifier.height(Dimens.SpaceS))
    SectionLabel(stringResource(R.string.assistant_section_voice))
    CallSettingsDropdown(
        value = voices.firstOrNull { it.id == resolved?.voiceId }
            ?.let { it.label ?: it.value } ?: "",
        options = voices.map { it.label ?: it.value },
        onSelect = { onSelectVoice(voices[it].id) },
        enabled = enabled && voices.isNotEmpty(),
    )
    Spacer(modifier = Modifier.height(Dimens.SpaceS))
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceS),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallSettingsDropdown(
    value: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier.padding(horizontal = Dimens.SpaceXl),
    optionTag: ((Int) -> String)? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier.fillMaxWidth().semantics { if (!enabled) disabled() },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = enabled)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = Dimens.SheetContentMaxHeight),
        ) {
            options.forEachIndexed { idx, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    modifier = optionTag?.let { Modifier.testTag(it(idx)) } ?: Modifier,
                    onClick = {
                        onSelect(idx)
                        expanded = false
                    },
                )
            }
        }
    }
}
