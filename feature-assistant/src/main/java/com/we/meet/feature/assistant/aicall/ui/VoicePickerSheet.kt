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

private const val DEFAULT_PROMPT_LABEL = "默认"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerSheet(
    config: AiAgentConfigResponse?,
    selectedVoiceIndex: Int,
    selectedPromptLabel: String?,
    onSelectVoice: (Int) -> Unit,
    onSelectPrompt: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Voices for the voice-mode profile (resolved from the backend catalog);
    // backend supplies the display labels.
    val voiceOptions = config?.voiceProfile()?.voices.orEmpty()
        .map { it.label ?: it.value }

    val promptOptions = buildList {
        add(DEFAULT_PROMPT_LABEL)
        addAll(config?.prompts.orEmpty().map { it.label })
    }
    val currentPromptLabel = selectedPromptLabel ?: DEFAULT_PROMPT_LABEL

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            SectionLabel("声音")
            Dropdown(
                value = voiceOptions.getOrNull(selectedVoiceIndex) ?: "",
                options = voiceOptions,
                onSelect = { idx -> onSelectVoice(idx) },
                enabled = voiceOptions.isNotEmpty(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            SectionLabel("对话风格")
            Dropdown(
                value = currentPromptLabel,
                options = promptOptions,
                onSelect = { idx ->
                    val picked = promptOptions[idx]
                    onSelectPrompt(if (picked == DEFAULT_PROMPT_LABEL) null else picked)
                },
                enabled = promptOptions.size > 1,
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
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
