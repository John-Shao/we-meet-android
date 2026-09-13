package com.we.meet.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.ui.components.SettingsGroup
import com.we.meet.ui.components.SettingsHint
import com.we.meet.ui.components.SettingsPickerOption
import com.we.meet.ui.components.SettingsPickerSheet
import com.we.meet.ui.components.SettingsRow
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.settings.VideoCodecPref
import com.we.meet.ui.theme.Dimens

/**
 * 会议设置 — meeting-scoped preferences reached from the 会议 tab's top-right
 * gear. Currently the video codec (a per-meeting media knob), split out of the
 * general Settings page so it lives next to the meeting surface rather than
 * mixed in with device-wide theme/language preferences.
 *
 * 版式与设置总页同一套(共享 `SettingsList` 组件):白卡片 + 卡片下方那句说明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingSettingsScreen(
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val settingsStore = app.settingsStore
    val selectedCodec by settingsStore.videoCodec.collectAsStateWithLifecycle()

    var backPending by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.meeting_settings_title),
                onBack = { if (!backPending) { backPending = true; onBack() } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(Dimens.SpaceL))
            CodecRow(
                selected = selectedCodec,
                onSelect = settingsStore::setVideoCodec,
            )
            Spacer(Modifier.height(Dimens.SpaceXl))
        }
    }
}

@Composable
private fun CodecRow(
    selected: VideoCodecPref,
    onSelect: (VideoCodecPref) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }

    SettingsGroup {
        SettingsRow(
            label = stringResource(R.string.settings_video_codec),
            value = codecLabel(selected),
            onClick = { picking = true },
        )
    }
    SettingsHint(stringResource(R.string.settings_video_codec_hint))

    if (picking) {
        SettingsPickerSheet(
            title = stringResource(R.string.settings_video_codec),
            options = VideoCodecPref.entries.map { option ->
                SettingsPickerOption(
                    label = codecLabel(option),
                    selected = option == selected,
                    onSelect = { onSelect(option) },
                )
            },
            onDismissRequest = { picking = false },
        )
    }
}

/** 当前值与被选项用同一个文案:「默认」那档带上后缀,一眼看出不选就是它。 */
@Composable
private fun codecLabel(option: VideoCodecPref): String {
    val suffix = stringResource(R.string.settings_video_codec_default_suffix)
    return if (option == VideoCodecPref.DEFAULT) "${option.displayLabel} $suffix"
    else option.displayLabel
}
