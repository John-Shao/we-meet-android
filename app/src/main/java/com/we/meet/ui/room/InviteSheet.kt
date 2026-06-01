package com.we.meet.ui.room

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.we.meet.BuildConfig
import com.we.meet.R

private const val QR_SIZE_PX = 600

/**
 * Bottom sheet for sharing the meeting joining info — used as the host's
 * "邀请参会人" affordance off the in-room top bar's meeting-id area.
 *
 * Shows the joinable 8-digit slug as a 会议号, a QR encoding the same
 * URL the recipient would tap to deep-link in (S1.6 wires the App Links
 * side; until then the link still opens fine in a browser → web client),
 * the full link as plain text, and two actions:
 *
 *   - "复制链接": dumps an invite-ready blob to the clipboard
 *     (sentence + URL on a separate line so it looks right in a chat
 *     paste with the URL still being a clickable target).
 *   - "系统分享": fires Intent.ACTION_SEND so WeChat / DingTalk / Mail
 *     etc. can pick it up via their own SEND intent-filters. We do NOT
 *     integrate any vendor SDK — that's a separate workstream gated on
 *     vendor app-IDs and signature audits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteSheet(
    roomName: String,
    roomSlug: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current

    val baseUrl = BuildConfig.WE_MEET_BASE_URL.trimEnd('/')
    val joinUrl = "$baseUrl/$roomSlug"

    // Encode once per slug. zxing's MultiFormatWriter is cheap, but we still
    // don't want to re-encode on every recomposition (clipboard toast, etc).
    val qrBitmap = remember(joinUrl) {
        runCatching {
            val matrix = MultiFormatWriter().encode(
                joinUrl, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX,
            )
            BarcodeEncoder().createBitmap(matrix)
        }.getOrNull()
    }

    val inviteText = stringResource(R.string.invite_clipboard_format, joinUrl, roomSlug)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.invite_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // QR
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.invite_qr_desc),
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .padding(8.dp),
                )
            } else {
                // Defensive fallback — MultiFormatWriter shouldn't fail for an
                // 8-digit URL, but a blank box beats a crash on the share path.
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.LightGray),
                )
            }

            Spacer(Modifier.height(16.dp))

            // 会议号
            Text(
                text = stringResource(R.string.room_slug_label, roomSlug),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            // Meeting name (smaller, secondary)
            if (roomName.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = roomName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // URL (small, monospace-ish — Material 3 doesn't expose a code style)
            Spacer(Modifier.height(8.dp))
            Text(
                text = joinUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(
                    onClick = {
                        copyToClipboard(context, inviteText)
                        Toast.makeText(
                            context,
                            context.getString(R.string.invite_copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.invite_copy_link))
                }
                TextButton(
                    onClick = { startSystemShare(context, inviteText) },
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.invite_share))
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("we-meet-invite", text))
}

private fun startSystemShare(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(
        intent,
        context.getString(R.string.invite_share_chooser_title),
    )
    runCatching { context.startActivity(chooser) }
}
