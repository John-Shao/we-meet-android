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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import android.widget.Toast
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.we.meet.data.api.PushPreferencesUpdate
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
import com.we.meet.feature.im.ImSession
import com.we.meet.ui.theme.Dimens
import com.we.meet.data.settings.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    onOpenAccountSecurity: () -> Unit,
    /** P8 设置收敛:模块设置页入口(会议/日历)。模块内的齿轮只是快捷入口,
     * 指向的仍是这里挂的同一页面。 */
    onOpenMeetingSettings: () -> Unit,
    onOpenCalendarSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val settingsStore = app.settingsStore
    val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle()
    var showSignOutConfirm by remember { mutableStateOf(false) }

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
            ThemeSection(
                selected = themeMode,
                onSelect = settingsStore::setThemeMode,
            )
            LanguageSection()
            NotificationSection()
            ModuleSettingsSection(
                onMeetingClick = onOpenMeetingSettings,
                onCalendarClick = onOpenCalendarSettings,
            )
            AccountSection(
                onAccountSecurityClick = onOpenAccountSecurity,
                onSignOutClick = { showSignOutConfirm = true },
            )
        }
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text(stringResource(R.string.profile_sign_out)) },
            text = { Text(stringResource(R.string.profile_sign_out_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutConfirm = false
                    app.authRepository.signOut()
                    com.we.meet.analytics.Analytics.reset()
                    // Drop the IM socket + caches so the next login doesn't
                    // inherit this user's session.
                    ImSession.shutdown()
                    onSignedOut()
                }) {
                    Text(stringResource(R.string.ok), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

}

// ── Module settings (P8 设置收敛) ────────────────────────────────────────

/**
 * 模块设置入口(会议设置/日历设置):所有设置集中在用户设置里,模块内的
 * 齿轮(会议 tab、日历 tab)只是指向同一页面的快捷入口。
 */
@Composable
private fun ModuleSettingsSection(
    onMeetingClick: () -> Unit,
    onCalendarClick: () -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column {
            ModuleEntryRow(
                label = stringResource(R.string.meeting_settings_title),
                onClick = onMeetingClick,
            )
            ModuleEntryRow(
                label = stringResource(R.string.calendar_settings_title),
                onClick = onCalendarClick,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ModuleEntryRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Account ─────────────────────────────────────────────────────────────

@Composable
private fun AccountSection(
    onAccountSecurityClick: () -> Unit,
    onSignOutClick: () -> Unit,
) {
    Spacer(Modifier.height(8.dp))

    // 账号与安全 entry (navigates to the account-scoped surface: phone number +
    // the destructive deregister flow) sits above the reversible sign-out.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onAccountSecurityClick)
                .padding(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_account_security),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSignOutClick)
                .padding(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.profile_sign_out),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    Spacer(Modifier.height(16.dp))
}

// ── Notifications (P0-M3 消息免打扰) ─────────────────────────────────────

/**
 * 免打扰时段:开关 + 起止时间(24h),存 we-meet 后端(`push/preferences/`),
 * 服务端在离线推送发送前按账号时区过滤。仅静默消息通知,来电不受影响。
 * 读失败仍放开 UI(保存时再报错),避免弱网下设置页卡死。
 */
@Composable
private fun NotificationSection() {
    val context = LocalContext.current
    val app = context.applicationContext as WeMeetApp
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf("22:00") }
    var end by remember { mutableStateOf("08:00") }
    var tz by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { app.apiClient.pushApi.getPreferences() }
            .onSuccess { p ->
                enabled = p.quiet_enabled
                start = p.quiet_start
                end = p.quiet_end
                tz = p.timezone.orEmpty()
            }
        loaded = true
    }

    fun save() {
        scope.launch {
            runCatching {
                app.apiClient.pushApi.updatePreferences(
                    PushPreferencesUpdate(
                        quiet_enabled = enabled,
                        quiet_start = start,
                        quiet_end = end,
                    )
                )
            }.onFailure {
                Toast.makeText(
                    context, R.string.settings_quiet_save_failed, Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun pickTime(current: String, onPicked: (String) -> Unit) {
        val parts = current.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        android.app.TimePickerDialog(
            context,
            { _, hh, mm ->
                onPicked(String.format(java.util.Locale.US, "%02d:%02d", hh, mm))
            },
            h, m, true,
        ).show()
    }

    Spacer(Modifier.height(8.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_quiet_hours),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = enabled,
                    enabled = loaded,
                    onCheckedChange = { enabled = it; save() },
                )
            }
            if (enabled) {
                QuietTimeRow(
                    label = stringResource(R.string.settings_quiet_start),
                    value = start,
                    onClick = { pickTime(start) { start = it; save() } },
                )
                QuietTimeRow(
                    label = stringResource(R.string.settings_quiet_end),
                    value = end,
                    onClick = { pickTime(end) { end = it; save() } },
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(
            R.string.settings_quiet_hint,
            tz.ifBlank { "-" },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun QuietTimeRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
            .padding(horizontal = Dimens.ScreenPadding)
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
            .padding(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
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
            .padding(horizontal = Dimens.ScreenPadding)
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
            .padding(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
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

