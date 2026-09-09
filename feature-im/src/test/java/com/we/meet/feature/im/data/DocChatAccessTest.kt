package com.we.meet.feature.im.data

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DocChatAccessTest {
    private fun repository(reply: Map<String, Any>, onRequest: (Map<*, *>) -> Unit = {}): ImBridgeRepository {
        val api = Proxy.newProxyInstance(ImApi::class.java.classLoader, arrayOf(ImApi::class.java)) { _, method, args ->
            check(method.name == "docChatAccess")
            onRequest(args[0] as Map<*, *>)
            reply
        } as ImApi
        return ImBridgeRepository(api)
    }

    @Test
    fun `comment permission reaches the API and is confirmed`() = runBlocking {
        val reply = mapOf("scoped" to true, "role" to "commenter", "complete" to true)
        val repo = repository(reply) {
            assertEquals(mapOf("doc_id" to "doc", "cid" to "chat", "role" to "commenter"), it)
        }
        assertEquals(reply, repo.docChatAccess("doc", "chat", "commenter"))
    }

    @Test
    fun `reading card permissions never sends a grant`() = runBlocking {
        val reply = mapOf("scoped" to true, "role" to "commenter", "complete" to true)
        val repo = repository(reply) {
            assertEquals(mapOf("doc_id" to "doc", "cid" to "chat"), it)
        }
        assertEquals("commenter", repo.docChatAccess("doc", "chat")["role"])
    }

    @Test
    fun `comment permission rejects mismatched incomplete and legacy confirmations`() {
        listOf(
            mapOf("scoped" to true, "role" to "reader", "complete" to true),
            mapOf("scoped" to true, "role" to "commenter", "complete" to false),
            mapOf("role" to "commenter", "complete" to true),
        ).forEach { reply ->
            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository(reply).docChatAccess("doc", "chat", "commenter") }
            }
        }
    }
}
