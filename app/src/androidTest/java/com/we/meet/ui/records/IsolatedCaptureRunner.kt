package com.we.meet.ui.records

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.we.meet.data.capture.CapturePcmSource
import com.we.meet.data.repository.CaptureRepository
import com.we.meet.service.CaptureServiceHost
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Foreground service fixtures use synthetic PCM and an in-memory protocol, never a microphone. */
class IsolatedCaptureRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, CaptureFixtureApplication::class.java.name, context)
}

class CaptureFixtureApplication : Application(), CaptureServiceHost {
    @Volatile override var captureAccount: String? = null
    internal var protocol = CaptureProtocolFixture()
    override var captureRepository = CaptureRepository(protocol) { captureAccount }
    @Volatile internal var input: SyntheticCaptureInput? = null
    internal fun reset() {
        captureAccount = "fgs-fixture-${UUID.randomUUID()}"
        protocol = CaptureProtocolFixture()
        captureRepository = CaptureRepository(protocol) { captureAccount }
        input = null
    }
    override fun openCaptureSource(context: Context, interrupted: () -> Unit): CapturePcmSource =
        SyntheticCaptureInput().also { input = it }
}

internal class SyntheticCaptureInput : CapturePcmSource {
    private val stop = CountDownLatch(1)
    @Volatile var closed = false
    @Volatile var reads = 0
    override fun start() {}
    override fun read(buffer: ShortArray): Int {
        if (stop.await(10, TimeUnit.MILLISECONDS)) return -3
        buffer.fill(7)
        reads++
        return buffer.size
    }
    override fun stop() { stop.countDown() }
    override fun close() { closed = true }
}
