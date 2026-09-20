package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.RecordingUploadApi
import com.we.meet.data.repository.RecordingUploadRepository
import com.we.meet.data.repository.mergePersonalHotwords
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class PersonalHotwordsTest {
    private var viewer = "owner"
    private var switchViewer = false
    private var code = 200
    private var reply = """{"words":["Qwen"],"revision":1}"""
    private val requests = mutableListOf<Pair<String, String>>()
    private fun repository(): RecordingUploadRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.method to okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
            assertEquals("/api/v1.0/recording-hotwords/", request.url.encodedPath)
            if (switchViewer) viewer = "reader"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(reply.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
            .build().create(RecordingUploadApi::class.java)
        return RecordingUploadRepository(api, currentViewer = { viewer })
    }
    @Test fun getDoesNotWriteAndSaveUsesTheReviewedRevision() = runBlocking {
        val repo = repository()
        assertEquals(listOf("Qwen"), repo.personalHotwords("owner").getOrThrow().words)
        assertEquals("GET", requests.single().first)
        assertTrue(repo.savePersonalHotwords("owner", "Qwen", 0).isSuccess)
        assertEquals("PUT", requests.last().first)
        assertTrue(requests.last().second.contains("\"expected_revision\":0"))
    }
    @Test fun conflictIsPreservedWithoutAutomaticRetry() = runBlocking {
        code = 409; reply = "{}"
        val result = repository().savePersonalHotwords("owner", "Draft", 1)
        assertEquals(409, (result.exceptionOrNull() as retrofit2.HttpException).code())
        assertEquals(1, requests.size)
    }
    @Test fun accountSwitchCannotReturnThePreviousOwnersVocabulary() = runBlocking {
        switchViewer = true
        assertTrue(repository().personalHotwords("owner").isFailure)
    }
    @Test fun malformedOrOversizedVocabularyIsRejected() = runBlocking {
        reply = """{"words":["Qwen","Qwen"],"revision":1}"""
        assertTrue(repository().personalHotwords("owner").isFailure)
        val repo = repository(); requests.clear()
        assertTrue(repo.savePersonalHotwords("owner", "x".repeat(41), 0).isFailure)
        assertTrue(repo.savePersonalHotwords("owner", "valid", -1).isFailure)
        assertTrue(requests.isEmpty())
    }
    @Test fun mergePreservesManualWordsOrderCaseAndUnicode() {
        assertEquals("Existing\nQwen\nqwen\n妙记", mergePersonalHotwords(" Existing\r\nQwen\nqwen", listOf("Qwen", "妙记")))
        assertEquals("𠮷".repeat(40), mergePersonalHotwords("𠮷".repeat(40), emptyList()))
        assertTrue(runCatching { mergePersonalHotwords("𠮷".repeat(41), emptyList()) }.isFailure)
        assertTrue(runCatching { mergePersonalHotwords((0..99).joinToString("\n"), listOf("overflow")) }.isFailure)
    }
    @Test fun emptySaveClearsOnlyTheVocabulary() = runBlocking {
        reply = """{"words":[],"revision":2}"""
        assertEquals(emptyList<String>(), repository().savePersonalHotwords("owner", "", 1).getOrThrow().words)
        assertEquals("PUT", requests.single().first)
        assertTrue(requests.single().second.contains("\"text\":\"\""))
    }
}
