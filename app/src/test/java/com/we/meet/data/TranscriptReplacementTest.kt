package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingRecordApi
import com.we.meet.data.api.dto.ReplacementConfirmation
import com.we.meet.data.repository.MeetingRecordRepository
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class TranscriptReplacementTest {
    private val record = "11111111-1111-4111-8111-111111111111"
    private val batch = "22222222-2222-4222-8222-222222222222"
    private val hash = "a".repeat(64)
    private val requests = mutableListOf<Pair<String, String>>()
    private var viewer = "owner"
    private var reply = ""
    private var code = 200
    private var switchViewer = false
    private val receipt get() = """{"id":"$batch","find":"word","replacement":"world","changed_segments":1,"created_at":"2026-09-20T07:00:00Z","undone":false}"""
    private fun repository(): MeetingRecordRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
            requests += request.url.encodedPath to body
            if (switchViewer) viewer = "other"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(reply.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
            .build().create(MeetingRecordApi::class.java)
        return MeetingRecordRepository(api) { viewer }
    }
    @Test fun retrofitSendsExactPreviewAndConfirmationFields() = runBlocking {
        val repo = repository()
        reply = """{"record_id":"$record","preview_hash":"$hash","occurrences":1,"changes":[{"id":"$batch","before":"word","after":"world","start_ms":0}]}"""
        assertTrue(repo.previewReplacement("owner", record, "word", "world").isSuccess)
        assertEquals("/api/v1.0/meeting-records/$record/transcript-replacements/preview/", requests.last().first)
        reply = receipt
        val intent = ReplacementConfirmation(batch, "word", "world", hash)
        assertTrue(repo.applyReplacement("owner", record, intent).isSuccess)
        assertTrue(repo.applyReplacement("owner", record, intent).isSuccess)
        assertEquals(requests[1], requests[2])
        assertTrue(requests.last().second.contains("\"expected_hash\":\"$hash\""))
    }
    @Test fun staleResponseRemainsAConflictAndNeverAutomaticallyRetries() = runBlocking {
        reply = "{}"; code = 409
        val result = repository().applyReplacement("owner", record, ReplacementConfirmation(batch, "word", "world", hash))
        assertEquals(409, (result.exceptionOrNull() as retrofit2.HttpException).code())
        assertEquals(1, requests.size)
    }
    @Test fun previewForAnotherRecordIsRejected() = runBlocking {
        reply = """{"record_id":"$batch","preview_hash":"$hash","occurrences":0,"changes":[]}"""
        assertTrue(repository().previewReplacement("owner", record, "word", "world").isFailure)
    }
    @Test fun responsesCannotCrossAnAccountSwitch() = runBlocking {
        reply = receipt; switchViewer = true
        assertTrue(repository().applyReplacement("owner", record, ReplacementConfirmation(batch, "word", "world", hash)).isFailure)
    }
    @Test fun undoRequiresTheExactReceiptAndAnUndoneResponse() = runBlocking {
        reply = receipt.replace("false", "true")
        assertTrue(repository().undoReplacement("owner", record, batch).isSuccess)
        assertEquals("/api/v1.0/meeting-records/$record/transcript-replacements/$batch/undo/", requests.single().first)
        reply = receipt
        assertTrue(repository().undoReplacement("owner", record, batch).isFailure)
    }
    @Test fun invalidSelectionCannotReachTheNetwork() = runBlocking {
        val repo = repository()
        assertTrue(repo.previewReplacement("owner", record, " ", "x").isFailure)
        assertTrue(repo.previewReplacement("owner", record, "word", "word").isFailure)
        assertTrue(repo.previewReplacement("owner", record, "word", "x".repeat(201)).isFailure)
        assertTrue(requests.isEmpty())
    }
}
