package com.we.meet.ui.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.we.meet.R
import com.we.meet.feature.assistant.aicall.ui.CallSettingsDropdown
import com.we.meet.feature.assistant.history.AssistantHistoryPreference
import com.we.meet.feature.assistant.history.AssistantHistoryStore
import com.we.meet.ui.components.SettingsDivider
import com.we.meet.ui.components.SettingsGroup
import com.we.meet.ui.components.SettingsGroupHeader
import com.we.meet.ui.components.SettingsHint
import com.we.meet.ui.components.SettingsRow
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import com.we.meet.feature.assistant.R as AssistantR

/** Shares the parent screen's controller so visiting settings keeps translation running. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BilingualTranslationSettingsScreen(
    state: BilingualState,
    facing: Boolean,
    history: AssistantHistoryStore?,
    onSelectLanguage: (Boolean, String) -> Unit,
    onFacingChange: (Boolean) -> Unit,
    onSoundChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSelectScene: (String?) -> Unit,
    onDirectAoqChange: (Boolean) -> Unit = {},
    onFixedSourceChange: (String?) -> Unit = {},
    onVoiceChange: (String) -> Unit = {},
) {
    Scaffold(topBar = {
        WeMeetTopBar(title = stringResource(R.string.bilingual_settings_title), onBack = onBack)
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(Dimens.SpaceL))
            SettingsGroup {
                com.we.meet.feature.assistant.scenes.TranslationScenePicker(state.sceneId, onSelectScene,
                    enabled = !state.active,
                    modifier = Modifier.padding(Dimens.ScreenPadding))
            }
            val dropdownPadding = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM)
            SettingsGroupHeader(stringResource(R.string.bilingual_transport))
            SettingsGroup {
                val options = listOf(stringResource(R.string.bilingual_transport_aoq), stringResource(R.string.bilingual_transport_webrtc))
                CallSettingsDropdown(options[if (state.directAoq) 0 else 1], options,
                    onSelect = { onDirectAoqChange(it == 0) }, enabled = !state.active,
                    modifier = dropdownPadding.testTag("bilingual-transport-picker"),
                    optionTag = { if (it == 0) "bilingual-aoq" else "bilingual-webrtc" })
            }
            SettingsGroupHeader(stringResource(R.string.bilingual_direction_settings))
            SettingsGroup {
                val options = listOf(stringResource(R.string.bilingual_direction_auto), stringResource(R.string.bilingual_direction_manual))
                CallSettingsDropdown(options[if (state.fixedSource == null) 0 else 1], options,
                    onSelect = { onFixedSourceChange(if (it == 0) null else state.pair.source) }, enabled = !state.active,
                    modifier = dropdownPadding.testTag("bilingual-direction-picker"),
                    optionTag = { if (it == 0) "bilingual-direction-auto" else "bilingual-direction-manual" })
            }
            SettingsHint(stringResource(R.string.bilingual_direction_hint))
            SettingsGroupHeader(stringResource(R.string.bilingual_languages))
            SettingsGroup {
                Box(Modifier.padding(horizontal = Dimens.ScreenPadding)) {
                    BilingualLanguageSelectors(state, onSelectLanguage)
                }
            }
            if (state.active) SettingsHint(stringResource(R.string.bilingual_settings_locked))
            SettingsGroupHeader(stringResource(R.string.bilingual_display_mode))
            SettingsGroup {
                val options = listOf(stringResource(AssistantR.string.assistant_facing_mode), stringResource(AssistantR.string.assistant_side_by_side_mode))
                CallSettingsDropdown(options[if (facing) 0 else 1], options,
                    onSelect = { onFacingChange(it == 0) }, enabled = true,
                    modifier = dropdownPadding.testTag("bilingual-display-picker"),
                    optionTag = { if (it == 0) "bilingual-mode-facing" else "bilingual-mode-side-by-side" })
            }
            SettingsGroupHeader(stringResource(AssistantR.string.assistant_section_voice))
            SettingsGroup {
                val voices = state.voiceConfig?.voices?.map { it.value } ?: BilingualVoices.labels.keys.toList()
                val labels = state.voiceConfig?.voices?.map { it.label } ?: voices.map { stringResource(BilingualVoices.label(it)) }
                CallSettingsDropdown(labels.getOrNull(voices.indexOf(state.voice)) ?: "—",
                    labels,
                    onSelect = { onVoiceChange(voices[it]) }, enabled = !state.active && voices.isNotEmpty(),
                    modifier = dropdownPadding.testTag("bilingual-voice-picker"),
                    optionTag = { "bilingual-voice-${voices[it]}" })
            }
            Spacer(Modifier.height(Dimens.SpaceL))
            SettingsGroup {
                val soundLabel = stringResource(R.string.bilingual_sound)
                SettingsRow(label = soundLabel, trailing = {
                    Switch(checked = state.sound, onCheckedChange = onSoundChange,
                        modifier = Modifier.testTag("bilingual-sound").semantics { contentDescription = soundLabel })
                })
                if (history != null) {
                    SettingsDivider()
                    Box(Modifier.padding(vertical = Dimens.SpaceS)) {
                        AssistantHistoryPreference(history, kind = "translation", enabled = !state.active)
                    }
                }
            }
            SettingsHint(stringResource(R.string.bilingual_hint))
            Spacer(Modifier.height(Dimens.SpaceXl))
        }
    }
}
