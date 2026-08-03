package com.we.meet.ui.approval

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.rememberDatePickerState
import java.time.Instant
import java.time.ZoneOffset
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.R
import com.we.meet.ui.theme.Dimens
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.ApprovalTemplateDto
import com.we.meet.data.api.dto.SubmitApprovalRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SubmitUiState(
    val templates: List<ApprovalTemplateDto> = emptyList(),
    val loading: Boolean = true,
    val submitting: Boolean = false,
    val error: Boolean = false,
)

class SubmitApprovalViewModel(
    app: Application,
    private val presetTemplateId: String?,
) : AndroidViewModel(app) {

    private val api = (app as WeMeetApp).apiClient.approvalApi
    private val _ui = MutableStateFlow(SubmitUiState())
    val ui: StateFlow<SubmitUiState> = _ui.asStateFlow()

    /** Template chosen initially (preset or first), set once templates load. */
    var initialTemplateId: String? = null
        private set

    init {
        loadTemplates()
    }

    fun loadTemplates() {
        _ui.update { it.copy(loading = true, error = false) }
        viewModelScope.launch {
            runCatching { api.listTemplates() }
                .onSuccess { page ->
                    initialTemplateId = presetTemplateId ?: page.results.firstOrNull()?.id
                    _ui.update { it.copy(templates = page.results, loading = false) }
                }
                .onFailure { _ui.update { it.copy(loading = false, error = true) } }
        }
    }

    fun submit(templateId: String, formData: Map<String, String>, onDone: () -> Unit) {
        if (_ui.value.submitting) return
        _ui.update { it.copy(submitting = true, error = false) }
        viewModelScope.launch {
            runCatching { api.submit(SubmitApprovalRequest(template = templateId, formData = formData)) }
                .onSuccess { onDone() }
                .onFailure { _ui.update { it.copy(submitting = false, error = true) } }
        }
    }

    companion object {
        fun factory(app: Application, presetTemplateId: String?): ViewModelProvider.Factory =
            viewModelFactory { initializer { SubmitApprovalViewModel(app, presetTemplateId) } }
    }
}

/** Submit form (route `approval_submit`): template picker + dynamic fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubmitApprovalScreen(
    presetTemplateId: String?,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val vm: SubmitApprovalViewModel = viewModel(
        factory = SubmitApprovalViewModel.factory(app, presetTemplateId),
    )
    val ui by vm.ui.collectAsStateWithLifecycle()

    var selectedId by remember(ui.templates) { mutableStateOf(vm.initialTemplateId) }
    val selected = ui.templates.firstOrNull { it.id == selectedId }
    // Field values, keyed by field key. Reset when the template changes.
    var formData by remember(selectedId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var menuOpen by remember { mutableStateOf(false) }

    val fields = selected?.formSchema?.fields.orEmpty()
    val missingRequired = fields.any { it.required && formData[it.key].orEmpty().isBlank() }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.approval_form_title),
                onBack = onBack,
                actions = {
                    TextButton(
                        onClick = { selectedId?.let { vm.submit(it, formData, onDone) } },
                        enabled = selectedId != null && !missingRequired && !ui.submitting,
                    ) {
                        if (ui.submitting) {
                            CircularProgressIndicator(Modifier.padding(end = Dimens.SpaceS).height(Dimens.IconSmall))
                        } else {
                            Text(stringResource(R.string.approval_form_submit))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding),
        ) {
            when {
                ui.loading -> CircularProgressIndicator(Modifier.padding(Dimens.SpaceXl))
                // Load failure ≠ genuinely-empty: show an error + retry instead
                // of the misleading "no templates available".
                ui.error && ui.templates.isEmpty() -> Column(Modifier.padding(Dimens.SpaceXl)) {
                    Text(
                        stringResource(R.string.approval_load_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { vm.loadTemplates() }) {
                        Text(stringResource(R.string.approval_retry))
                    }
                }
                ui.templates.isEmpty() -> Text(
                    stringResource(R.string.approval_no_templates),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Dimens.SpaceXl),
                )
                else -> {
                    // Template picker.
                    ExposedDropdownMenuBox(
                        expanded = menuOpen,
                        onExpandedChange = { menuOpen = it },
                        modifier = Modifier.padding(top = Dimens.SpaceM),
                    ) {
                        OutlinedTextField(
                            value = selected?.name.orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.approval_form_template)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            ui.templates.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t.name.orEmpty()) },
                                    onClick = {
                                        selectedId = t.id
                                        menuOpen = false
                                    },
                                )
                            }
                        }
                    }

                    selected?.description?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Dimens.SpaceS),
                        )
                    }

                    // Dynamic fields.
                    fields.forEach { field ->
                        val label = (field.label ?: field.key) + if (field.required) " *" else ""
                        if (field.type == "date") {
                            DateField(
                                label = label,
                                value = formData[field.key].orEmpty(),
                                onPick = { formData = formData + (field.key to it) },
                            )
                        } else {
                            OutlinedTextField(
                                value = formData[field.key].orEmpty(),
                                onValueChange = { formData = formData + (field.key to it) },
                                label = { Text(label) },
                                singleLine = field.type != "textarea",
                                minLines = if (field.type == "textarea") 3 else 1,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = when (field.type) {
                                        "number" -> KeyboardType.Number
                                        else -> KeyboardType.Text
                                    }
                                ),
                                modifier = Modifier.fillMaxWidth().padding(top = Dimens.SpaceM),
                            )
                        }
                    }

                    if (ui.error) {
                        Text(
                            text = stringResource(R.string.approval_submit_failed),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = Dimens.SpaceM),
                        )
                    }
                }
            }
        }
    }
}

/** Date form field (对齐 web type="date"):read-only 文本框点击弹 M3 DatePicker,
 *  提交 ISO yyyy-MM-dd(与 web <input type="date"> 一致)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: String, onPick: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(top = Dimens.SpaceM)) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            trailingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // readOnly 文本框自身不发 click,盖一层透明可点区域触发选择器。
        Box(Modifier.matchParentSize().clickable { show = true })
    }
    if (show) {
        val initial = runCatching {
            java.time.LocalDate.parse(value)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        onPick(d.toString())
                    }
                    show = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { show = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        ) { DatePicker(state = state) }
    }
}
