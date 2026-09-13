package com.we.meet.ui.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.CaptureTranscriptionApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.CaptureTranscriptionCoordinator
import com.we.meet.data.capture.MeetingIntentKind
import com.we.meet.data.capture.MeetingIntentStore
import com.we.meet.data.repository.CaptureTranscriptionRepository
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class MeetingIntentStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "intent-fixture-${UUID.randomUUID()}"
    private var currentViewer: String? = viewer
    private var store = MeetingIntentStore.open(context, viewer) { currentViewer }
    private val capture = UUID.randomUUID().toString()
    private val kind = MeetingIntentKind.CAPTURE_ASR
    @After fun close() { store.close() }

    @Test fun unknownPaidOutcomeSurvivesRestartAndIgnoresChangedUiOptions() = runBlocking {
        val api = Fixture()
        val repository = CaptureTranscriptionRepository(api) { currentViewer }
        var coordinator = CaptureTranscriptionCoordinator(viewer, store, repository)
        api.lost = true
        assertTrue(runCatching { coordinator.submit(capture, CaptureAsrRequestDto(null, false, true)) }.isFailure)
        val frozen = coordinator.pending(capture)!!
        store.close()
        store = MeetingIntentStore.open(context, viewer) { currentViewer }
        coordinator = CaptureTranscriptionCoordinator(viewer, store, repository)
        assertEquals(frozen, coordinator.pending(capture))
        assertEquals(1, api.keys.size) // Opening a store never calls the API.
        coordinator.submit(capture, CaptureAsrRequestDto(UUID.randomUUID().toString(), true, false))
        assertEquals(listOf(frozen.key, frozen.key), api.keys)
        assertEquals(api.bodies.first(), api.bodies.last())
        assertNull(coordinator.pending(capture))
    }

    @Test fun throttlingRetainsIntentWhileDefinitiveConflictClearsOnlyThatRequest() = runBlocking {
        val api = Fixture()
        val coordinator = CaptureTranscriptionCoordinator(viewer, store, CaptureTranscriptionRepository(api) { currentViewer })
        api.status = 429
        assertTrue(runCatching { coordinator.submit(capture, CaptureAsrRequestDto(null, false, true)) }.isFailure)
        val pending = coordinator.pending(capture)!!
        api.status = 409
        assertTrue(runCatching { coordinator.submit(capture, CaptureAsrRequestDto(null, false, true)) }.isFailure)
        assertEquals(listOf(pending.key, pending.key), api.keys)
        assertNull(coordinator.pending(capture))
    }

    @Test fun staleResolutionCannotClearNewIntentAndAccountSwitchCannotReadOldBody() {
        val first = store.getOrCreate(kind, capture, "{\"request\":1}")
        store.resolve(kind, capture, first)
        val second = store.getOrCreate(kind, capture, "{\"request\":2}")
        assertTrue(runCatching { store.resolve(kind, capture, first) }.isFailure)
        assertEquals(second, store.get(kind, capture))
        currentViewer = "another-${UUID.randomUUID()}"
        assertTrue(runCatching { store.get(kind, capture) }.isFailure)
        MeetingIntentStore.open(context, currentViewer!!) { currentViewer }.use { assertNull(it.get(kind, capture)) }
    }

    @Test fun fullStorePreservesUnknownIntentsInsteadOfExpiringOrOverwritingThem() {
        val first = store.getOrCreate(kind, capture, "{}")
        repeat(127) { store.getOrCreate(kind, UUID.randomUUID().toString(), "{}") }
        assertTrue(runCatching { store.getOrCreate(kind, UUID.randomUUID().toString(), "{}") }.isFailure)
        assertEquals(first, store.getOrCreate(kind, capture, "{\"changed\":true}"))
    }

    private class Fixture : CaptureTranscriptionApi {
        var lost = false
        var status = 200
        val keys = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        override suspend fun request(capture: String, key: String, request: RequestBody): CaptureAsrCreatedDto {
            keys += key
            bodies += Buffer().also { request.writeTo(it) }.readUtf8()
            if (lost) { lost = false; throw IOException("Synthetic lost response") }
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            return CaptureAsrCreatedDto(CaptureAsrJobDto(UUID.randomUUID().toString(), 1, "queued", 0, 0, 0, "uploading", "live", false), false)
        }
        override suspend fun state(capture: String): CaptureAsrStateDto = error("Unexpected read")
        override suspend fun cancel(capture: String, job: String, empty: RequestBody): CaptureAsrJobDto = error("Unexpected cancellation")
        override suspend fun preview(capture: String, job: String, after: Int): CaptureAsrPreviewDto = error("Unexpected preview")
    }
}
