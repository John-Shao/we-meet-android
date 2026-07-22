package com.we.meet.ui.home

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.we.meet.BuildConfig
import com.we.meet.R
import com.we.meet.WeMeetApp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * P8 预约会议详情页(对标飞书/Web MeetingDetailPanel):点预约会议行打开,
 * 所有操作收进详情 —— 进入会议 / 复制会议号与链接 / 删除(服务端删房,
 * 确认后回退)。数据由列表行直传(slug/name/scheduledAt),零额外请求。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledDetailScreen(
    slug: String,
    name: String,
    scheduledAtIso: String,
    onBack: () -> Unit,
    onJoinSlug: (slug: String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val title = name.ifBlank { formatSlugDigits(slug) }
    val link = BuildConfig.WE_MEET_BASE_URL.trimEnd('/') + "/" + slug
    val copiedText = stringResource(R.string.detail_copied)
    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.history_delete_confirm_title)) },
            text = { Text(stringResource(R.string.history_delete_confirm_text, title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (!deleting) {
                        deleting = true
                        scope.launch {
                            // 与 HomeViewModel.deleteMeeting 同语义:服务端删房
                            // (非房主 403 也无妨)+ 清本机记录;列表 resume 自愈。
                            app.roomRepository.deleteRoom(slug)
                            app.historyStore.remove(slug)
                            onBack()
                        }
                    }
                }) {
                    Text(
                        stringResource(R.string.history_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.meeting_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.history_action_delete),
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))

            // 时钟图标已表意,不再带「预约时间:」前缀;详情页带年份。
            DetailRow(icon = Icons.Filled.Schedule) {
                Text(
                    text = formatScheduledIso(
                        context.getString(R.string.fmt_full_date_time),
                        scheduledAtIso,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            DetailRow(icon = Icons.Filled.Tag) {
                Text(
                    text = formatSlugDigits(slug),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { copy(slug) }) {
                    Text(stringResource(R.string.detail_copy))
                }
            }
            DetailRow(icon = Icons.Filled.Link) {
                Text(
                    text = link,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { copy(link) }) {
                    Text(stringResource(R.string.detail_copy))
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onJoinSlug(slug) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Videocam, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.event_join_meeting))
            }
        }
    }
}

@Composable
private fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        content()
    }
}

/** 8/9/6 位会议号按组分隔(与历史详情 formatSlug 同口径)。 */
internal fun formatSlugDigits(slug: String): String {
    val digits = slug.filter { it.isDigit() }
    return when (digits.length) {
        8 -> "${digits.substring(0, 4)} ${digits.substring(4)}"
        9 -> "${digits.substring(0, 3)} ${digits.substring(3, 6)} ${digits.substring(6)}"
        6 -> "${digits.substring(0, 3)} ${digits.substring(3)}"
        else -> slug
    }
}

private fun formatScheduledIso(pattern: String, iso: String): String {
    if (iso.isBlank()) return "—"
    val normalized = iso
        .replace(Regex("\\.\\d+"), "")
        .let { if (it.endsWith("Z")) it.dropLast(1) + "+0000" else it }
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val ms = runCatching { parser.parse(normalized)?.time }.getOrNull() ?: return iso
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}
