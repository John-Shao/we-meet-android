package com.we.meet.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.ui.components.SettingsActionRow
import com.we.meet.ui.components.SettingsDivider
import com.we.meet.ui.components.SettingsGroup
import com.we.meet.ui.components.SettingsHint
import com.we.meet.ui.components.SettingsPickerOption
import com.we.meet.ui.components.SettingsPickerSheet
import com.we.meet.ui.components.SettingsRow
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.WeMeetApp
import com.we.meet.R
import com.we.meet.core.directory.data.ContactPrefs
import com.we.meet.feature.im.ImSession
import com.we.meet.ui.theme.Dimens
import com.we.meet.data.settings.ThemeMode
import kotlinx.coroutines.launch

/**
 * 设置总页。版式走共享的 [SettingsGroup] / [SettingsRow](规范见 `SettingsList.kt`
 * 的表):浅灰页面底 + 白卡片,分组之间 [Dimens.SpaceL],首尾各留一口气,组内多行
 * 之间用 [SettingsDivider]。
 *
 * 这里**不给分组加标题**:每组只有一两行、行名本身就说清了是什么(主题/语言/通知),
 * 再加一行灰字是重复。需要标题的是「日历设置」那种一组里塞了七八行、必须分段的页面。
 */
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
    val scope = rememberCoroutineScope()
    var showSignOutConfirm by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    BackHandler(enabled = signingOut) { }

    var backPending by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.settings_title),
                onBack = { if (!backPending) { backPending = true; onBack() } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(Dimens.SpaceL))

            ThemeRow(
                selected = themeMode,
                onSelect = settingsStore::setThemeMode,
            )
            Spacer(Modifier.height(Dimens.SpaceL))

            LanguageRow()
            Spacer(Modifier.height(Dimens.SpaceL))

            // 通知只是总页上的一行入口:免打扰时段、特别提醒名单都在子页面里
            // (对标微信/企业微信 —— 通知设置不该摊在总页上)。
            SettingsGroup {
                SettingsRow(
                    label = stringResource(R.string.notification_settings_title),
                    onClick = onOpenNotificationSettings,
                )
            }
            Spacer(Modifier.height(Dimens.SpaceL))

            // 模块设置(会议/日历/任务):三行一件事,合成一组。
            SettingsGroup {
                SettingsRow(
                    label = stringResource(R.string.meeting_settings_title),
                    onClick = onOpenMeetingSettings,
                )
                SettingsDivider()
                SettingsRow(
                    label = stringResource(R.string.calendar_settings_title),
                    onClick = onOpenCalendarSettings,
                )
                SettingsDivider()
                SettingsRow(
                    label = stringResource(R.string.task_settings),
                    onClick = onOpenTaskSettings,
                )
            }
            Spacer(Modifier.height(Dimens.SpaceL))

            SettingsGroup {
                SettingsRow(
                    label = stringResource(R.string.settings_account_security),
                    onClick = onOpenAccountSecurity,
                )
            }
            Spacer(Modifier.height(Dimens.SpaceXl))

            // 退出登录单独一组:它是动作不是设置项,而且要给二次确认。
            SettingsGroup {
                SettingsActionRow(
                    label = stringResource(R.string.profile_sign_out),
                    onClick = { showSignOutConfirm = true },
                    contentColor = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(Dimens.SpaceXl))
        }
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { if (!signingOut) showSignOutConfirm = false },
            title = { Text(stringResource(R.string.profile_sign_out)) },
            text = { Text(stringResource(R.string.profile_sign_out_confirm)) },
            confirmButton = {
                TextButton(enabled = !signingOut, onClick = {
                    signingOut = true
                    app.clearDocsSession()
                    scope.launch {
                        // Finish cleanup even if the settings composition is disposed.
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            app.authRepository.signOut()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                app.cacheDir.resolve("docs_image_cache").deleteRecursively()
                            }
                            com.we.meet.analytics.Analytics.reset()
                            ImSession.shutdown()
                            ContactPrefs.clear()
                            // 组织也一样:内存里那份属于上一个账号(本地那份随
                            // tokenStore.clear() 一起没了),不能留给下一个。
                            app.orgContextStore.clear()
                            onSignedOut()
                        }
                    }
                }) {
                    Text(stringResource(R.string.ok), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(enabled = !signingOut, onClick = { showSignOutConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

}

// ── 设备级偏好(主题 / 语言) ─────────────────────────────────────────────

@Composable
private fun ThemeRow(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }

    SettingsGroup {
        SettingsRow(
            label = stringResource(R.string.settings_theme),
            value = themeLabel(selected),
            onClick = { picking = true },
        )
    }
    if (picking) {
        SettingsPickerSheet(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries.map { option ->
                SettingsPickerOption(
                    label = themeLabel(option),
                    selected = option == selected,
                    onSelect = { onSelect(option) },
                )
            },
            onDismissRequest = { picking = false },
        )
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
private fun LanguageRow() {
    var picking by remember { mutableStateOf(false) }
    // Current selection: AppCompatDelegate is the source of truth — it
    // persists per-app locale on API 33+ via Android's LocaleManager,
    // and uses ConfigurationOverride for older API levels. An empty
    // locale list means "follow system" — we show that as the system
    // option rather than mapping it to a real tag.
    val currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val systemLabel = stringResource(R.string.settings_language_system)
    val currentLabel = LANGUAGE_OPTIONS.firstOrNull { it.tag.equals(currentTag, ignoreCase = true) }
        ?.display ?: systemLabel

    SettingsGroup {
        SettingsRow(
            label = stringResource(R.string.settings_language),
            value = currentLabel,
            onClick = { picking = true },
        )
    }
    SettingsHint(stringResource(R.string.settings_language_hint))

    if (picking) {
        SettingsPickerSheet(
            title = stringResource(R.string.settings_language),
            options = buildList {
                add(
                    SettingsPickerOption(
                        label = systemLabel,
                        selected = currentTag.isEmpty(),
                        onSelect = {
                            AppCompatDelegate.setApplicationLocales(
                                LocaleListCompat.getEmptyLocaleList()
                            )
                        },
                    )
                )
                LANGUAGE_OPTIONS.forEach { option ->
                    add(
                        SettingsPickerOption(
                            label = option.display,
                            selected = option.tag.equals(currentTag, ignoreCase = true),
                            onSelect = {
                                AppCompatDelegate.setApplicationLocales(
                                    LocaleListCompat.forLanguageTags(option.tag)
                                )
                            },
                        )
                    )
                }
            },
            onDismissRequest = { picking = false },
        )
    }
}
