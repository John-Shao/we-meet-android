package com.we.meet.feature.docs.data.net

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class DocsApiContractTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Test fun userSearchMinimumUsesServerConfigurationWithLegacyFallback() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/api/v1.0/config/", chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("""{"API_USERS_SEARCH_QUERY_MIN_LENGTH":6,"unrelated":true}""".toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://docs.example/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(DocsApi::class.java)
        assertEquals(6, api.config().userSearchMinLength)
        assertEquals(3, moshi.adapter(DocsConfigDto::class.java).fromJson("{}")!!.userSearchMinLength)
    }

    @Test fun restrictedLinksAcceptNullOptionsAndOmitRole() {
        val doc = moshi.adapter(DocumentDto::class.java).fromJson("""{"id":"doc","abilities":{"link_select_options":{"restricted":null,"authenticated":["reader","editor"]}}}""")!!
        assertTrue(doc.abilities.linkSelectOptions.containsKey("restricted"))
        assertNull(doc.abilities.linkSelectOptions["restricted"])
        val body = moshi.adapter(DocsLinkConfigurationRequest::class.java)
            .toJson(DocsLinkConfigurationRequest("restricted", null))
        // The service accepts an omitted role for restricted reach, but rejects
        // explicit null because the stored model field itself is non-nullable.
        assertEquals("""{"link_reach":"restricted"}""", body)
    }

    @Test fun resolvedThreadAndReactionPermissionsMatchServer() {
        val thread = moshi.adapter(DocsThreadDto::class.java).fromJson("""
            {"id":"thread","resolved":true,"resolved_by":"user-uuid",
             "abilities":{"unresolve":true},"comments":[{"id":"comment",
             "abilities":{"reactions":true},"body":[{"type":"paragraph","content":[]}]}]}
        """)!!
        assertEquals("user-uuid", thread.resolvedBy)
        assertTrue(thread.abilities.unresolve)
        assertTrue(thread.comments.single().abilities.react)
    }

    @Test fun deletingReactionSendsRequiredJsonBody() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("DELETE", request.method)
            assertEquals("/api/v1.0/documents/doc/threads/thread/comments/comment/reactions/", request.url.encodedPath)
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            assertEquals("{\"emoji\":\"thumb\"}", buffer.readUtf8())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(204)
                .message("No Content").body("".toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://docs.example/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(DocsApi::class.java)
        api.removeReaction("doc", "thread", "comment", DocsReactionRequest("thumb"))
    }

    @Test fun accessesAcceptServerUnpaginatedArray() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("""[{"id":"access","user":{"id":"user"},"team":"","role":"owner","abilities":{"set_role_to":[]}}]""".toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://docs.example/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(DocsApi::class.java)
        val access = api.accesses("doc").single()
        assertEquals("access", access.id)
        assertEquals("owner", access.role)
        assertEquals("user", access.user?.id)
    }
}
