package com.we.meet.feature.im.ui.chat

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
import androidx.compose.ui.unit.dp
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
    onDismiss: () -> Unit,
) {
    val (read, unread) = remember(memberUids, readMarkers, seq) {
        memberUids.partition { (readMarkers[it] ?: 0L) >= seq }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
            item {
                SectionHeader(stringResource(R.string.im_receipt_read_section, read.size))
            }
            items(read, key = { "r-$it" }) { uid -> MemberLine(uid, resolveUser) }
            item {
                SectionHeader(stringResource(R.string.im_receipt_unread_section, unread.size))
            }
            items(unread, key = { "u-$it" }) { uid -> MemberLine(uid, resolveUser) }
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
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

@Composable
private fun MemberLine(uid: String, resolveUser: (String) -> ImUserInfo?) {
    val info = resolveUser(uid)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
    ) {
        MemberAvatar(
            name = info?.displayName.orEmpty(),
            url = info?.avatarUrl,
            cacheKey = "im-avatar:$uid",
            size = 32.dp,
        )
        Text(
            text = info?.displayName.orEmpty().ifBlank { uid.take(8) },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
