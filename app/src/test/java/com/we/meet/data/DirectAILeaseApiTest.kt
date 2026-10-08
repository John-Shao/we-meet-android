package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.AssistantTranscriptionApi
import com.we.meet.data.api.AssistantTranslationApi
import com.we.meet.data.api.DirectAsrCredentials
import com.we.meet.feature.assistant.aicall.data.AiAgentApi
import com.we.meet.feature.assistant.aicall.data.DirectAILeaseOperation
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.UUID

class DirectAILeaseApiTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Test fun allThreeFeaturesSendLifecycleRequestsThroughRetrofit() = runBlocking {
        val id = UUID.randomUUID().toString()
        val operations = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("POST", request.method)
            assertEquals("/api/v1.0/direct-ai/sessions/$id/", request.url.encodedPath)
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            operations += moshi.adapter(DirectAILeaseOperation::class.java).fromJson(body)!!.operation
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("Fixture").body("{\"status\":\"active\"}".toResponseBody()).build()
        }.build()
        val retrofit = Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build()
        retrofit.create(AiAgentApi::class.java).sessionLease(id, DirectAILeaseOperation("heartbeat"))
        retrofit.create(AssistantTranslationApi::class.java).sessionLease(id, DirectAILeaseOperation("close"))
        retrofit.create(AssistantTranscriptionApi::class.java).sessionLease(id, DirectAILeaseOperation("heartbeat"))
        assertEquals(listOf("heartbeat", "close", "heartbeat"), operations)
    }

    @Test fun allocationMetadataIsOptionalForOlderServersAndDecodesEnforcement() {
        val adapter = moshi.adapter(DirectAsrCredentials::class.java)
        val prefix = "\"model\":\"fixture\",\"url\":\"wss://fixture.invalid/\",\"token\":\"fixture\",\"expires_at\":1"
        assertNull(adapter.fromJson("{$prefix}")!!.sessionLease)
        val id = UUID.randomUUID().toString()
        val lease = adapter.fromJson("{$prefix,\"session_lease\":{\"id\":\"$id\",\"ttl_seconds\":120,\"heartbeat_seconds\":30,\"enforce\":true}}")!!.sessionLease!!
        assertEquals(id, lease.id)
        assertEquals(120, lease.ttlSeconds)
        assertEquals(30, lease.heartbeatSeconds)
        assertTrue(lease.enforce)
    }
}
