package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.*
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.CaptureWave
import com.we.meet.data.repository.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class CapturePlaybackRepositoryTest {
    private class Fixture {
        val record = UUID.randomUUID().toString()
        val capture = UUID.randomUUID().toString()
        var viewer: String? = "owner"
        var permission = true
        var retention = "media"
        var status = "stopped"
        var revision = 4
        var gap = false
        var count = 2
        var mutateManifest = false
        var mutateAfterDownload = false
        var badHash = false
        var extraByte = false
        var wrongMime = false
        var shortBody = false
        var otherViewerAfterDownload = false
        var stored = true
        var binaryReads = 0
        val requests = mutableListOf<Request>()
        val audio = CaptureWave.encode(ShortArray(16000) { (it % 40).toShort() })
        val checksum = CaptureWave.inspect(audio).checksum
        val ids = (1..205).map { UUID.randomUUID().toString() }
        private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        private inline fun <reified T> json(value: T) = moshi.adapter(T::class.java).toJson(value).toResponseBody("application/json".toMediaType())
        private fun chunk(sequence: Int) = CaptureAudioReceiptDto(ids[sequence - 1], sequence,
            (sequence - 1) * 1000L + if (gap && sequence > 1) 1000 else 0,
            1000, if (badHash) "0".repeat(64) else checksum, audio.size, stored)
        private fun response(request: Request): ResponseBody {
            val path = request.url.encodedPath
            if (path == "/api/v1.0/meeting-records/$record/") return json(RecordDto(record, "audio_recording", "Fixture audio", "2026-09-13T00:00:00Z", 3,
                RecordCapabilitiesDto(true, permission), captureId = capture, retentionMode = retention))
            if (path == "/api/v1.0/capture-sessions/$capture/") return json(CaptureDto(capture, record, "device", status, revision, "2026-09-13T00:00:00Z", mediaStatus = "saved", lastAckedSequence = count))
            if (path == "/api/v1.0/capture-sessions/$capture/audio/") {
                val after = request.url.queryParameter("after_sequence")!!.toInt()
                val rows = (after + 1..count).take(100).map(::chunk)
                val manifest = CaptureManifestDto(count, if (gap || !stored) "incomplete" else "saved", if (stored) count * 1000L else 0,
                    if (stored) emptyList() else (1..count).toList(), if (gap) listOf(CaptureGapDto(1000, 2000)) else emptyList())
                return json(CaptureReceiptsDto(rows, if (after + 100 < count) after + 100 else null,
                    if (mutateManifest && after > 0) manifest.copy(durationMs = manifest.durationMs + 1) else manifest))
            }
            require(path.startsWith("/api/v1.0/capture-sessions/$capture/audio/") && ids.any { path.endsWith("/$it/") })
            binaryReads++
            if (mutateAfterDownload) permission = false
            if (otherViewerAfterDownload) viewer = "other"
            val bytes = if (extraByte) audio + byteArrayOf(1) else if (shortBody) audio.copyOf(audio.size - 1) else audio
            val mime = (if (wrongMime) "text/html" else "audio/wav").toMediaType()
            return object : ResponseBody() {
                private val buffer = Buffer().write(bytes)
                override fun contentType() = mime
                override fun contentLength() = -1L // Exercise bounded streaming, not Content-Length alone.
                override fun source() = buffer
            }
        }
        val repo: CapturePlaybackRepository
        init {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests += request
                assertEquals("meeting.invalid", request.url.host)
                assertEquals("no-store", request.header("Cache-Control"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(response(request)).build()
            }.build()
            val retrofit = Retrofit.Builder().baseUrl("https://meeting.invalid/").client(client).addConverterFactory(MoshiConverterFactory.create(moshi)).build()
            repo = CapturePlaybackRepository(CaptureRepository(retrofit.create(CaptureApi::class.java)) { viewer },
                MeetingRecordRepository(retrofit.create(MeetingRecordApi::class.java)) { viewer }, retrofit.create(CapturePlaybackApi::class.java)) { viewer }
        }
    }
    @Test fun exactSealedPlaylistAndVerifiedWavStayOnAuthenticatedFixedPaths() = runBlocking {
        val fixture = Fixture()
        val playlist = fixture.repo.playlist("owner", fixture.record).getOrThrow()
        assertEquals(2, playlist.chunks.size)
        assertEquals(2000L, playlist.endMs)
        assertEquals(0, playlist.locate(999))
        assertEquals(1, playlist.locate(1000))
        assertNull(playlist.locate(2000))
        assertEquals(0, fixture.binaryReads)
        val bytes = fixture.repo.audio("owner", playlist, 1).getOrThrow()
        assertArrayEquals(fixture.audio, bytes)
        assertEquals(1, fixture.binaryReads)
        assertTrue(fixture.requests.any { it.url.queryParameter("after_sequence") == "4320" })
    }
    @Test fun gapsRemainExplicitAndUnstoredRowsAreNeverPlayable() = runBlocking {
        val fixture = Fixture().apply { gap = true }
        val playlist = fixture.repo.playlist("owner", fixture.record).getOrThrow()
        assertNull(playlist.locate(1500))
        assertEquals(1, playlist.locate(2000))
        fixture.gap = false
        fixture.stored = false
        assertTrue(fixture.repo.playlist("owner", fixture.record).getOrThrow().chunks.isEmpty())
    }
    @Test fun pagesRemainBoundedAndChangingManifestInvalidatesWholePlaylist() = runBlocking {
        val fixture = Fixture().apply { count = 205 }
        assertEquals(205, fixture.repo.playlist("owner", fixture.record).getOrThrow().chunks.size)
        assertTrue(fixture.requests.any { it.url.queryParameter("after_sequence") == "200" })
        fixture.mutateManifest = true
        assertTrue(fixture.repo.playlist("owner", fixture.record).isFailure)
    }
    @Test fun liveTextOnlyAndRevokedRecordsCannotExposeAudio() = runBlocking {
        val fixture = Fixture()
        fixture.status = "recording"
        assertTrue(fixture.repo.playlist("owner", fixture.record).isFailure)
        fixture.status = "stopped"
        fixture.retention = "text_only"
        assertTrue(fixture.repo.playlist("owner", fixture.record).isFailure)
        fixture.retention = "media"
        fixture.permission = false
        assertTrue(fixture.repo.playlist("owner", fixture.record).isFailure)
        assertEquals(0, fixture.binaryReads)
    }
    @Test fun hashMimeTruncatedAndOversizedStreamsFailBeforePlayback() = runBlocking {
        val fixture = Fixture()
        fixture.badHash = true
        val bad = fixture.repo.playlist("owner", fixture.record).getOrThrow()
        assertTrue(fixture.repo.audio("owner", bad, 0).isFailure)
        fixture.badHash = false
        val good = fixture.repo.playlist("owner", fixture.record).getOrThrow()
        fixture.extraByte = true
        assertTrue(fixture.repo.audio("owner", good, 0).isFailure)
        fixture.extraByte = false
        fixture.shortBody = true
        assertTrue(fixture.repo.audio("owner", good, 0).isFailure)
        fixture.shortBody = false
        fixture.wrongMime = true
        assertTrue(fixture.repo.audio("owner", good, 0).isFailure)
    }
    @Test fun permissionOrAccountChangedDuringDownloadDiscardsBytes() = runBlocking {
        val fixture = Fixture()
        val playlist = fixture.repo.playlist("owner", fixture.record).getOrThrow()
        fixture.mutateAfterDownload = true
        assertTrue(fixture.repo.audio("owner", playlist, 0).isFailure)
        fixture.mutateAfterDownload = false
        fixture.permission = true
        fixture.otherViewerAfterDownload = true
        assertTrue(fixture.repo.audio("owner", playlist, 0).isFailure)
    }
    @Test fun captureRevisionChangeInvalidatesOldPlaylistBeforeDownloading() = runBlocking {
        val fixture = Fixture()
        val playlist = fixture.repo.playlist("owner", fixture.record).getOrThrow()
        fixture.revision++
        assertTrue(fixture.repo.checkAccess("owner", playlist).isFailure)
        assertTrue(fixture.repo.audio("owner", playlist, 0).isFailure)
        assertEquals(0, fixture.binaryReads)
    }
}
