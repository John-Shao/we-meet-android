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
) {
    Scaffold(topBar = {
        WeMeetTopBar(title = stringResource(R.string.bilingual_settings_title), onBack = onBack)
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(Dimens.SpaceL))
            SettingsGroup {
                com.we.meet.feature.assistant.scenes.AssistantScenePicker(state.sceneId, onSelectScene,
                    translationOnly = true, enabled = !state.active,
                    modifier = Modifier.padding(Dimens.ScreenPadding))
            }
            SettingsGroupHeader(stringResource(R.string.bilingual_languages))
            SettingsGroup {
                Box(Modifier.padding(horizontal = Dimens.ScreenPadding)) {
                    BilingualLanguageSelectors(state, onSelectLanguage)
                }
            }
            if (state.active) SettingsHint(stringResource(R.string.bilingual_settings_locked))
            SettingsGroupHeader(stringResource(R.string.bilingual_display_mode))
            SettingsGroup {
                listOf(true, false).forEach { faceToFace ->
                    if (!faceToFace) SettingsDivider()
                    SettingsRow(
                        label = stringResource(if (faceToFace) AssistantR.string.assistant_facing_mode else AssistantR.string.assistant_side_by_side_mode),
                        modifier = Modifier.testTag(if (faceToFace) "bilingual-mode-facing" else "bilingual-mode-side-by-side"),
                        onClick = { onFacingChange(faceToFace) },
                        trailing = { RadioButton(selected = facing == faceToFace, onClick = null) },
                    )
                }
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
