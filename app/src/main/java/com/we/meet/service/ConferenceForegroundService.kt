package com.we.meet.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.we.meet.MainActivity
import com.we.meet.R

/**
 * Foreground service that pins cam + mic + media-playback access while the
 * user is in a meeting and the app is backgrounded. Without it, Android
 * releases the Camera2 session within a few seconds of background, pauses
 * microphone capture, and throttles SCTP/DTLS keepalives — the LiveKit
 * server then kicks the participant after ~10 s.
 *
 * The service is started via [start] on successful room-Connected transition
 * and stopped via [stop] on leave / end-meeting / host-ended. It's NOT
 * bound — we don't need to communicate with it; we just need it to exist
 * so the OS grants us the three FGS carve-outs.
 */
class ConferenceForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val roomName = intent?.getStringExtra(EXTRA_ROOM_NAME).orEmpty()
        val notification = buildNotification(roomName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Declare each type we actually use. The OS verifies against the
            // manifest's foregroundServiceType AND the runtime
            // FOREGROUND_SERVICE_* permissions.
            val types =
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Don't auto-restart a meeting service the OS killed — by the time
        // we'd be re-summoned the room is almost certainly gone.
        isRunning = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        // The FGS lives exactly as long as the LiveKit session, so its
        // teardown IS the "call/meeting ended" signal. The call controller
        // uses it to persist the 1:1 call's "通话时长" log; plain meetings
        // no-op inside (no connected call tracked). peek() (not get()) so a
        // teardown never lazily constructs an IM session as a side effect.
        com.we.meet.feature.im.ImSession.peek()?.calls?.onCallRoomEnded()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.meeting_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(roomName: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentPi = PendingIntent.getActivity(this, 0, tapIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle(getString(R.string.meeting_notification_title))
            .setContentText(
                if (roomName.isBlank()) getString(R.string.meeting_notification_title)
                else getString(R.string.meeting_notification_content, roomName)
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .setShowWhen(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "meeting_in_progress"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_ROOM_NAME = "room_name"

        /**
         * True while the meeting FGS is alive — the service runs exactly for
         * the duration of a LiveKit room session, so this doubles as the
         * "user is in a meeting/call" busy signal for 1:1 call handling.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context, roomName: String) {
            val intent = Intent(context, ConferenceForegroundService::class.java)
                .putExtra(EXTRA_ROOM_NAME, roomName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConferenceForegroundService::class.java))
        }
    }
}
