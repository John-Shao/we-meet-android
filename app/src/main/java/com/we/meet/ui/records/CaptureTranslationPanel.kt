package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.we.meet.R
import com.we.meet.data.api.dto.CaptureTranslationChoiceDto
import com.we.meet.data.capture.*
import com.we.meet.data.repository.CaptureTranslationRepository
import com.we.meet.data.repository.CaptureTranslationSource
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.*

@Composable
internal fun CaptureTranslationPanel(source: CaptureTranslationSource, revision: Long, repository: CaptureTranslationRepository,
    currentViewer: () -> String?, currentSource: () -> Boolean, recording: () -> Boolean, observe: () -> CapturePcmTap.Subscription) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val viewer by rememberUpdatedState(currentViewer)
    val sourceCurrent by rememberUpdatedState(currentSource)
    val sourceRecording by rememberUpdatedState(recording)
    val tap by rememberUpdatedState(observe)
    var controller by remember(source, revision) { mutableStateOf<CaptureTranslationController?>(null) }
    var state by remember(source, revision) { mutableStateOf(CaptureTranslationViewState()) }
    var choice by remember(source, revision) { mutableStateOf(CaptureTranslationChoiceDto("zh", "en", "simultaneous", false, false)) }
    var refresh by remember(source, revision) { mutableIntStateOf(0) }
    var action by remember(source, revision) { mutableStateOf<Job?>(null) }
    LaunchedEffect(source, revision, repository, lifecycle, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var store: MeetingIntentStore? = null
            var active: CaptureTranslationController? = null
            try {
                withContext(Dispatchers.IO) { store = MeetingIntentStore.open(context, source.viewer) { viewer() } }
                val owner = CaptureTranslationController(source, revision, repository, requireNotNull(store),
                    { viewer() == source.viewer && sourceCurrent() }, { sourceRecording() }, { tap() },
                    { interrupted -> AndroidTranslationOutput(context, interrupted) })
                active = owner; controller = owner
                coroutineScope {
                    launch { owner.state.collect { state = it } }
                    owner.load()
                    while (isActive) { delay(5000); owner.refresh() }
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { state = CaptureTranslationViewState(storageError = true) }
            finally {
                active?.close(); controller = null
                withContext(NonCancellable) { action?.cancelAndJoin(); withContext(Dispatchers.IO) { store?.close() } }
                action = null
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) state = CaptureTranslationViewState()
            }
        }
    }
    fun perform(operation: String, recover: Boolean = false) {
        val owner = controller ?: return
        if (action?.isActive == true) return
        action = scope.launch { owner.perform(operation, if (operation == "start") choice else null, recover) }
    }
    CaptureTranslationControls(state, choice, controller != null && viewer() == source.viewer && sourceCurrent() && sourceRecording(),
        { choice = it }, { perform("start") }, { if (controller?.finishConnected() != true) perform("stop") },
        { perform("start", true) }, { controller?.begin(it) }, { controller?.endTurn() }, { controller?.mute(!state.muted) }, { refresh++ })
}

/** Rendering boundary: settings never start a microphone or issue a paid request by themselves. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun CaptureTranslationControls(state: CaptureTranslationViewState, choice: CaptureTranslationChoiceDto, canRecord: Boolean,
    change: (CaptureTranslationChoiceDto) -> Unit, start: () -> Unit, stop: () -> Unit, recover: () -> Unit,
    begin: (String) -> Unit, endTurn: () -> Unit, mute: () -> Unit, refresh: () -> Unit) {
    val live = state.live
    val connected = live != null && live.phase !in CaptureTranslationController.terminal
    val frozen = state.busy || state.pending != null || connected || state.remote?.current?.status in CaptureTranslationRepository.activeStates
    if (state.remote?.available == false && state.remote.current == null && state.pending == null) return
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Text(stringResource(R.string.capture_translation_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.capture_translation_scope), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                FilterChip(choice.mode == "simultaneous", { change(choice.copy(mode = "simultaneous")) }, enabled = !frozen, label = { Text(stringResource(R.string.capture_translation_simultaneous)) })
                FilterChip(choice.mode == "push_to_talk", { change(choice.copy(mode = "push_to_talk")) }, enabled = !frozen, label = { Text(stringResource(R.string.capture_translation_speech)) })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                FilterChip(choice.sourceLanguage == "zh", { change(choice.copy(sourceLanguage = "zh", targetLanguage = "en")) }, enabled = !frozen, label = { Text(stringResource(R.string.capture_translation_zh_en)) })
                FilterChip(choice.sourceLanguage == "en", { change(choice.copy(sourceLanguage = "en", targetLanguage = "zh")) }, enabled = !frozen, label = { Text(stringResource(R.string.capture_translation_en_zh)) })
            }
            TranslationChoice(choice.audio, !frozen, stringResource(R.string.capture_translation_audio)) { change(choice.copy(audio = it)) }
            if (choice.audio) Text(stringResource(R.string.capture_translation_headphones), style = MaterialTheme.typography.bodySmall)
            TranslationChoice(choice.saveTranslations, !frozen && state.remote?.canSaveTranslations == true, stringResource(R.string.capture_translation_save)) { change(choice.copy(saveTranslations = it)) }
            if (state.remote == null && !state.failed && !state.storageError) WeMeetInlineLoading()
            if (state.failed || state.storageError) {
                Text(stringResource(if (state.storageError) R.string.capture_translation_storage_error else R.string.capture_translation_error), style = MaterialTheme.typography.bodySmall)
                TextButton(refresh) { Text(stringResource(R.string.records_refresh)) }
            }
            if (state.pending != null) {
                Text(stringResource(R.string.capture_translation_pending), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(recover, enabled = !state.busy && !state.storageError) { Text(stringResource(R.string.capture_translation_recover)) }
            }
            if (live != null) Text(stringResource(translationPhase(live.phase)), style = MaterialTheme.typography.labelLarge)
            if (!connected && state.remote?.current?.status in CaptureTranslationRepository.activeStates) Text(stringResource(R.string.capture_translation_detached), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Button(start, enabled = canRecord && !frozen && !state.storageError && state.remote?.canStart == true && (!choice.saveTranslations || state.remote.canSaveTranslations)) { Text(stringResource(R.string.capture_translation_start)) }
                if (connected || state.remote?.canStop == true) OutlinedButton(stop, enabled = !state.busy && state.pending == null && live?.phase != "finishing") { Text(stringResource(R.string.capture_translation_stop)) }
            }
            if (connected && choice.audio) TextButton(mute) { Text(stringResource(if (state.muted) R.string.capture_translation_unmute else R.string.capture_translation_mute)) }
            if (connected && choice.mode == "push_to_talk") {
                if (live?.phase == "speaking") Button(endTurn) { Text(stringResource(R.string.capture_translation_end_turn)) }
                else {
                    OutlinedButton({ begin("forward") }, enabled = live?.phase == "ready", modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.capture_translation_speak, translationLanguage(choice.sourceLanguage))) }
                    OutlinedButton({ begin("reverse") }, enabled = live?.phase == "ready", modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.capture_translation_speak, translationLanguage(choice.targetLanguage))) }
                }
            }
            live?.finals?.forEach { Text(it.text, style = MaterialTheme.typography.bodyMedium) }
            live?.candidate?.let { Text(it.text, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
@Composable private fun TranslationChoice(checked: Boolean, enabled: Boolean, label: String, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked, change, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable private fun translationLanguage(value: String) = stringResource(if (value == "zh") R.string.archives_zh else R.string.archives_en)
private fun translationPhase(value: String) = when (value) {
    "connecting" -> R.string.capture_translation_connecting
    "ready" -> R.string.capture_translation_ready
    "speaking" -> R.string.capture_translation_listening
    "draining", "awaiting" -> R.string.capture_translation_awaiting
    "finishing" -> R.string.capture_translation_finishing
    "stopped" -> R.string.capture_translation_stopped
    "unknown" -> R.string.capture_translation_unknown
    else -> R.string.capture_translation_interrupted
}
