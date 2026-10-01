package com.we.meet.ui.ai

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.feature.assistant.background.AssistantForegroundSession
import com.we.meet.feature.assistant.background.AssistantForegroundService
import com.we.meet.feature.assistant.background.AssistantSessionKind
import kotlinx.coroutines.*
import livekit.org.webrtc.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AssistantForegroundSessionTest {
    @Test fun notificationInputAndOutputControlsReflectChangesAndRejectStaleActions() = runBlocking {
        ActivityScenario.launch(ComponentActivity::class.java).use { activity ->
            val lease = AssistantForegroundSession.start(context, AssistantSessionKind.TRANSLATION, false) {}
            var paused = false
            var muted = false
            var clicks = 0
            fun publish() {
                lease.controls(com.we.meet.feature.assistant.background.AssistantControlState(true, paused, muted),
                    { paused = !paused; clicks++; publish() }, { muted = !muted; clicks++; publish() })
            }
            withContext(Dispatchers.Main) { publish() }
            until { currentNotification()?.notification?.actions?.size == 3 }
            val pause = currentNotification()!!.notification.actions[0].actionIntent
            activity.moveToState(Lifecycle.State.CREATED)
            pause.send()
            until { paused && currentNotification()?.notification?.actions?.firstOrNull()?.title == context.getString(com.we.meet.feature.assistant.R.string.assistant_background_resume) }
            currentNotification()!!.notification.actions[1].actionIntent.send()
            until { muted && currentNotification()?.notification?.actions?.get(1)?.title == context.getString(com.we.meet.feature.assistant.R.string.assistant_background_unmute) }
            withContext(Dispatchers.Main) { lease.close() }
            until { currentNotification() == null }
            activity.moveToState(Lifecycle.State.RESUMED)
            val next = AssistantForegroundSession.start(context, AssistantSessionKind.CALL, false) {}
            try {
                pause.send(); delay(150)
                assertEquals(2, clicks)
            } finally { withContext(Dispatchers.Main) { next.close() } }
        }
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private fun currentNotification() = notifications.activeNotifications.singleOrNull { it.notification.channelId == "ai_assistant_session" }
    private suspend fun until(test: () -> Boolean) { withTimeout(7000) { while (!test()) delay(20) } }
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    private fun wakeLockHeld(): Boolean {
        val dump = String(shell("dumpsys power"))
        check(dump.contains("Wake Locks:") && dump.contains("Suspend Blockers:"))
        // New Android versions append a history containing already released locks.
        return dump.substringAfter("Wake Locks:").substringBefore("Suspend Blockers:").contains("WeMeet:AiAssistant")
    }
    private suspend fun stopAction(): android.app.PendingIntent {
        // NotificationManager publishes the service's updated notification asynchronously.
        until { currentNotification()?.notification?.actions?.size == 1 }
        return currentNotification()!!.notification.actions.single().actionIntent
    }

    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) { context.stopService(android.content.Intent(context, AssistantForegroundService::class.java)) }
        until { currentNotification() == null }
    }

    @Before fun permissions() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    @Test fun cameraKeepsProducingFramesAfterHomeAndScreenLock() = runBlocking {
        ActivityScenario.launch(ComponentActivity::class.java).use { activity ->
            val ended = AtomicBoolean()
            val session = AssistantForegroundSession.start(context, AssistantSessionKind.CALL, true) { ended.set(true) }
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            val egl = EglBase.create()
            val helper = SurfaceTextureHelper.create("BackgroundCameraTest", egl.eglBaseContext)
            val frames = AtomicInteger()
            val enumerator = Camera2Enumerator(context)
            val camera = requireNotNull(enumerator.createCapturer(enumerator.deviceNames.first(), null))
            try {
                camera.initialize(helper, context, object : CapturerObserver {
                    override fun onCapturerStarted(success: Boolean) = Unit
                    override fun onCapturerStopped() = Unit
                    override fun onFrameCaptured(frame: VideoFrame) { frames.incrementAndGet() }
                })
                camera.startCapture(320, 240, 5)
                until { frames.get() >= 3 }
                activity.moveToState(Lifecycle.State.CREATED)
                var before = frames.get()
                until { frames.get() >= before + 5 }
                shell("input keyevent KEYCODE_SLEEP")
                before = frames.get()
                until { frames.get() >= before + 5 }
                assertFalse(ended.get())
                assertNotNull(currentNotification())
                assertTrue(wakeLockHeld())
                shell("input keyevent KEYCODE_WAKEUP")
                shell("wm dismiss-keyguard")
                activity.moveToState(Lifecycle.State.RESUMED)
                before = frames.get()
                until { frames.get() >= before + 3 }
            } finally {
                camera.stopCapture(); camera.dispose(); helper.dispose(); egl.release()
                withContext(Dispatchers.Main) { session.close() }
                shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
                activity.moveToState(Lifecycle.State.RESUMED)
            }
            until { currentNotification() == null }
            assertFalse(wakeLockHeld())
        }
    }

    @Test fun staleStopCannotEndNewSessionAndCurrentStopReleasesService() = runBlocking {
        ActivityScenario.launch(ComponentActivity::class.java).use {
            val old = AssistantForegroundSession.start(context, AssistantSessionKind.TRANSLATION, false) {}
            val staleStop = try { stopAction() } finally { withContext(Dispatchers.Main) { old.close() } }
            until { currentNotification() == null }
            val ended = AtomicBoolean()
            val current = AssistantForegroundSession.start(context, AssistantSessionKind.CALL, false) { ended.set(true) }
            try {
                staleStop.send()
                delay(150)
                assertFalse(ended.get())
                // Upgrade the existing voice service before opening a camera.
                current.camera(true)
                current.camera(false)
                stopAction().send()
                until { ended.get() && currentNotification() == null }
            } finally { withContext(Dispatchers.Main) { current.close() } }
        }
    }

    @Test fun cancellationDuringStartupDoesNotLeaveAnOrphanService() = runBlocking {
        ActivityScenario.launch(ComponentActivity::class.java).use {
            val started = CompletableDeferred<Unit>()
            val task = launch(Dispatchers.Main) {
                started.complete(Unit)
                val lease = AssistantForegroundSession.start(context, AssistantSessionKind.CALL, false) {}
                try { awaitCancellation() } finally { lease.close() }
            }
            started.await()
            task.cancelAndJoin()
            // A pending Android service-start intent must see no owner and stop itself.
            delay(300)
            assertNull(currentNotification())
            val replacement = AssistantForegroundSession.start(context, AssistantSessionKind.CALL, false) {}
            withContext(Dispatchers.Main) { replacement.close() }
            until { currentNotification() == null }
        }
    }
}
