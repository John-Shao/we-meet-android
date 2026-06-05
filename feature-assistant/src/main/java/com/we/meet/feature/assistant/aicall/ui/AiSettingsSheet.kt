package com.we.meet.feature.assistant.aicall.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.we.meet.feature.assistant.aicall.model.AiAgentConfigResponse
import com.we.meet.feature.assistant.aicall.model.AiCallMode
import com.we.meet.feature.assistant.aicall.model.AiModeSelection
import com.we.meet.feature.assistant.aicall.model.AiProfileDto

/** Sentinel label shown in the prompt dropdown when nothing is selected. */
private const val NO_PROMPT_LABEL = "默认"

/**
 * AI agent configuration sheet — entered from 「AI → 打电话 → 设置」.
 *
 * Two tabs (语音通话 / 视频通话). Each tab independently picks model →
 * voice → prompt. The voice list depends on the picked model, the prompt
 * list is shared across both tabs (and across users).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsSheet(
    config: AiAgentConfigResponse?,
    initialTab: AiCallMode,
    voiceSelection: AiModeSelection,
    videoSelection: AiModeSelection,
    onSelectProfile: (AiCallMode, profileCode: String?) -> Unit,
    onSelectVoice: (AiCallMode, voiceId: String?) -> Unit,
    onSelectPrompt: (AiCallMode, promptId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var activeTab by remember(initialTab) { mutableStateOf(initialTab) }

    val cfg = config
    val audioProfiles = cfg?.profiles.orEmpty().filter { it.isAudio }
    val videoProfiles = cfg?.profiles.orEmpty().filter { it.isVideo }
    val prompts = cfg?.prompts.orEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            TabRow(selectedTabIndex = if (activeTab == AiCallMode.Voice) 0 else 1) {
                Tab(
                    selected = activeTab == AiCallMode.Voice,
                    onClick = { activeTab = AiCallMode.Voice },
                    text = { Text("语音通话") },
                )
                Tab(
                    selected = activeTab == AiCallMode.Video,
                    onClick = { activeTab = AiCallMode.Video },
                    text = { Text("视频通话") },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val selection = if (activeTab == AiCallMode.Voice) voiceSelection else videoSelection
            val profiles = if (activeTab == AiCallMode.Voice) audioProfiles else videoProfiles
            ModeConfigSection(
                profiles = profiles,
                prompts = prompts,
                selection = selection,
                onSelectProfile = { code -> onSelectProfile(activeTab, code) },
                onSelectVoice = { id -> onSelectVoice(activeTab, id) },
                onSelectPrompt = { id -> onSelectPrompt(activeTab, id) },
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ModeConfigSection(
    profiles: List<AiProfileDto>,
    prompts: List<com.we.meet.feature.assistant.aicall.model.AiPromptDto>,
    selection: AiModeSelection,
    onSelectProfile: (String?) -> Unit,
    onSelectVoice: (String?) -> Unit,
    onSelectPrompt: (String?) -> Unit,
) {
    // Resolved profile: explicit user pick (validated against the list)
    // → first profile in this tab. The dropdown always reflects whatever
    // would actually be used at call start, not a stale stored code.
    val resolvedProfile = remember(profiles, selection.profileCode) {
        selection.profileCode?.let { code -> profiles.firstOrNull { it.code == code } }
            ?: profiles.firstOrNull()
    }
    val resolvedVoiceId = remember(resolvedProfile, selection.voiceId) {
        val voices = resolvedProfile?.voices.orEmpty()
        when {
            selection.voiceId != null && voices.any { it.id == selection.voiceId } -> selection.voiceId
            else -> resolvedProfile?.default_voice_id ?: voices.firstOrNull()?.id
        }
    }

    // 模型
    SectionLabel("模型")
    Dropdown(
        value = resolvedProfile?.display_name?.takeIf { it.isNotBlank() }
            ?: resolvedProfile?.code
            ?: "暂无可用模型",
        options = profiles.map { it.display_name?.takeIf { n -> n.isNotBlank() } ?: it.code },
        onSelect = { idx -> onSelectProfile(profiles[idx].code) },
        enabled = profiles.size > 1,
    )

    Spacer(modifier = Modifier.height(8.dp))

    // 音色
    val voices = resolvedProfile?.voices.orEmpty()
    val voiceLabels = voices.map { it.label ?: it.value }
    val voiceIndex = voices.indexOfFirst { it.id == resolvedVoiceId }.takeIf { it >= 0 } ?: 0
    SectionLabel("音色")
    Dropdown(
        value = voiceLabels.getOrNull(voiceIndex) ?: "",
        options = voiceLabels,
        onSelect = { idx -> onSelectVoice(voices.getOrNull(idx)?.id) },
        enabled = voices.size > 1,
    )

    Spacer(modifier = Modifier.height(8.dp))

    // 提示词 — 第一项是「默认」（null），后续为后端目录中的 prompt
    val promptOptions = buildList {
        add(NO_PROMPT_LABEL)
        addAll(prompts.map { it.label })
    }
    val currentPromptLabel = selection.promptId
        ?.let { id -> prompts.firstOrNull { it.id == id }?.label }
        ?: NO_PROMPT_LABEL
    SectionLabel("提示词")
    Dropdown(
        value = currentPromptLabel,
        options = promptOptions,
        onSelect = { idx ->
            if (idx == 0) {
                onSelectPrompt(null)
            } else {
                onSelectPrompt(prompts[idx - 1].id)
            }
        },
        enabled = promptOptions.size > 1,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF666666),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(
    value: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
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
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp),
        ) {
            options.forEachIndexed { idx, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(idx)
                        expanded = false
                    },
                )
            }
        }
    }
}
