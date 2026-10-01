package com.we.meet.feature.assistant.background

import android.app.NotificationChannel
import android.app.Notification
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
        running = this
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
        if (intent.action == ACTION_INPUT || intent.action == ACTION_OUTPUT) {
            active.control(intent.action == ACTION_INPUT)
            return START_NOT_STICKY
        }
        try {
            val notification = notification(active)
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
        if (running === this) running = null
        handler.removeCallbacks(renew)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        session?.interrupted()
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private var running: AssistantForegroundService? = null
        internal fun refresh(owner: AssistantForegroundSession) {
            val service = running ?: return
            if (service.session === owner && AssistantForegroundSession.current === owner) {
                service.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, service.notification(owner))
            }
        }
        internal const val EXTRA_SESSION = "assistant_session"
        internal const val ACTION_STOP = "com.we.meet.assistant.STOP"
        internal const val ACTION_INPUT = "com.we.meet.assistant.INPUT"
        internal const val ACTION_OUTPUT = "com.we.meet.assistant.OUTPUT"
        private const val CHANNEL = "ai_assistant_session"
        private const val NOTIFICATION_ID = 1003
    }

    private fun notification(active: AssistantForegroundSession): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun action(name: String) = PendingIntent.getService(this, 0, Intent(this, AssistantForegroundService::class.java)
            .setAction(name).setData(android.net.Uri.parse("we-meet://assistant/${active.id}/$name"))
            .putExtra(EXTRA_SESSION, active.id), flags)
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val state = active.controlState
        val title = if (active.kind == AssistantSessionKind.CALL) R.string.assistant_background_call else R.string.assistant_background_translation
        val status = when {
            !state.ready -> R.string.assistant_background_connecting
            state.inputPaused -> R.string.assistant_background_paused
            else -> R.string.assistant_background_listening
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_outgoing).setContentTitle(getString(title))
            .setContentText(getString(status) + " · " + getString(if (state.outputMuted) R.string.assistant_background_muted else R.string.assistant_background_sound))
            .setCategory(NotificationCompat.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (state.ready) {
                    addAction(android.R.drawable.ic_btn_speak_now, getString(if (state.inputPaused) R.string.assistant_background_resume else R.string.assistant_background_pause), action(ACTION_INPUT))
                    addAction(android.R.drawable.ic_lock_silent_mode, getString(if (state.outputMuted) R.string.assistant_background_unmute else R.string.assistant_background_mute), action(ACTION_OUTPUT))
                }
                addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.assistant_background_stop), action(ACTION_STOP))
                if (launch != null) setContentIntent(PendingIntent.getActivity(this@AssistantForegroundService, NOTIFICATION_ID, launch, flags))
            }.build()
    }
}
