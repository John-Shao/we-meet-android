package com.we.meet.ui.records

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.BuildConfig
import com.we.meet.data.capture.CaptureJournal
import com.we.meet.data.capture.CaptureRecovery
import com.we.meet.service.CaptureForegroundService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureForegroundServiceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as CaptureFixtureApplication
    private var connection: ServiceConnection? = null
    private var service: CaptureForegroundService? = null
    @Before fun setup() {
        check(BuildConfig.WE_MEET_CAPTURE_NATIVE) { "Run the synthetic service fixture with the capture build flag" }
        app.reset()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
    @After fun close() {
        instrumentation.runOnMainSync {
            context.stopService(CaptureForegroundService.bindingIntent(context))
            connection?.let { context.unbindService(it) }
        }
        runBlocking { delay(200) }
    }
    private fun bind(): CaptureForegroundService {
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as CaptureForegroundService.LocalBinder).service
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        this.connection = connection
        assertTrue(context.bindService(CaptureForegroundService.bindingIntent(context), connection, Context.BIND_AUTO_CREATE))
        assertTrue(connected.await(5, TimeUnit.SECONDS))
        return requireNotNull(service)
    }
    private suspend fun until(check: () -> Boolean) = withTimeout(8000) { while (!check()) delay(20) }

    @Test fun bindRecoversLocalOpenMarkerWithoutStartingHardwareOrNetwork() = runBlocking {
        val viewer = requireNotNull(app.captureAccount)
        CaptureJournal.open(context, viewer, { app.captureAccount }).use { journal ->
            val recovery = CaptureRecovery(viewer, journal, app.captureRepository)
            val local = recovery.prepare("Fixture")
            recovery.start(local.id)
        }
        val calls = app.protocol.commandKeys.size
        val service = bind()
        until { service.state.value.ready }
        assertTrue(service.state.value.local!!.closed)
        assertTrue(service.state.value.local!!.interrupted)
        assertFalse(service.state.value.recording)
        assertNull(app.input)
        assertEquals(calls, app.protocol.commandKeys.size)
    }

    @Test fun foregroundStartPauseAndFinishDrainSyntheticAudioAndRemoveNotification() = runBlocking {
        val service = bind()
        until { service.state.value.ready }
        ActivityScenario.launch(ComponentActivity::class.java).use { activity ->
            activity.onActivity { CaptureForegroundService.start(it, "Synthetic recording") }
            until { service.state.value.recording && (app.input?.reads ?: 0) >= 3 }
            assertTrue(CaptureForegroundService.microphoneActive)
            until { context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 1007 } }
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == 1007 }
            notification.notification.actions.first().actionIntent.send()
            until { !service.state.value.busy && !service.state.value.recording }
            assertTrue(app.input!!.closed)
            assertFalse(CaptureForegroundService.microphoneActive)
            assertTrue(service.state.value.local!!.closed)
            assertFalse(service.state.value.error)
            assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 1007 })
            instrumentation.runOnMainSync { service.finish() }
            until { !service.state.value.busy }
            assertFalse(service.state.value.error)
            assertTrue(service.state.value.local!!.sealed)
            assertTrue(service.state.value.local!!.durationMs > 0)
            assertEquals(0, service.state.value.local!!.pendingBytes)
        }
    }

    @Test fun accountSwitchStopsSyntheticInputAndClearsBoundPrivateState() = runBlocking {
        val service = bind()
        until { service.state.value.ready }
        ActivityScenario.launch(ComponentActivity::class.java).use { activity ->
            activity.onActivity { CaptureForegroundService.start(it, "Private fixture") }
            until { service.state.value.recording && app.input != null }
            app.captureAccount = null
            until { service.state.value.viewer == null && app.input!!.closed }
            assertFalse(CaptureForegroundService.microphoneActive)
            assertNull(service.state.value.local)
            assertFalse(service.state.value.recording)
        }
    }

    @Test fun pauseWhileStartResponseIsPendingNeverOpensInputLater() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        app.protocol.afterCreate = { entered.complete(Unit); release.await() }
        val service = bind()
        until { service.state.value.ready }
        ActivityScenario.launch(ComponentActivity::class.java).use { activity ->
            activity.onActivity { CaptureForegroundService.start(it, "Delayed fixture") }
            withTimeout(8000) { entered.await() }
            instrumentation.runOnMainSync { service.pause() }
            release.complete(Unit)
            until { !service.state.value.busy }
            assertNull(app.input)
            assertFalse(CaptureForegroundService.microphoneActive)
            assertFalse(service.state.value.recording)
            assertTrue(service.state.value.local!!.closed)
            assertFalse(service.state.value.error)
            assertEquals("paused", service.state.value.local!!.remote!!.status)
        }
    }
}
