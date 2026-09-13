package com.we.meet.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.we.meet.BuildConfig
import com.we.meet.MainActivity
import com.we.meet.R
import com.we.meet.data.capture.CaptureJournal
import com.we.meet.data.capture.CapturePcmPump
import com.we.meet.data.capture.CapturePcmSource
import com.we.meet.data.capture.CapturePumpOutcome
import com.we.meet.data.capture.CaptureRecovery
import com.we.meet.data.capture.CaptureWave
import com.we.meet.data.capture.LocalCapture
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CaptureServiceState(
    val viewer: String? = null,
    val local: LocalCapture? = null,
    val ready: Boolean = false,
    val recording: Boolean = false,
    val busy: Boolean = false,
    val error: Boolean = false,
    val uploadFailed: Boolean = false,
) { override fun toString() = "CaptureServiceState(<private>)" }

/** Bound for local recovery/UI; promoted to microphone FGS only by a visible user action. */
class CaptureForegroundService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@CaptureForegroundService }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val stopEpoch = AtomicLong()
    private val mutableState = MutableStateFlow(CaptureServiceState())
    val state = mutableState.asStateFlow()
    private var journal: CaptureJournal? = null
    private var recovery: CaptureRecovery? = null
    private var viewer: String? = null
    private var pump: CapturePcmPump? = null
    private var audio: Deferred<CapturePumpOutcome>? = null
    private var upload: Job? = null
    private var initialization: Job? = null
    private var foreground = false
    @Volatile private var destroyed = false
    private var operationCount = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private val app get() = application as CaptureServiceHost

    override fun onCreate() {
        super.onCreate()
        initialization = scope.launch {
            try {
                check(BuildConfig.WE_MEET_CAPTURE_NATIVE)
                val account = requireNotNull(app.captureAccount)
                viewer = account
                val opened = withContext(Dispatchers.IO) {
                    CaptureJournal.open(this@CaptureForegroundService, account, { currentViewer() }).also { journal = it }
                }
                val controller = CaptureRecovery(account, opened, app.captureRepository)
                recovery = controller
                val rows = controller.load()
                mutableState.value = CaptureServiceState(account, rows.firstOrNull { !it.sealed } ?: rows.firstOrNull(), ready = true, busy = operationCount > 0)
                ready.complete(Unit)
                while (true) {
                    delay(250)
                    if (currentViewer() != account) {
                        stopEpoch.incrementAndGet()
                        pump?.requestStop(true)
                        mutableState.value = CaptureServiceState(error = true)
                        removeForeground()
                        stopSelf()
                        break
                    }
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) {
                mutableState.value = CaptureServiceState(error = true)
                ready.completeExceptionally(IllegalStateException("Recording recovery unavailable"))
                removeForeground()
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            if (intent.getStringExtra(EXTRA_VIEWER) == viewer) pause()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || !BuildConfig.WE_MEET_CAPTURE_NATIVE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            check(intent.getStringExtra(EXTRA_VIEWER) == currentViewer())
            check(!ConferenceForegroundService.isRunning && hasMicrophonePermission())
            if (mutableState.value.recording) return START_NOT_STICKY
            // Promotion happens before any database/network work to meet the FGS deadline.
            val notification = notification(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION_ID, notification)
            foreground = true
            if (mutableState.value.busy) { removeForeground(); stopSelf(startId); return START_NOT_STICKY }
            startInput(intent.getStringExtra(EXTRA_TITLE).orEmpty())
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(error = true)
            removeForeground()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun currentViewer(): String? = app.captureAccount
    private fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun authorized() = !destroyed && viewer != null && currentViewer() == viewer &&
        hasMicrophonePermission() && !ConferenceForegroundService.isRunning

    private fun startInput(title: String) {
        if (mutableState.value.recording || mutableState.value.busy) return
        val epoch = stopEpoch.get()
        perform {
            check(authorized() && epoch == stopEpoch.get())
            val controller = requireNotNull(recovery)
            val existing = mutableState.value.local
            val local = if (existing == null || existing.sealed) controller.prepare(title) else existing
            mutableState.value = mutableState.value.copy(local = local)
            controller.start(local.id)
            var opening: CapturePcmSource? = null
            try {
                check(authorized() && epoch == stopEpoch.get())
                withContext(Dispatchers.IO) {
                    opening = app.openCaptureSource(this@CaptureForegroundService) { pump?.requestStop(true) }
                }
                check(authorized() && epoch == stopEpoch.get())
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WeMeet:IndependentCapture").apply {
                    setReferenceCounted(false)
                    acquire(CaptureWave.MAX_DURATION_MS + 5000)
                }
                val input = CapturePcmPump(requireNotNull(opening), { pcm ->
                    requireNotNull(journal).append(local.id, pcm)
                    scope.launch {
                        if (runCatching { refresh() }.isSuccess) scheduleUpload(local.id)
                    }
                }, { authorized() })
                pump = input
                opening = null // Ownership transferred to the pump, including device release.
                val running = audioScope.async { input.run() }
                audio = running
                mutableState.value = mutableState.value.copy(recording = true)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(true))
                scope.launch {
                    val outcome = running.await()
                    if (audio === running && (outcome.interrupted || outcome.failed)) pause(unexpected = true)
                }
            } catch (error: Exception) {
                controller.closeLocally(local.id, true)
                throw error
            } finally { opening?.close() }
            refresh()
        }
    }

    /** Immediately stops hardware, even while a previous network action is still pending. */
    fun pause(unexpected: Boolean = false) {
        stopEpoch.incrementAndGet()
        pump?.requestStop(unexpected)
        perform(queue = true) {
            val local = mutableState.value.local ?: return@perform
            val interrupted = drain(unexpected)
            requireNotNull(recovery).closeLocally(local.id, interrupted)
            removeForeground()
            stopSelf()
            requireNotNull(recovery).pause(local.id, interrupted)
            refresh()
        }
    }

    fun finish() {
        stopEpoch.incrementAndGet()
        pump?.requestStop()
        perform(queue = true) {
            val local = mutableState.value.local ?: return@perform
            val interrupted = drain(false)
            requireNotNull(recovery).closeLocally(local.id, interrupted)
            removeForeground()
            stopSelf()
            upload?.join()
            requireNotNull(recovery).finish(local.id)
            refresh()
        }
    }

    fun retryUploads() = perform {
        val local = mutableState.value.local ?: return@perform
        requireNotNull(recovery).retryUploads(local.id)
        mutableState.value = mutableState.value.copy(uploadFailed = false)
        refresh()
    }

    private suspend fun drain(unexpected: Boolean): Boolean {
        pump?.requestStop(unexpected)
        val result = audio?.await()
        pump = null
        audio = null
        mutableState.value = mutableState.value.copy(recording = false)
        return unexpected || result?.interrupted == true || result?.failed == true
    }

    private fun scheduleUpload(id: String) {
        if (upload?.isActive == true || mutableState.value.uploadFailed) return
        upload = scope.launch {
            try { requireNotNull(recovery).uploadPending(id); refresh() }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { mutableState.value = mutableState.value.copy(uploadFailed = true) }
        }
    }

    private suspend fun refresh() {
        check(currentViewer() == viewer)
        val id = mutableState.value.local?.id ?: return
        val row = withContext(Dispatchers.IO) { requireNotNull(journal).get(id) }
        mutableState.value = mutableState.value.copy(local = row)
    }

    private fun perform(queue: Boolean = false, run: suspend () -> Unit) {
        if (!queue && mutableState.value.busy) return
        operationCount++
        mutableState.value = mutableState.value.copy(busy = true, error = false)
        scope.launch {
            operations.withLock {
                try {
                    mutableState.value = mutableState.value.copy(error = false)
                    ready.await()
                    check(currentViewer() == viewer)
                    run()
                } catch (canceled: CancellationException) { throw canceled }
                catch (_: Exception) {
                    mutableState.value = mutableState.value.copy(error = true)
                    if (pump == null) { removeForeground(); stopSelf() }
                    runCatching { refresh() }
                } finally {
                    operationCount--
                    mutableState.value = mutableState.value.copy(busy = operationCount > 0)
                }
            }
        }
    }

    private fun removeForeground() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
    }

    private fun notification(recording: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.capture_notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false); setSound(null, null); enableVibration(false)
        })
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), flags)
        val pause = PendingIntent.getService(this, NOTIFICATION_ID, Intent(this, CaptureForegroundService::class.java)
            .setAction(ACTION_PAUSE).putExtra(EXTRA_VIEWER, viewer ?: currentViewer()), flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(if (recording) R.string.capture_notification_recording else R.string.capture_notification_starting))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, getString(R.string.capture_pause), pause).build()
    }

    override fun onDestroy() {
        destroyed = true
        stopEpoch.incrementAndGet()
        pump?.requestStop(true)
        scope.cancel()
        val running = audio
        val opening = initialization
        val id = mutableState.value.local?.id
        val controller = recovery
        mutableState.value = CaptureServiceState()
        // Best-effort orderly teardown; abrupt process death is handled by the durable open marker.
        audioScope.launch {
            try {
                opening?.join()
                running?.await()
                if (id != null) runCatching { controller?.closeLocally(id, true) }
            } finally { journal?.close(); audioScope.cancel() }
        }
        removeForeground()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "independent_audio_capture"
        private const val NOTIFICATION_ID = 1007
        private const val ACTION_START = "com.we.meet.capture.START"
        private const val ACTION_PAUSE = "com.we.meet.capture.PAUSE"
        private const val EXTRA_VIEWER = "capture_viewer"
        private const val EXTRA_TITLE = "capture_title"

        fun start(activity: ComponentActivity, title: String = "") {
            check(BuildConfig.WE_MEET_CAPTURE_NATIVE && title.length <= 500)
            check(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            check(ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            val app = activity.application as CaptureServiceHost
            check(!ConferenceForegroundService.isRunning)
            val viewer = requireNotNull(app.captureAccount)
            ContextCompat.startForegroundService(activity, Intent(activity, CaptureForegroundService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_VIEWER, viewer).putExtra(EXTRA_TITLE, title))
        }

        fun bindingIntent(context: Context) = Intent(context, CaptureForegroundService::class.java)
    }
}
