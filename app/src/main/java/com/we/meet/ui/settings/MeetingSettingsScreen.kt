package com.we.meet.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                transparent = true,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            CodecSection(
                selected = selectedCodec,
                onSelect = settingsStore::setVideoCodec,
            )
        }
    }
}

@Composable
private fun CodecSection(
    selected: VideoCodecPref,
    onSelect: (VideoCodecPref) -> Unit,
) {
    Spacer(Modifier.height(Dimens.SpaceS))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .clip(RoundedCornerShape(Dimens.CornerM))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        CodecDropdownRow(
            label = stringResource(R.string.settings_video_codec),
            selected = selected,
            onSelect = onSelect,
        )
    }

    Spacer(Modifier.height(Dimens.SpaceS))
    Text(
        text = stringResource(R.string.settings_video_codec_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.SpaceXl),
    )
    Spacer(Modifier.height(Dimens.SpaceL))
}

@Composable
private fun CodecDropdownRow(
    label: String,
    selected: VideoCodecPref,
    onSelect: (VideoCodecPref) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    // Wrap the trigger row + DropdownMenu in a wrapContentSize Box so the
    // menu anchors to the row's right edge rather than the screen's top-left.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.weight(1f))
        Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = selectedDisplay(selected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(Dimens.SpaceXxs))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                VideoCodecPref.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(itemDisplay(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                        trailingIcon = if (option == selected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(Dimens.IconSmall),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun selectedDisplay(option: VideoCodecPref): String {
    val suffix = stringResource(R.string.settings_video_codec_default_suffix)
    return if (option == VideoCodecPref.DEFAULT) "${option.displayLabel} $suffix"
    else option.displayLabel
}

@Composable
private fun itemDisplay(option: VideoCodecPref): String = selectedDisplay(option)
