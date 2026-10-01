package com.we.meet.feature.assistant.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.we.meet.feature.assistant.R

/** Started while visible; keeps an explicitly started AI session alive on Home/lock. */
class AssistantForegroundService : Service() {
    private var session: AssistantForegroundSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val renew = object : Runnable {
        override fun run() {
            wakeLock?.acquire(30 * 60 * 1000L)
            handler.postDelayed(this, 15 * 60 * 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.assistant_background_channel), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            setSound(null, null)
        })
        // Even an immediately canceled start must acknowledge Android's FGS
        // contract before onStartCommand sees the canceled lease and stops.
        val pending = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle(getString(R.string.assistant_title))
            .setOngoing(true).setOnlyAlertOnce(true).build()
        startForeground(NOTIFICATION_ID, pending, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val active = AssistantForegroundSession.current
        if (active == null) { stopSelf(startId); return START_NOT_STICKY }
        if (intent?.getStringExtra(EXTRA_SESSION) != active.id) return START_NOT_STICKY
        session = active
        active.attached = true
        if (intent.action == ACTION_STOP) {
            active.interrupted()
            return START_NOT_STICKY
        }
        try {
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val stop = PendingIntent.getService(this, active.id.hashCode(), Intent(this, AssistantForegroundService::class.java)
                .setAction(ACTION_STOP).putExtra(EXTRA_SESSION, active.id), pendingFlags)
            val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val title = if (active.kind == AssistantSessionKind.CALL) R.string.assistant_background_call else R.string.assistant_background_translation
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_call_outgoing)
                .setContentTitle(getString(title))
                .setContentText(getString(R.string.assistant_background_return))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.assistant_background_stop), stop)
                .apply { if (launch != null) setContentIntent(PendingIntent.getActivity(this@AssistantForegroundService, NOTIFICATION_ID, launch, pendingFlags)) }
                .build()
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (active.cameraEnabled) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(NOTIFICATION_ID, notification, types)
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WeMeet:AiAssistant").apply {
                    setReferenceCounted(false)
                }
                renew.run()
            }
            active.confirmation.complete(Unit)
        } catch (error: Exception) {
            active.confirmation.completeExceptionally(error)
            active.interrupted()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(renew)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        session?.interrupted()
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_SESSION = "assistant_session"
        internal const val ACTION_STOP = "com.we.meet.assistant.STOP"
        private const val CHANNEL = "ai_assistant_session"
        private const val NOTIFICATION_ID = 1003
    }
}
