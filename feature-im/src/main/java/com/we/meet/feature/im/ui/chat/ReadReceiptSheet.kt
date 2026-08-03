package com.we.meet.feature.im.ui.chat

import com.we.meet.ui.theme.Dimens
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.feature.im.R
import com.we.meet.feature.im.data.ImUserInfo

/** Group read-receipt roster: who has read the caller's latest message, who hasn't. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadReceiptSheet(
    memberUids: List<String>,
    readMarkers: Map<String, Long>,
    seq: Long,
    resolveUser: (String) -> ImUserInfo?,
    /** 群昵称(P10)优先的显示名解析,与消息气泡发送者名口径一致。 */
    nameOf: (String) -> String,
    onDismiss: () -> Unit,
) {
    val (read, unread) = remember(memberUids, readMarkers, seq) {
        memberUids.partition { (readMarkers[it] ?: 0L) >= seq }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(modifier = Modifier.padding(bottom = Dimens.SpaceXl)) {
            item {
                SectionHeader(stringResource(R.string.im_receipt_read_section, read.size))
            }
            items(read, key = { "r-$it" }) { uid -> MemberLine(uid, resolveUser, nameOf) }
            item {
                SectionHeader(stringResource(R.string.im_receipt_unread_section, unread.size))
            }
            items(unread, key = { "u-$it" }) { uid -> MemberLine(uid, resolveUser, nameOf) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceS),
    )
}

@Composable
private fun MemberLine(
    uid: String,
    resolveUser: (String) -> ImUserInfo?,
    nameOf: (String) -> String,
) {
    val info = resolveUser(uid)
    // 群昵称优先,空则退回目录名;头像字母兜底同步用该显示名。
    val label = nameOf(uid).ifBlank { info?.displayName.orEmpty() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceXs),
    ) {
        MemberAvatar(
            name = label,
            url = info?.avatarUrl,
            cacheKey = "im-avatar:$uid",
            size = Dimens.IconXl,
        )
        Text(
            text = label.ifBlank { uid.take(8) },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = Dimens.SpaceM),
        )
    }
}
