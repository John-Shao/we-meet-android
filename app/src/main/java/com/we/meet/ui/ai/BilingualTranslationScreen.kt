package com.we.meet.ui.ai

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BilingualTranslationScreen(app: WeMeetApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val user = app.captureAccount
    val controller = remember(app, user) {
        BilingualTranslationController(context.applicationContext, app.apiClient.assistantTranslationApi, authorized = {
            user != null && app.captureAccount == user
        })
    }
    val state by controller.state.collectAsStateWithLifecycle()
    var permissionDenied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.start()
    }
    DisposableEffect(controller, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) controller.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); controller.close() }
    }
    val back = { controller.stop(); onBack() }
    BackHandler(onBack = back)
    val listState = rememberLazyListState()
    LaunchedEffect(state.rows.lastOrNull()?.id) {
        if (state.rows.isNotEmpty()) listState.animateScrollToItem(state.rows.lastIndex)
    }
    Scaffold(topBar = { WeMeetTopBar(title = stringResource(R.string.bilingual_title), onBack = back) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = Dimens.ScreenPadding)) {
            Text(stringResource(R.string.bilingual_pair), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = Dimens.SpaceL))
            Text(stringResource(R.string.bilingual_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceS), verticalAlignment = Alignment.CenterVertically) {
                val soundLabel = stringResource(R.string.bilingual_sound)
                Text(soundLabel, Modifier.weight(1f))
                Switch(checked = state.sound, onCheckedChange = controller::sound,
                    modifier = Modifier.semantics { contentDescription = soundLabel })
            }
            val status = when {
                permissionDenied -> R.string.bilingual_permission
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
            Text(stringResource(status), style = MaterialTheme.typography.bodyMedium,
                color = if (state.phase == BilingualPhase.ERROR || permissionDenied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            if (state.audioOmitted) Text(stringResource(R.string.bilingual_audio_omitted),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.rows.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.bilingual_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                    contentPadding = PaddingValues(vertical = Dimens.SpaceL), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                    items(state.rows, key = { it.id }) { row ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(Dimens.SpaceL), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                                Text(stringResource(if (row.sourceLanguage == "zh") R.string.bilingual_zh_en else R.string.bilingual_en_zh),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(row.source, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(row.text, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    permissionDenied = false
                    if (state.active) controller.finish()
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) controller.start()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = state.phase != BilingualPhase.FINISHING,
                modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceL).height(Dimens.ButtonHeight),
            ) { Text(stringResource(if (state.active) R.string.bilingual_stop else R.string.bilingual_start)) }
        }
    }
}
