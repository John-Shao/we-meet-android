package com.we.meet.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.igexin.sdk.GTIntentService
import com.igexin.sdk.message.GTCmdMessage
import com.igexin.sdk.message.GTNotificationMessage
import com.igexin.sdk.message.GTTransmitMessage
import com.we.meet.MainActivity
import com.we.meet.R
import org.json.JSONObject

private const val TAG = "WeMeetPush"

/**
 * Getui callback service (runs in the main process; registered from
 * [com.we.meet.WeMeetApp] via PushManager.registerPushIntentService).
 *
 * Two jobs:
 *  - cid lifecycle: persist the cid and hand it to [PushTokenUploader] for
 *    backend registration;
 *  - transparent (透传) messages: the backend sends a small JSON payload
 *    `{"title", "body", "type": "im", "cid"}` — render it as a system
 *    notification whose tap opens `wemeet://im?cid=...` (MainActivity's
 *    deep-link path stashes the cid for AppNav).
 */
class WeMeetGtIntentService : GTIntentService() {

    override fun onReceiveServicePid(context: Context, pid: Int) {}

    override fun onReceiveClientId(context: Context, clientId: String) {
        Log.i(TAG, "onReceiveClientId")
        PushTokenUploader.onNewCid(context, clientId)
    }

    override fun onReceiveMessageData(context: Context, msg: GTTransmitMessage) {
        val payload = msg.payload ?: return
        val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }
            .getOrElse {
                Log.w(TAG, "transparent message is not JSON, ignoring", it)
                return
            }
        when (val type = json.optString("type")) {
            "im" -> showImNotification(
                context = context,
                title = json.optString("title").ifBlank {
                    context.getString(R.string.im_push_default_title)
                },
                body = json.optString("body"),
                cid = json.optString("cid"),
            )
            else -> Log.d(TAG, "unhandled push type: $type")
        }
    }

    override fun onReceiveOnlineState(context: Context, online: Boolean) {}

    override fun onReceiveCommandResult(context: Context, cmdMessage: GTCmdMessage) {}

    override fun onNotificationMessageArrived(context: Context, msg: GTNotificationMessage) {}

    override fun onNotificationMessageClicked(context: Context, msg: GTNotificationMessage) {}

    private fun showImNotification(context: Context, title: String, body: String, cid: String) {
        // Without POST_NOTIFICATIONS (runtime on 33+) notify() is a no-op /
        // SecurityException depending on OEM; bail out quietly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted, dropping IM push")
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, nm)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("wemeet://im?cid=${Uri.encode(cid)}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            // Distinct requestCode per conversation so parallel notifications
            // don't overwrite each other's cid extra.
            cid.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        // One notification slot per conversation: a newer message from the
        // same chat replaces the older one instead of stacking endlessly.
        nm.notify(cid.hashCode(), notification)
    }

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.im_push_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
    }

    private companion object {
        const val CHANNEL_ID = "im_messages"
    }
}
