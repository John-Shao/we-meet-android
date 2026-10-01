package com.we.meet.ui.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import com.we.meet.feature.assistant.history.AssistantHistoryPreference
import com.we.meet.feature.assistant.history.HistoryTextActions
import com.we.meet.feature.assistant.R as AssistantR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BilingualTranslationScreen(app: WeMeetApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val user = app.captureAccount
    val vm: BilingualTranslationViewModel = viewModel(key = "bilingual:$user",
        factory = BilingualTranslationViewModel.Factory(app, user))
    val controller = vm.controller
    val state by controller.state.collectAsStateWithLifecycle()
    var permissionDenied by remember { mutableStateOf(false) }
    var facing by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        permissionDenied = !granted
        if (granted && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.start()
    }
    val back = { controller.stop(); onBack() }
    BackHandler(onBack = back)
    val listState = rememberLazyListState()
    LaunchedEffect(state.rows.lastOrNull()?.id) {
        if (state.rows.isNotEmpty()) listState.animateScrollToItem(state.rows.lastIndex)
    }
    Scaffold(topBar = { WeMeetTopBar(title = stringResource(R.string.bilingual_title), onBack = back, actions = {
        TextButton(onClick = { facing = !facing }) { Text(stringResource(if (facing) AssistantR.string.assistant_facing_list else AssistantR.string.assistant_facing_mode)) }
    }) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = Dimens.ScreenPadding)) {
            BilingualLanguageSelectors(state, controller::selectLanguage)
            if (!state.active) AssistantHistoryPreference(controller.history)
            if (!facing) Text(stringResource(R.string.bilingual_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceS), verticalAlignment = Alignment.CenterVertically) {
                val soundLabel = stringResource(R.string.bilingual_sound)
                Text(soundLabel, Modifier.weight(1f))
                Switch(checked = state.sound, onCheckedChange = controller::sound,
                    modifier = Modifier.semantics { contentDescription = soundLabel })
            }
            val status = when {
                permissionDenied -> R.string.bilingual_permission
                state.inputPaused && state.active -> AssistantR.string.assistant_background_paused
                state.unknownLanguage -> R.string.bilingual_unknown
                else -> when (state.phase) {
                    BilingualPhase.IDLE -> R.string.bilingual_idle
                    BilingualPhase.CONNECTING -> R.string.bilingual_connecting
                    BilingualPhase.LISTENING -> R.string.bilingual_listening
                    BilingualPhase.SPEAKING -> R.string.bilingual_speaking
                    BilingualPhase.FINISHING -> R.string.bilingual_finishing
                    BilingualPhase.ERROR -> R.string.bilingual_error
                    BilingualPhase.EXPIRED -> R.string.bilingual_expired
                }
            }
            Text(if (state.unknownLanguage && !permissionDenied) stringResource(status,
                stringResource(BilingualLanguages.label(state.pair.source)), stringResource(BilingualLanguages.label(state.pair.target)))
                else stringResource(status), style = MaterialTheme.typography.bodyMedium,
                color = if (state.phase == BilingualPhase.ERROR || permissionDenied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            if (state.audioOmitted) Text(stringResource(R.string.bilingual_audio_omitted),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.replayError) Text(stringResource(AssistantR.string.assistant_history_replay_error),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (state.rows.isNotEmpty() && !facing) Text(stringResource(AssistantR.string.assistant_history_replay_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (facing) {
                BilingualFaceToFace(state, Modifier.weight(1f).fillMaxWidth())
            } else if (state.rows.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.bilingual_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                    contentPadding = PaddingValues(vertical = Dimens.SpaceL), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                    items(state.rows, key = { it.id }) { row ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(Dimens.SpaceL), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                                Text(stringResource(R.string.bilingual_direction,
                                    stringResource(BilingualLanguages.label(row.sourceLanguage)), stringResource(BilingualLanguages.label(row.targetLanguage))),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(row.source, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(row.text, style = MaterialTheme.typography.bodyLarge)
                                HistoryTextActions(
                                    text = stringResource(R.string.bilingual_direction,
                                        stringResource(BilingualLanguages.label(row.sourceLanguage)), stringResource(BilingualLanguages.label(row.targetLanguage))) + "\n${row.source}\n${row.text}",
                                    replay = if (row.id in state.replayable) ({ controller.replay(row.id) }) else null,
                                    replayEnabled = !state.replaying && (!state.active || state.phase == BilingualPhase.LISTENING),
                                )
                            }
                        }
                    }
                }
            }
            if (state.active && state.phase != BilingualPhase.CONNECTING && state.phase != BilingualPhase.FINISHING) {
                TextButton(onClick = { controller.pauseInput(!state.inputPaused) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state.inputPaused) AssistantR.string.assistant_background_resume else AssistantR.string.assistant_background_pause))
                }
            }
            Button(
                onClick = {
                    permissionDenied = false
                    if (state.active) controller.finish()
                    else {
                        val needed = buildList {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (needed.isEmpty()) controller.start() else permission.launch(needed.toTypedArray())
                    }
                },
                enabled = state.phase != BilingualPhase.FINISHING,
                modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceL).height(Dimens.ButtonHeight),
            ) { Text(stringResource(if (state.active) R.string.bilingual_stop else R.string.bilingual_start)) }
        }
    }
}

@Composable
internal fun BilingualLanguageSelectors(state: BilingualState, select: (Boolean, String) -> Unit) {
    var choosingFirst by remember { mutableStateOf<Boolean?>(null) }
    Row(Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceM), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        OutlinedButton(onClick = { choosingFirst = true }, enabled = !state.active,
            modifier = Modifier.weight(1f).testTag("bilingual-first-language")) {
            Text(stringResource(BilingualLanguages.label(state.pair.source)), fontWeight = FontWeight.Bold)
        }
        Text("⇄", style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = { choosingFirst = false }, enabled = !state.active,
            modifier = Modifier.weight(1f).testTag("bilingual-second-language")) {
            Text(stringResource(BilingualLanguages.label(state.pair.target)), fontWeight = FontWeight.Bold)
        }
    }
    val first = choosingFirst
    if (first != null && !state.active) {
        val current = if (first) state.pair.source else state.pair.target
        AlertDialog(onDismissRequest = { choosingFirst = null },
            title = { Text(stringResource(R.string.bilingual_choose_language)) },
            text = {
                LazyColumn(Modifier.testTag("bilingual-language-list")) {
                    items(BilingualLanguages.labels.keys.toList(), key = { it }) { code ->
                        TextButton(onClick = { select(first, code); choosingFirst = null }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(BilingualLanguages.label(code)), modifier = Modifier.weight(1f),
                                fontWeight = if (code == current) FontWeight.Bold else FontWeight.Normal)
                            if (code == current) Text("✓")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingFirst = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
