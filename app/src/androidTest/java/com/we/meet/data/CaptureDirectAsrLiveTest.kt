package com.we.meet.data

import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in paid fixture: real provider, normal audio archive, formal original text, safe trash. */
class CaptureDirectAsrLiveTest {
    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(30000) {
        while(!condition()) delay(50)
    }
    @Test fun directPauseResumePublishesFormalTextAndKeepsAudioArchive() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBackend") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as WeMeetApp
        val arguments = InstrumentationRegistry.getArguments()
        app.authRepository.sendOtp(arguments.getString("e2ePhone") ?: "13800000009").getOrThrow()
        app.authRepository.verifyOtp(arguments.getString("e2ePhone") ?: "13800000009", arguments.getString("e2eOtp") ?: "123456").getOrThrow()
        val api=app.apiClient
        val device=UUID.randomUUID().toString()
        val create=CreateCaptureDto(device, UUID.randomUUID().toString(), "Direct ASR formal e2e ${System.currentTimeMillis()}")
        var capture=api.captureApi.create(UUID.randomUUID().toString(), create).capture
        val viewer="live-direct-${UUID.randomUUID()}"
        val journal=DirectAsrJournal.open(instrumentation.targetContext, viewer) { viewer }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        var tap=CapturePcmTap()
        val controller=CaptureDirectAsrController(journal, api.captureDirectAsrApi, api.assistantTranscriptionApi,
            api.captureTranscriptionApi, scope, { true }, { tap.attach(exclusive=true) })
        suspend fun command(name: String) {
            capture=api.captureApi.read(capture.id)
            capture=api.captureApi.command(capture.id, UUID.randomUUID().toString(), create.leaseKey,
                CaptureCommandDto(name, device, capture.revision)).capture
        }
        var sequence=0
        var origin=0L
        try {
            command("start")
            val local=LocalCapture(UUID.randomUUID().toString(), System.currentTimeMillis(), UUID.randomUUID().toString(), create, capture)
            val source=instrumentation.context.assets.open("aoq-english.pcm").use { it.readBytes() }
            repeat(2) { turn ->
                tap=CapturePcmTap(origin)
                if(turn == 0) controller.start(local) else { command("resume"); controller.resume(local) }
                waitFor { controller.state.value.phase in setOf("running", "error") }
                assertEquals("running", controller.state.value.phase)
                val pcm=ByteArray(((source.size+3199)/3200)*3200)
                source.copyInto(pcm)
                val samples=ShortArray(pcm.size/2)
                ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                for(offset in samples.indices step 1600) {
                    tap.offer(samples.copyOfRange(offset, offset+1600),1600)
                    delay(100)
                }
                controller.pause()
                assertEquals("paused", controller.state.value.phase)
                command("pause")
                val wave=CaptureWave.encode(samples)
                val info=CaptureWave.inspect(wave)
                val fields=mapOf("device_id" to device, "sequence" to (++sequence).toString(), "start_ms" to origin.toString(), "checksum" to info.checksum)
                    .mapValues { it.value.toRequestBody("text/plain".toMediaType()) }
                assertTrue(api.captureApi.upload(capture.id, create.leaseKey, fields,
                    MultipartBody.Part.createFormData("audio", "chunk.wav", wave.toRequestBody("audio/wav".toMediaType()))).stored)
                origin+=info.durationMs
            }
            assertTrue(controller.state.value.rows.sumOf { it.text.length } > 10)
            assertTrue(controller.state.value.rows.any { it.startMs >= origin/2 })
            command("stop")
            val manifest=api.captureApi.seal(capture.id, create.leaseKey, SealCaptureDto(device,sequence,false))
            assertEquals(origin,manifest.durationMs); assertTrue(manifest.gaps.isEmpty())
            command("finalize")
            controller.save()
            assertEquals("saved",controller.state.value.phase)
            val record=api.meetingRecordApi.record(capture.recordId)
            val rows=api.meetingRecordApi.originals(record.id,record.revision,null,null,null).results
            assertEquals(controller.state.value.rows.map { it.text },rows.map { it.originalText ?: it.text })
            assertTrue(rows.any { it.startMs >= origin/2 })
            val job=api.captureTranscriptionApi.state(capture.id).results.single()
            assertEquals("direct",job.transport); assertEquals("succeeded",job.status)
            assertEquals(rows.size,job.finalCount)
        } finally {
            controller.close(); scope.coroutineContext[Job]?.cancelAndJoin(); journal.close(); tap.close()
            runCatching {
                capture=api.captureApi.read(capture.id)
                if(capture.status !in setOf("stopping","stopped")) command("stop")
                if(capture.status != "stopped") command("finalize")
                val record=api.meetingRecordApi.record(capture.recordId)
                api.meetingRecordApi.lifecycle(record.id, RecordLifecycleRequest("trashed", requireNotNull(record.lifecycleRevision)))
            }.getOrThrow()
        }
    }
}
