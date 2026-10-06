package com.we.meet.data

import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.*
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import okhttp3.RequestBody
import org.junit.Assert.*
import org.junit.Test

class CaptureDirectAsrControllerTest {
    private class Fixture : CaptureDirectAsrApi, CaptureTranscriptionApi, AssistantTranscriptionApi {
        var job = CaptureAsrJobDto(UUID.randomUUID().toString(), 1, "running", 0, 0, 0, "uploading", "live", false, transport="direct")
        var created = false
        var failSync = false
        var loseFinish = false
        var models = 0
        var finished = false
        var authorized = true
        var loseStart = false
        var credentialGate: CompletableDeferred<Unit>? = null
        val delivered = linkedMapOf<Int, DirectAsrFinal>()
        override suspend fun start(capture: String, lease: String, key: String, body: RequestBody): CaptureAsrCreatedDto {
            created=true
            if(loseStart) { loseStart=false; throw IOException("unknown allocation") }
            return CaptureAsrCreatedDto(job, true)
        }
        override suspend fun sync(capture: String, job: String, lease: String, body: DirectAsrSync): CaptureAsrJobDto {
            if(failSync) throw IOException("offline")
            body.finals.forEach { row -> delivered[row.sequence]?.let { require(it == row) }; delivered[row.sequence]=row }
            if(body.operation == "finish") {
                finished=true; this.job=this.job.copy(status="succeeded")
                if(loseFinish) { loseFinish=false; throw IOException("unknown finish") }
            }
            this.job=this.job.copy(finalCount=delivered.size)
            return this.job
        }
        override suspend fun session(): DirectAsrCredentials {
            credentialGate?.await()
            return DirectAsrCredentials(DirectAsrWire.MODEL, "wss://test.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference", "st-test", Long.MAX_VALUE)
        }
        override suspend fun state(capture: String) = CaptureAsrStateDto(available=true, liveAvailable=true, directAvailable=true, results=if(created) listOf(job) else emptyList())
        override suspend fun request(capture: String, key: String, request: RequestBody): CaptureAsrCreatedDto = error("No cloud dispatch")
        override suspend fun cancel(capture: String, job: String, empty: RequestBody): CaptureAsrJobDto = error("No cloud cancellation")
        override suspend fun preview(capture: String, job: String, after: Int): CaptureAsrPreviewDto = error("No cloud preview")
    }
    private class Wire(private val callback: (List<DirectAsrRow>) -> Unit, private val fixture: Fixture) : DirectAsrConnection {
        @Volatile var input=0L
        var fail=false
        override suspend fun start(credentials: DirectAsrCredentials) { fixture.models++ }
        override fun send(pcm: ByteArray): Boolean { if(fail) return false; input+=pcm.size/32; return true }
        override suspend fun finish(): List<DirectAsrRow> {
            val rows=if(input>0) listOf(DirectAsrRow(0, "Tail ${fixture.models}", 0, input)) else emptyList()
            callback(rows); return rows
        }
        override fun close() = Unit
    }
    private suspend fun waitFor(condition: () -> Boolean) { withTimeout(8000) { while(!condition()) delay(10) } }
    private suspend fun withFixture(block: suspend (Fixture, CaptureDirectAsrController, LocalCapture, (Long) -> CapturePcmTap, () -> Wire, DirectAsrJournal, CoroutineScope, String) -> Unit) {
        val viewer="direct-test-${UUID.randomUUID()}"
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=DirectAsrJournal.open(context, viewer) { viewer }
        val fixture=Fixture()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val device=UUID.randomUUID().toString()
        val remote=CaptureDto(UUID.randomUUID().toString(), UUID.randomUUID().toString(), device, "recording", 1, "2026-10-06T00:00:00Z", mediaStatus="connected", lastAckedSequence=0)
        val local=LocalCapture(UUID.randomUUID().toString(), System.currentTimeMillis(), UUID.randomUUID().toString(), CreateCaptureDto(device, UUID.randomUUID().toString(), "Fixture"), remote)
        var tap=CapturePcmTap()
        lateinit var wire: Wire
        val controller=CaptureDirectAsrController(store, fixture, fixture, fixture, scope, { fixture.authorized }, { tap.attach(exclusive=true) }, { Wire(it, fixture).also { value -> wire=value } })
        try { block(fixture, controller, local, { origin -> CapturePcmTap(origin).also { tap=it } }, { wire }, store, scope, viewer) }
        finally { controller.close(); scope.coroutineContext[Job]?.cancelAndJoin(); store.close() }
    }
    @Test fun pauseResumeKeepsAbsoluteOffsetsAndSavesBothTailSentences() = runBlocking {
        withFixture { fixture, controller, local, newTap, wire, _, _, _ ->
            val first=newTap(1000)
            controller.start(local); waitFor { controller.state.value.phase == "running" }
            first.offer(ShortArray(1600), 1600); waitFor { wire().input == 100L }
            controller.pause()
            assertEquals(1000L, controller.state.value.rows.single().startMs)
            val next=newTap(1100)
            controller.resume(local); waitFor { controller.state.value.phase == "running" }
            next.offer(ShortArray(1600),1600); waitFor { wire().input == 100L }
            controller.save()
            assertEquals("saved", controller.state.value.phase)
            assertEquals(listOf(1000L,1100L), fixture.delivered.values.map { it.startMs })
            assertEquals(2, fixture.models)
            controller.start(local); delay(100)
            assertEquals("saved", controller.state.value.phase)
            assertEquals(2, fixture.models)
            assertEquals(2, fixture.delivered.size)
        }
    }
    @Test fun lostFinishResponseRetriesTextWithoutReconnectingModel() = runBlocking {
        withFixture { fixture, controller, local, newTap, wire, _, _, _ ->
            val tap=newTap(0)
            controller.start(local); waitFor { controller.state.value.phase == "running" }
            tap.offer(ShortArray(1600),1600); waitFor { wire().input == 100L }
            fixture.loseFinish=true
            controller.save()
            assertEquals("save_error", controller.state.value.phase)
            controller.save()
            assertEquals("saved", controller.state.value.phase)
            assertEquals(1, fixture.models); assertEquals(1, fixture.delivered.size)
        }
    }
    @Test fun networkSaveFailureSurvivesControllerRecreationWithoutAudioReplay() = runBlocking {
        withFixture { fixture, controller, local, newTap, wire, store, scope, _ ->
            val tap=newTap(0)
            controller.start(local); waitFor { controller.state.value.phase == "running" }
            tap.offer(ShortArray(1600),1600); waitFor { wire().input == 100L }
            fixture.failSync=true
            controller.save()
            assertEquals("save_error", controller.state.value.phase)
            assertEquals(1, store.rows(local.id).size)
            controller.close(); fixture.failSync=false
            val recovered=CaptureDirectAsrController(store, fixture, fixture, fixture, scope, { true }, { error("No microphone on recovery") }, { error("No paid model replay") })
            try {
                recovered.recover(local.id); recovered.save()
                assertEquals("saved", recovered.state.value.phase)
                assertEquals(1, fixture.models); assertEquals(1, fixture.delivered.size)
            } finally { recovered.close() }
        }
    }
    @Test fun modelDisconnectStopsDirectAsrAndDoesNotStartCloudOrReplay() = runBlocking {
        withFixture { fixture, controller, local, newTap, wire, _, _, _ ->
            val tap=newTap(0)
            controller.start(local); waitFor { controller.state.value.phase == "running" }
            wire().fail=true; tap.offer(ShortArray(1600),1600)
            waitFor { controller.state.value.phase == "error" }
            assertTrue(controller.state.value.gap)
            assertEquals(1, fixture.models)
            controller.save(); assertTrue(fixture.finished)
        }
    }
    @Test fun stoppingWhileCredentialsArePendingDoesNotOpenModelOrMicrophone() = runBlocking {
        withFixture { fixture, controller, local, _, _, _, _, _ ->
            val gate=CompletableDeferred<Unit>(); fixture.credentialGate=gate
            controller.start(local); waitFor { fixture.created }
            val stopping=async { controller.save() }
            delay(100); gate.complete(Unit); stopping.await()
            assertEquals("saved", controller.state.value.phase)
            assertEquals(0,fixture.models); assertTrue(fixture.finished)
        }
    }
    @Test fun lostAllocationCanBeClosedWithoutOpeningPaidModel() = runBlocking {
        withFixture { fixture, controller, local, _, _, _, _, _ ->
            fixture.loseStart=true
            controller.start(local); waitFor { controller.state.value.phase == "error" }
            controller.save()
            assertEquals("saved", controller.state.value.phase)
            assertEquals(0,fixture.models); assertTrue(fixture.finished)
        }
    }
    @Test fun accountRevocationPreventsFurtherTextDelivery() = runBlocking {
        withFixture { fixture, controller, local, newTap, wire, _, _, _ ->
            val tap=newTap(0)
            controller.start(local); waitFor { controller.state.value.phase == "running" }
            tap.offer(ShortArray(1600),1600); waitFor { wire().input == 100L }
            fixture.authorized=false
            controller.pause(); controller.save()
            assertTrue(fixture.delivered.isEmpty()); assertFalse(fixture.finished)
            assertEquals(1,fixture.models)
        }
    }
}
