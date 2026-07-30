package com.we.meet.ui.settings

import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.api.PushPreferencesUpdate
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * 「设置 › 通知」—— 消息通知相关的设置都收在这一页(对标微信/企业微信:通知是
 * 设置总页的一行入口,不是散落在总页上的一节)。
 *
 * 目前两项,都存 we-meet 后端的 `push/preferences/`:
 * - **免打扰时段**(P0-M3):开关 + 起止时间(24h 墙上钟,按账号时区解释)。
 *   服务端在离线推送发送前过滤,只静默消息通知,来电不受影响。
 * - **星标联系人仍然通知我**:星标联系人的消息穿透上面那个时段(默认开)。
 *   免打扰时段没开时它无事可做 → 置灰并换一句说明,而不是留个点了没反应的开关。
 *
 * 读失败仍放开 UI(保存时再报错),避免弱网下设置页卡死。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as WeMeetApp
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf("22:00") }
    var end by remember { mutableStateOf("08:00") }
    var starredBypass by remember { mutableStateOf(true) }
    var tz by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { app.apiClient.pushApi.getPreferences() }
            .onSuccess { p ->
                enabled = p.quiet_enabled
                start = p.quiet_start
                end = p.quiet_end
                starredBypass = p.starred_bypass_quiet
                tz = p.timezone.orEmpty()
            }
        loaded = true
    }

    /** 局部更新:未传的字段服务端不动(PushPreferencesUpdate 字段全可空)。 */
    fun save(update: PushPreferencesUpdate, rollback: () -> Unit) {
        scope.launch {
            runCatching { app.apiClient.pushApi.updatePreferences(update) }
                .onFailure {
                    rollback()  // 别让开关停在一个没落库的状态
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notification_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
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
            Spacer(Modifier.height(8.dp))
            SettingsCard {
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
                            onCheckedChange = { next ->
                                val previous = enabled
                                enabled = next
                                save(
                                    PushPreferencesUpdate(
                                        quiet_enabled = next,
                                        quiet_start = start,
                                        quiet_end = end,
                                    ),
                                ) { enabled = previous }
                            },
                        )
                    }
                    if (enabled) {
                        QuietTimeRow(
                            label = stringResource(R.string.settings_quiet_start),
                            value = start,
                            onClick = {
                                pickTime(start) { picked ->
                                    val previous = start
                                    start = picked
                                    save(PushPreferencesUpdate(quiet_start = picked)) {
                                        start = previous
                                    }
                                }
                            },
                        )
                        QuietTimeRow(
                            label = stringResource(R.string.settings_quiet_end),
                            value = end,
                            onClick = {
                                pickTime(end) { picked ->
                                    val previous = end
                                    end = picked
                                    save(PushPreferencesUpdate(quiet_end = picked)) {
                                        end = previous
                                    }
                                }
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_quiet_hint, tz.ifBlank { "-" }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(Modifier.height(20.dp))
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.starred_notify_bypass_quiet),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = starredBypass,
                        // 免打扰时段关着时这个开关无事可做 —— 置灰,别让人点个没反应的东西。
                        enabled = loaded && enabled,
                        onCheckedChange = { next ->
                            val previous = starredBypass
                            starredBypass = next
                            save(PushPreferencesUpdate(starred_bypass_quiet = next)) {
                                starredBypass = previous
                            }
                        },
                    )
                }
            }

            // 开关文案自陈其事,开启态不再另配说明。只有置灰时才补一句「为什么
            // 不能点」—— 那不是解释功能,是解释状态。
            if (!enabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.starred_notify_bypass_quiet_disabled_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 设置页那种圆角卡片容器(与设置总页各节同一观感)。 */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        content()
    }
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
