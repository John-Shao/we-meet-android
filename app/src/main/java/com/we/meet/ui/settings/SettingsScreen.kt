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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.WeMeetApp
import com.we.meet.R
import com.we.meet.core.directory.data.ContactPrefs
import com.we.meet.feature.im.ImSession
import com.we.meet.ui.theme.Dimens
import com.we.meet.data.settings.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    onOpenAccountSecurity: () -> Unit,
    /** P8 设置收敛:模块设置页入口(会议/日历/任务)。模块内的齿轮只是快捷入口,
     * 指向的仍是这里挂的同一页面。 */
    onOpenMeetingSettings: () -> Unit,
    onOpenCalendarSettings: () -> Unit,
    onOpenTaskSettings: () -> Unit,
    /** 「通知」页 —— 免打扰时段/星标穿透等消息通知设置都在里面。 */
    onOpenNotificationSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val settingsStore = app.settingsStore
    val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle()
    var showSignOutConfirm by remember { mutableStateOf(false) }

    var backPending by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.settings_title),
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
            ThemeSection(
                selected = themeMode,
                onSelect = settingsStore::setThemeMode,
            )
            LanguageSection()
            NotificationEntrySection(onClick = onOpenNotificationSettings)
            ModuleSettingsSection(
                onMeetingClick = onOpenMeetingSettings,
                onCalendarClick = onOpenCalendarSettings,
                onTaskClick = onOpenTaskSettings,
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
                    // 星标名单同理:换账号后不该还挂着上一个人的星标。
                    ContactPrefs.clear()
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
 * 模块设置入口(会议设置/日历设置/任务设置):所有设置集中在用户设置里,模块内的
 * 齿轮(会议 tab、日历 tab、任务 tab)只是指向同一页面的快捷入口。
 */
@Composable
private fun ModuleSettingsSection(
    onMeetingClick: () -> Unit,
    onCalendarClick: () -> Unit,
    onTaskClick: () -> Unit,
) {
    Spacer(Modifier.height(Dimens.SpaceS))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .clip(RoundedCornerShape(Dimens.CornerM))
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
            ModuleEntryRow(
                label = stringResource(R.string.task_settings),
                onClick = onTaskClick,
            )
        }
    }
    Spacer(Modifier.height(Dimens.SpaceS))
}

@Composable
private fun ModuleEntryRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.ScreenPadding),
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
    Spacer(Modifier.height(Dimens.SpaceS))

    // 账号与安全 entry (navigates to the account-scoped surface: phone number +
    // the destructive deregister flow) sits above the reversible sign-out.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .clip(RoundedCornerShape(Dimens.CornerM))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onAccountSecurityClick)
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.ScreenPadding),
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

    Spacer(Modifier.height(Dimens.SpaceL))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .clip(RoundedCornerShape(Dimens.CornerM))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSignOutClick)
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.ScreenPadding),
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

    Spacer(Modifier.height(Dimens.SpaceL))
}

// ── Notifications ───────────────────────────────────────────────────────

/**
 * 通知设置只留一行入口(对标微信/企业微信):免打扰时段、星标联系人穿透等
 * 消息通知相关的项都收进 [NotificationSettingsScreen],不再摊在总页上。
 */
@Composable
private fun NotificationEntrySection(onClick: () -> Unit) {
    Spacer(Modifier.height(Dimens.SpaceS))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .clip(RoundedCornerShape(Dimens.CornerM))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        ModuleEntryRow(
            label = stringResource(R.string.notification_settings_title),
            onClick = onClick,
        )
    }
    Spacer(Modifier.height(Dimens.SpaceL))
}

// ── Theme ───────────────────────────────────────────────────────────────

@Composable
private fun ThemeSection(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Spacer(Modifier.height(Dimens.SpaceS))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .clip(RoundedCornerShape(Dimens.CornerM))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        ThemeDropdownRow(
            label = stringResource(R.string.settings_theme),
            selected = selected,
            onSelect = onSelect,
        )
    }

    Spacer(Modifier.height(Dimens.SpaceL))
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
                    text = themeLabel(selected),
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

// 语言名一律用该语言自己的写法,不进 strings.xml —— 挪进去就会被翻译,
// 而「简体中文」在英文界面里也应该显示成「简体中文」,用户才认得出。
private val LANGUAGE_OPTIONS = listOf(
    LanguageOption("zh-CN", "简体中文"), // i18n-exempt
    LanguageOption("en", "English"),
    LanguageOption("fr", "Français"),
    LanguageOption("de", "Deutsch"),
    LanguageOption("nl", "Nederlands"),
)

@Composable
private fun LanguageSection() {
    Spacer(Modifier.height(Dimens.SpaceS))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .clip(RoundedCornerShape(Dimens.CornerM))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        LanguageDropdownRow(label = stringResource(R.string.settings_language))
    }

    Spacer(Modifier.height(Dimens.SpaceS))
    Text(
        text = stringResource(R.string.settings_language_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.SpaceXl),
    )
    Spacer(Modifier.height(Dimens.SpaceL))
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
                    text = currentLabel,
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
                                modifier = Modifier.size(Dimens.IconSmall),
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
