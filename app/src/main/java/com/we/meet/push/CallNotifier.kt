package com.we.meet.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.we.meet.MainActivity
import com.we.meet.R
import com.we.meet.feature.im.call.CallSeed

/**
 * P2 来电通知 — the full-screen-intent notification for a push-delivered call
 * invite (App alive but backgrounded; the cold-killed case is covered by the
 * server-built manufacturer-channel notification instead).
 *
 * Channel `im_calls` is IMPORTANCE_HIGH with the system ringtone + vibration:
 * with the screen off the FSI launches MainActivity (deep link `wemeet://call`)
 * straight into the incoming-call screen; with the screen on it heads-up and
 * rings until tapped or timed out. One notification slot per call_id — a
 * repeated push for the same call replaces, terminal states cancel.
 */
object CallNotifier {

    private const val CHANNEL_ID = "im_calls"

    fun show(context: Context, seed: CallSeed, payloadJson: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, nm)

        val video = seed.media == "video"
        val title = context.getString(
            if (video) R.string.push_call_title_video else R.string.push_call_title_audio
        )
        val body = context.getString(
            if (video) R.string.push_call_body_video else R.string.push_call_body_audio,
            seed.fromName ?: context.getString(R.string.push_call_peer_fallback),
        )

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("wemeet://call?payload=${Uri.encode(payloadJson)}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            seed.callId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setOngoing(true)
            // 55s: mirrors the push TTL / the remainder of the caller's ring
            // window — a call notification must never outlive the call.
            .setTimeoutAfter(55_000L)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .build()

        nm.notify(notifyId(seed.callId), notification)
    }

    /** Terminal states / handled invites clear the slot for their call. */
    fun cancel(context: Context, callId: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(notifyId(callId))
    }

    private fun notifyId(callId: String) = callId.hashCode()

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.push_call_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(
                    ringtone,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
            }
        )
    }
}
