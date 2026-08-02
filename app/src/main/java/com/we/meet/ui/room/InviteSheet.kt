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
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.we.meet.BuildConfig
import com.we.meet.R
import com.we.meet.ui.theme.Dimens

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
    /**
     * Optional ISO 8601 scheduled-start (e.g. "2026-06-02T09:00+08:00").
     * When non-null + non-blank we show a "预约时间: ..." row and
     * include the localised time in the clipboard payload so the
     * paste-text the host shares mentions when to show up.
     */
    scheduledAtIso: String? = null,
    /**
     * When non-null, renders a primary "进入会议" button above the
     * copy/share row. Used by the "create later" flow on Home where
     * the host wants to share first and join later (or join now after
     * copying the link). Null when the sheet is opened from inside a
     * meeting — the host is already in.
     */
    onEnterRoom: (() -> Unit)? = null,
    /** Scheduled-meeting flow supplies this to share directly into IM chats. */
    onShareToChat: (() -> Unit)? = null,
) {
    // Force full expansion on open. The default partial-expanded state
    // hid the 复制/分享 action row below the fold on phones — users had to
    // drag the sheet up to discover the buttons, which is bad enough on a
    // host-side primary action to be a usability bug, not just a polish.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    val baseUrl = BuildConfig.WE_MEET_BASE_URL.trimEnd('/')
    val joinUrl = "$baseUrl/$roomSlug"
    val scheduledDisplay = remember(scheduledAtIso) {
        scheduledAtIso?.takeIf { it.isNotBlank() }?.let(::formatIsoForDisplay)
    }

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

    val baseInviteText = stringResource(R.string.invite_clipboard_format, joinUrl, roomSlug)
    val scheduledLine = scheduledDisplay?.let {
        "\n" + stringResource(R.string.invite_clipboard_scheduled, it)
    }.orEmpty()
    val inviteText = baseInviteText + scheduledLine

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.SpaceXl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.invite_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = Dimens.SpaceL),
            )

            // QR
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.invite_qr_desc),
                    modifier = Modifier
                        .size(Dimens.Room.QrSize)
                        .clip(RoundedCornerShape(Dimens.CornerS))
                        .background(Color.White)
                        .padding(Dimens.SpaceS),
                )
            } else {
                // Defensive fallback — MultiFormatWriter shouldn't fail for an
                // 8-digit URL, but a blank box beats a crash on the share path.
                Box(
                    modifier = Modifier
                        .size(Dimens.Room.QrSize)
                        .clip(RoundedCornerShape(Dimens.CornerS))
                        .background(Color.LightGray),
                )
            }

            Spacer(Modifier.height(Dimens.SpaceL))

            // 会议号
            Text(
                text = stringResource(R.string.room_slug_label, roomSlug),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            // Meeting name (smaller, secondary)
            if (roomName.isNotBlank()) {
                Spacer(Modifier.height(Dimens.SpaceXs))
                Text(
                    text = roomName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // Scheduled-start (only when host picked a time)
            if (scheduledDisplay != null) {
                Spacer(Modifier.height(Dimens.SpaceXs))
                Text(
                    text = stringResource(R.string.invite_scheduled_label, scheduledDisplay),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }

            // URL (small, monospace-ish — Material 3 doesn't expose a code style)
            Spacer(Modifier.height(Dimens.SpaceS))
            Text(
                text = joinUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Dimens.SpaceXl))
            HorizontalDivider()

            // "Create later" entry-point only: primary CTA above the
            // copy/share row so the host can immediately enter the
            // meeting they just made. In-meeting opens of this sheet
            // (no [onEnterRoom]) skip this row entirely.
            if (onEnterRoom != null) {
                androidx.compose.material3.Button(
                    onClick = onEnterRoom,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Dimens.SpaceM),
                ) {
                    Text(stringResource(R.string.invite_enter_room))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.SpaceS),
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
                        modifier = Modifier.size(Dimens.IconSmall),
                    )
                    Spacer(Modifier.size(Dimens.SpaceXs))
                    Text(stringResource(R.string.invite_copy_link))
                }
                TextButton(
                    onClick = { onShareToChat?.invoke() ?: startSystemShare(context, inviteText) },
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.IconSmall),
                    )
                    Spacer(Modifier.size(Dimens.SpaceXs))
                    Text(
                        stringResource(
                            if (onShareToChat != null) R.string.invite_share_to_chat
                            else R.string.invite_share,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(Dimens.SpaceL))
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("we-meet-invite", text))
}

/**
 * Format a server-supplied ISO 8601 offset timestamp (e.g.
 * "2026-06-02T09:00:00+08:00") as a short local wall-clock display.
 * Falls back to the raw string on parse failure so we never blank out
 * a row the user actually scheduled.
 */
private fun formatIsoForDisplay(iso: String): String = runCatching {
    val odt = java.time.OffsetDateTime.parse(iso)
    odt.atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrDefault(iso)

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
