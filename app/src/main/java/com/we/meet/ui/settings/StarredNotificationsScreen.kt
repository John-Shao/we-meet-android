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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.data.StarredContacts
import com.we.meet.data.api.PushPreferencesUpdate
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * 「星标联系人消息通知」(对标飞书同名页)。
 *
 * 只有一个开关:**通知静音时,仍然通知我** —— 星标联系人的消息穿透免打扰时段
 * (存后端 `push/preferences/` 的 `starred_bypass_quiet`,默认开)。
 *
 * 飞书还有一条「免打扰会话有新消息时,通知我」,we-meet 暂时给不出:会话级
 * `muted` 由 jusi-light-im 在发 webhook 前就把成员剔掉了,Django 侧收不到那条
 * 消息,穿透要改 jusi 才做得到。宁可不上这个开关,也不上一个点了没用的开关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredNotificationsScreen(
    onBack: () -> Unit,
    onOpenStarredContacts: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as WeMeetApp
    val scope = rememberCoroutineScope()

    val starredIds by StarredContacts.ids.collectAsStateWithLifecycle()
    var loaded by remember { mutableStateOf(false) }
    var bypassQuiet by remember { mutableStateOf(true) }

    // 读失败也放开 UI(保存时再报错),与免打扰时段那节一致:弱网别把设置页锁死。
    LaunchedEffect(Unit) {
        runCatching { app.apiClient.pushApi.getPreferences() }
            .onSuccess { bypassQuiet = it.starred_bypass_quiet }
        loaded = true
    }

    fun save(next: Boolean) {
        scope.launch {
            runCatching {
                app.apiClient.pushApi.updatePreferences(
                    PushPreferencesUpdate(starred_bypass_quiet = next)
                )
            }.onFailure {
                bypassQuiet = !next  // 回滚,别让开关显示一个没落库的状态
                Toast.makeText(
                    context, R.string.settings_quiet_save_failed, Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.starred_notify_title)) },
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
                .padding(padding),
        ) {
            Spacer(Modifier.height(8.dp))
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenStarredContacts)
                        .padding(horizontal = Dimens.ScreenPadding, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.starred_notify_view_list),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = starredIds.size.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.starred_notify_bypass_quiet),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = bypassQuiet,
                        enabled = loaded,
                        onCheckedChange = { bypassQuiet = it; save(it) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.starred_notify_bypass_quiet_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

/** 设置页那种圆角卡片容器(与 SettingsScreen 各节同一观感)。 */
@Composable
private fun Card(content: @Composable () -> Unit) {
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
