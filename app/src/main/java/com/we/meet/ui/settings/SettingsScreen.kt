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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.WeMeetApp
import com.we.meet.R
import com.we.meet.data.settings.ThemeMode
import com.we.meet.data.settings.VideoCodecPref

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAccountDeregistered: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val settingsStore = app.settingsStore
    val selectedCodec by settingsStore.videoCodec.collectAsStateWithLifecycle()
    val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle()
    val expectedPhone = app.tokenStore.phone.orEmpty()
    var showDeregisterDialog by remember { mutableStateOf(false) }
    var deregistering by remember { mutableStateOf(false) }
    var deregisterError by remember { mutableStateOf<String?>(null) }
    val deregisterFailedText = stringResource(R.string.profile_deregister_error)
    val scope = rememberCoroutineScope()

    var backPending by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!backPending) { backPending = true; onBack() }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
            ThemeSection(
                selected = themeMode,
                onSelect = settingsStore::setThemeMode,
            )
            LanguageSection()
            AccountSection(
                onDeregisterClick = { showDeregisterDialog = true },
                errorMessage = deregisterError,
            )
        }
    }

    if (showDeregisterDialog) {
        DeregisterDialog(
            expectedPhone = expectedPhone,
            inFlight = deregistering,
            onConfirm = {
                deregistering = true
                deregisterError = null
                scope.launch {
                    app.profileRepository.deregister()
                        .onSuccess {
                            showDeregisterDialog = false
                            deregistering = false
                            com.we.meet.analytics.Analytics.reset()
                            onAccountDeregistered()
                        }
                        .onFailure {
                            deregistering = false
                            deregisterError = deregisterFailedText
                            showDeregisterDialog = false
                        }
                }
            },
            onDismiss = {
                if (!deregistering) showDeregisterDialog = false
            },
        )
    }
}

// ── Account ─────────────────────────────────────────────────────────────

@Composable
private fun AccountSection(
    onDeregisterClick: () -> Unit,
    errorMessage: String?,
) {
    Spacer(Modifier.height(8.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDeregisterClick)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.profile_deregister),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (errorMessage != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
    Spacer(Modifier.height(16.dp))
}

/**
 * High-friction account-delete dialog — moved verbatim from ProfileScreen
 * when account settings consolidated under Settings → Account. The
 * confirm button only enables when the user re-types their bound phone
 * number, matching the desktop client's destructive-action gate.
 */
@Composable
private fun DeregisterDialog(
    expectedPhone: String,
    inFlight: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val matches = input.trim() == expectedPhone

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_deregister_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.profile_deregister_warning),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    placeholder = {
                        Text(stringResource(R.string.profile_deregister_phone_hint))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !inFlight,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = matches && !inFlight,
            ) {
                if (inFlight) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.profile_deregister_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inFlight) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

// ── Theme ───────────────────────────────────────────────────────────────

@Composable
private fun ThemeSection(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Spacer(Modifier.height(8.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        ThemeDropdownRow(
            label = stringResource(R.string.settings_theme),
            selected = selected,
            onSelect = onSelect,
        )
    }

    Spacer(Modifier.height(16.dp))
}

@Composable
private fun ThemeDropdownRow(
    label: String,
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 16.dp),
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
                    text = themeLabel(selected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(2.dp))
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
                ThemeMode.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(themeLabel(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                        trailingIcon = if (option == selected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
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
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }
)

// ── Language ────────────────────────────────────────────────────────────

/**
 * 5 supported locales mirror the Web frontend's i18n bundle. Tag is the
 * BCP-47 language tag we feed into [AppCompatDelegate.setApplicationLocales].
 * Display name stays in the locale's own script so the option is
 * recognisable from any current UI language.
 */
private data class LanguageOption(val tag: String, val display: String)

private val LANGUAGE_OPTIONS = listOf(
    LanguageOption("zh-CN", "简体中文"),
    LanguageOption("en", "English"),
    LanguageOption("fr", "Français"),
    LanguageOption("de", "Deutsch"),
    LanguageOption("nl", "Nederlands"),
)

@Composable
private fun LanguageSection() {
    Spacer(Modifier.height(8.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        LanguageDropdownRow(label = stringResource(R.string.settings_language))
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.settings_language_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun LanguageDropdownRow(label: String) {
    var expanded by remember { mutableStateOf(false) }
    // Current selection: AppCompatDelegate is the source of truth — it
    // persists per-app locale on API 33+ via Android's LocaleManager,
    // and uses ConfigurationOverride for older API levels. An empty
    // locale list means "follow system" — we show that as the system
    // option rather than mapping it to a real tag.
    val currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val systemLabel = stringResource(R.string.settings_language_system)
    val currentLabel = LANGUAGE_OPTIONS.firstOrNull { it.tag.equals(currentTag, ignoreCase = true) }
        ?.display ?: systemLabel

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 16.dp),
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
                    text = currentLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(2.dp))
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
                DropdownMenuItem(
                    text = { Text(systemLabel) },
                    onClick = {
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                        expanded = false
                    },
                    trailingIcon = if (currentTag.isEmpty()) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else null,
                )
                LANGUAGE_OPTIONS.forEach { option ->
                    val selected = option.tag.equals(currentTag, ignoreCase = true)
                    DropdownMenuItem(
                        text = { Text(option.display) },
                        onClick = {
                            AppCompatDelegate.setApplicationLocales(
                                LocaleListCompat.forLanguageTags(option.tag)
                            )
                            expanded = false
                        },
                        trailingIcon = if (selected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
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
private fun CodecSection(
    selected: VideoCodecPref,
    onSelect: (VideoCodecPref) -> Unit,
) {
    Spacer(Modifier.height(8.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        CodecDropdownRow(
            label = stringResource(R.string.settings_video_codec),
            selected = selected,
            onSelect = onSelect,
        )
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.settings_video_codec_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
    Spacer(Modifier.height(16.dp))
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
            .padding(horizontal = 16.dp, vertical = 16.dp),
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
                Spacer(Modifier.width(2.dp))
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
                                    modifier = Modifier.size(20.dp),
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
