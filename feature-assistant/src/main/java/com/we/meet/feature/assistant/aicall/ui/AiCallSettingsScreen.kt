package com.we.meet.feature.assistant.aicall.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import com.we.meet.feature.assistant.R
import com.we.meet.feature.assistant.aicall.model.AiAgentConfigResponse
import com.we.meet.feature.assistant.aicall.model.AiCallSelection
import com.we.meet.feature.assistant.aicall.model.AiCallTransport
import com.we.meet.feature.assistant.aicall.model.AiCallVadMode
import com.we.meet.feature.assistant.history.AssistantHistoryPreference
import com.we.meet.feature.assistant.history.AssistantHistoryStore
import com.we.meet.ui.components.SettingsGroup
import com.we.meet.ui.components.SettingsGroupHeader
import com.we.meet.ui.components.SettingsHint
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens

/** Uses the call owner's preferences and callbacks without creating a second ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiCallSettingsScreen(
    config: AiAgentConfigResponse?,
    selection: AiCallSelection,
    historyStore: AssistantHistoryStore?,
    enabled: Boolean,
    onSelectTransport: (AiCallTransport) -> Unit,
    onSelectVoice: (String?) -> Unit,
    onSelectPrompt: (String?) -> Unit,
    onSelectScene: (String?) -> Unit,
    onBack: () -> Unit,
    onSelectVadMode: (AiCallVadMode) -> Unit,
    onSelectLocalPreviewFps: (Int) -> Unit,
    onSelectModelUploadFps: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize().testTag("call-settings-screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { WeMeetTopBar(title = stringResource(R.string.assistant_call_settings_title), onBack = onBack) },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())) {
            val dropdownPadding = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM)
            Spacer(Modifier.height(Dimens.SpaceL))
            SettingsGroup {
                AiCallScenePicker(config, selection, onSelectScene, onSelectPrompt,
                    modifier = Modifier.padding(Dimens.ScreenPadding), enabled = enabled)
            }
            SettingsGroupHeader(stringResource(R.string.assistant_call_transport))
            val transportLabels = listOf(
                stringResource(R.string.assistant_call_transport_webrtc),
                stringResource(R.string.assistant_call_transport_aoq),
            )
            SettingsGroup {
                CallSettingsDropdown(transportLabels[selection.transport.ordinal], transportLabels,
                    { onSelectTransport(AiCallTransport.entries[it]) }, enabled,
                    modifier = dropdownPadding.testTag("call-transport-picker"))
            }
            SettingsGroupHeader(stringResource(R.string.assistant_section_model))
            SettingsGroup {
                OutlinedTextField(value = "qwen3.8-omni-flash-realtime", onValueChange = {},
                    readOnly = true, singleLine = true, modifier = dropdownPadding.fillMaxWidth())
            }
            SettingsGroupHeader(stringResource(R.string.assistant_section_voice))
            val voices = config?.callProfile()?.voices.orEmpty()
            val resolved = config?.resolveSelection(selection)
            SettingsGroup {
                CallSettingsDropdown(
                    value = voices.firstOrNull { it.id == resolved?.voiceId }?.let { it.label ?: it.value } ?: "",
                    options = voices.map { it.label ?: it.value },
                    onSelect = { onSelectVoice(voices[it].id) },
                    enabled = enabled && voices.isNotEmpty(), modifier = dropdownPadding,
                )
            }
            SettingsGroupHeader(stringResource(R.string.assistant_call_vad_mode))
            val vadLabels = listOf(stringResource(R.string.assistant_call_vad_server),
                stringResource(R.string.assistant_call_vad_semantic))
            SettingsGroup {
                CallSettingsDropdown(vadLabels[selection.vadMode.ordinal], vadLabels,
                    { onSelectVadMode(AiCallVadMode.entries[it]) }, enabled,
                    modifier = dropdownPadding.testTag("call-vad-picker"),
                    optionTag = { "call-vad-${AiCallVadMode.entries[it].wireValue}" })
            }
            SettingsHint(stringResource(R.string.assistant_call_vad_hint))
            SettingsGroupHeader(stringResource(R.string.assistant_call_preview_fps))
            val previewRates = (10..30).toList()
            val previewLabels = previewRates.map { stringResource(R.string.assistant_call_fps_value, it) }
            SettingsGroup {
                CallSettingsDropdown(previewLabels[selection.videoSettings.localPreviewFps - 10], previewLabels,
                    { onSelectLocalPreviewFps(previewRates[it]) }, enabled,
                    modifier = dropdownPadding.testTag("call-preview-fps-picker"),
                    optionTag = { "call-preview-fps-${previewRates[it]}" })
            }
            SettingsGroupHeader(stringResource(R.string.assistant_call_upload_fps))
            val uploadRates = (1..10).toList()
            val uploadLabels = uploadRates.map { stringResource(R.string.assistant_call_fps_value, it) }
            SettingsGroup {
                CallSettingsDropdown(uploadLabels[selection.videoSettings.modelUploadFps - 1], uploadLabels,
                    { onSelectModelUploadFps(uploadRates[it]) }, enabled,
                    modifier = dropdownPadding.testTag("call-upload-fps-picker"),
                    optionTag = { "call-upload-fps-${uploadRates[it]}" })
            }
            SettingsHint(stringResource(R.string.assistant_call_fps_hint))
            if (historyStore != null) {
                Spacer(Modifier.height(Dimens.SpaceL))
                SettingsGroup {
                    Box(Modifier.padding(vertical = Dimens.SpaceS)) {
                        AssistantHistoryPreference(historyStore, kind = "call", enabled = enabled)
                    }
                }
            }
            Spacer(Modifier.height(Dimens.SpaceXl))
        }
    }
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
