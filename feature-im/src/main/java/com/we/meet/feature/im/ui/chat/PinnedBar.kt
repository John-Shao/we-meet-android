package com.we.meet.feature.im.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jusi.lightim.PinnedMessage
import com.we.meet.feature.im.R

/**
 * 会话 Pin 栏(P17,飞书式):置顶消息的 📌 摘要条,点击展开完整列表。
 * 解除按钮直接发 unpin;越权(非 pinner/非群主管理员)由服务端拒绝,
 * 列表保持不变。快照内联,无需二次拉取。
 */
@Composable
fun PinnedBar(
    pins: List<PinnedMessage>,
    senderName: (String) -> String,
    onUnpin: (Long) -> Unit,
    /** P3-M3 后补子项:点击置顶条目跳转定位到消息(seq)。 */
    onJump: ((Long) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val latest = pins.first()

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = latest.message.body.ifBlank {
                    stringResource(R.string.im_pin_no_preview)
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.im_pin_count, pins.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (expanded) {
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(pins.size) { i ->
                    val pin = pins[i]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier
                                .weight(1f)
                                .then(
                                    if (onJump != null) {
                                        Modifier.clickable {
                                            expanded = false
                                            onJump(pin.message.seq)
                                        }
                                    } else Modifier
                                ),
                        ) {
                            Text(
                                text = senderName(pin.message.senderUid),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Text(
                                text = pin.message.body.ifBlank {
                                    stringResource(R.string.im_pin_no_preview)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onUnpin(pin.mid) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.im_action_unpin),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider()
    }
}
