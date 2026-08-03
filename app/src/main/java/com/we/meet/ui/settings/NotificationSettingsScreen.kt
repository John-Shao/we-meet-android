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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.api.PushPreferencesUpdate
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * 「设置 › 通知」—— 消息通知相关的设置都收在这一页(对标微信/企业微信:通知是
 * 设置总页的一行入口,不是散落在总页上的一节)。
 *
 * 目前只有**免打扰时段**(P0-M3):开关 + 起止时间(24h 墙上钟,按账号时区解释)。
 * 存 we-meet 后端的 `push/preferences/`,服务端在离线推送发送前过滤,只静默消息
 * 通知,来电不受影响。
 *
 * 另有一行「消息特别提醒」入口:那是**名单**(我给谁开了穿透),不是全局开关 ——
 * 穿透逐联系人决定(`ContactPreference.special_alert`),开关在各自的详情页上。
 * 这里再兜一个全局闸就等于把飞书那套 override 结构搬回来,别加。
 *
 * 读失败仍放开 UI(保存时再报错),避免弱网下设置页卡死。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit,
    /** 「消息特别提醒」名单页(设置 › 通知 › 消息特别提醒)。 */
    onOpenSpecialAlerts: () -> Unit,
) {
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
                // 时段是按这个时区的墙上钟判断的,所以就在设置它的页面上对一次
                // 账 —— 这里的 timezone 是服务端权威值且已经拿到手,校准不额外
                // 花请求,还能自愈「浏览器登录把它覆盖成别的时区」的情况。
                app.profileRepository.syncDeviceTimezone(p.timezone.orEmpty())
                    .onSuccess { reported -> if (reported != null) tz = reported }
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
            WeMeetTopBar(
                title = stringResource(R.string.notification_settings_title),
                onBack = onBack,
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
            Spacer(Modifier.height(Dimens.SpaceS))
            SettingsCard {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
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
                        Spacer(Modifier.height(Dimens.SpaceXs))
                    }
                }
            }

            Spacer(Modifier.height(Dimens.SpaceS))
            Text(
                text = stringResource(R.string.settings_quiet_hint, tz.ifBlank { "-" }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.SpaceXl),
            )

            Spacer(Modifier.height(Dimens.SpaceXl))
            // 「消息特别提醒」名单入口:逐个人的开关在各自详情页上,这里是回顾/
            // 批量整理的地方(与星标的「详情页开关 + 名单页」同构)。
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSpecialAlerts)
                        .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.ScreenPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.special_alert_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(Dimens.SpaceS))
            Text(
                text = stringResource(R.string.special_alert_entry_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.SpaceXl),
            )
            Spacer(Modifier.height(Dimens.SpaceXl))
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
            .clip(RoundedCornerShape(Dimens.CornerM))
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
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
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
